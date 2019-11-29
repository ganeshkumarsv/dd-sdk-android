/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-2019 Datadog, Inc.
 */

package com.datadog.android.log.internal.net

import android.os.Build
import com.datadog.android.BuildConfig
import com.datadog.android.log.forge.Configurator
import com.datadog.android.utils.setStaticValue
import com.datadog.android.utils.extension.SystemOutStream
import com.datadog.android.utils.extension.SystemOutputExtension
import fr.xgouchet.elmyr.Forge
import fr.xgouchet.elmyr.junit5.ForgeConfiguration
import fr.xgouchet.elmyr.junit5.ForgeExtension
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class),
    ExtendWith(SystemOutputExtension::class)
)
@MockitoSettings()
@ForgeConfiguration(Configurator::class)
internal class LogOkHttpUploaderTest {

    lateinit var mockWebServer: MockWebServer

    lateinit var testedUploader: LogUploader

    lateinit var fakeEndpoint: String
    lateinit var fakeToken: String
    lateinit var fakeUserAgent: String

    @BeforeEach
    fun `set up`(forge: Forge) {

        Build.VERSION::class.java.setStaticValue("RELEASE", forge.anAlphaNumericalString())
        Build::class.java.setStaticValue("MODEL", forge.anAlphabeticalString())
        Build::class.java.setStaticValue("ID", forge.anAlphabeticalString())

        mockWebServer = MockWebServer()
        mockWebServer.start()
        fakeEndpoint = mockWebServer.url("/").toString().removeSuffix("/")
        fakeToken = forge.anHexadecimalString()
        fakeUserAgent = if (forge.aBool()) forge.anAlphaNumericalString() else ""
        System.setProperty("http.agent", fakeUserAgent)

        testedUploader = LogOkHttpUploader(
            fakeEndpoint,
            fakeToken,
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_TEST_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_TEST_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_TEST_MS, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    @AfterEach
    fun `tear down`() {
        mockWebServer.shutdown()

        Build.VERSION::class.java.setStaticValue("RELEASE", null)
        Build::class.java.setStaticValue("MODEL", null)
        Build::class.java.setStaticValue("ID", null)
    }

    @Test
    fun `uploads logs 100-Continue (timeout)`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(100))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 1xx-Informational`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(101, 200)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.UNKNOWN_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 200-OK`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(200))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.SUCCESS)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 204-NO CONTENT`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(204))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 205-RESET`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(205))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 2xx-Success`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(206, 299)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.SUCCESS)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 3xx-Redirection`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(300, 399)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_REDIRECTION)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 400-BadRequest`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(400))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_CLIENT_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 401-Unauthorized`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(401))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_CLIENT_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 404-NotFound`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(404))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_CLIENT_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 407-Proxy`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(407))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 4xx-ClientError`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(408, 499)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_CLIENT_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 500-InternalServerError`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(500))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_SERVER_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs 5xx-ServerError`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(500, 599)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.HTTP_SERVER_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads logs xxx-InvalidError`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(600, 1000)))

        val status = testedUploader.uploadLogs(logs)

        assertThat(status).isEqualTo(LogUploadStatus.UNKNOWN_ERROR)
        assertValidRequest(mockWebServer.takeRequest(), logs)
    }

    @Test
    fun `uploads with IOException (timeout)`(
        forge: Forge,
        @SystemOutStream systemOutputStream: ByteArrayOutputStream
    ) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(
            MockResponse()
                .throttleBody(THROTTLE_RATE, THROTTLE_PERIOD_MS, TimeUnit.MILLISECONDS)
                .setBody(
                    "{ 'success': 'ok', " +
                        "'message': 'Lorem ipsum dolor sit amet, consectetur adipiscing elit, " +
                        "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.' }"
                )
        )

        val status = testedUploader.uploadLogs(logs)
        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
        val logMessages = systemOutputStream.toString().trim().split("\n")
        val errorMessage = logMessages[logMessages.size - 1].trim()
        assertThat(errorMessage)
            .matches("E/android: DD_LOG\\+LogOkHttpUploader: .+")
    }

    @Test
    fun `uploads with IOException (protocol)`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(0, 100)))

        val status = testedUploader.uploadLogs(logs)
        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
    }

    @Test
    fun `uploads with IOException (protocol 2)`(forge: Forge) {
        val logs = forge.aList { anHexadecimalString() }
        mockWebServer.enqueue(mockResponse(forge.anInt(1000)))

        val status = testedUploader.uploadLogs(logs)
        assertThat(status).isEqualTo(LogUploadStatus.NETWORK_ERROR)
    }

    // region Internal

    private fun assertValidRequest(
        request: RecordedRequest,
        logs: List<String>
    ) {
        val expectedUserAgent = if (fakeUserAgent.isBlank()) {
            "Datadog/${BuildConfig.VERSION_NAME} " +
                "(Linux; U; Android ${Build.VERSION.RELEASE}; " +
                "${Build.MODEL} Build/${Build.ID})"
        } else {
            fakeUserAgent
        }

        assertThat(request.path)
            .isEqualTo("/v1/input/$fakeToken?ddsource=mobile")
        assertThat(request.getHeader("User-Agent"))
            .isEqualTo(expectedUserAgent)
        assertThat(request.getHeader("Content-Type"))
            .isEqualTo("application/json")
        assertThat(request.body.readUtf8())
            .isEqualTo(
                logs.joinToString(
                    separator = ",",
                    prefix = "[",
                    postfix = "]"
                )
            )
    }

    private fun mockResponse(code: Int): MockResponse {
        return MockResponse()
            .setResponseCode(code)
            .setBody("{}")
    }

    // endregion

    companion object {
        const val TIMEOUT_TEST_MS = 250L
        const val THROTTLE_RATE = 8L
        const val THROTTLE_PERIOD_MS = TIMEOUT_TEST_MS * 2
    }
}
