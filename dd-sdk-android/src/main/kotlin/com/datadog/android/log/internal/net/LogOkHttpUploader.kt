/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-2019 Datadog, Inc.
 */

package com.datadog.android.log.internal.net

import android.os.Build
import com.datadog.android.BuildConfig
import com.datadog.android.log.internal.extensions.asDataDogTag
import com.datadog.android.log.internal.utils.sdkLogger
import java.io.IOException
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

internal class LogOkHttpUploader(
    private val endpoint: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder().build()
) : LogUploader {

    private val userAgent = System.getProperty(SYSTEM_UA).let {
        if (it.isNullOrBlank()) {
            "Datadog/${BuildConfig.VERSION_NAME} " +
                "(Linux; U; Android ${Build.VERSION.RELEASE}; " +
                "${Build.MODEL} Build/${Build.ID})"
        } else {
            it
        }
    }

    // region LogUploader

    @Suppress("TooGenericExceptionCaught")
    override fun uploadLogs(logs: List<String>): LogUploadStatus {

        return try {
            val request = buildRequest(logs)
            val response = client.newCall(request).execute()
            sdkLogger.i(
                    "$TAG: Response code:${response.code()} " +
                            "body:${response.body()?.string()} " +
                            "headers:${response.headers()}"
            )
            responseCodeToLogUploadStatus(response.code())
        } catch (e: IOException) {
            sdkLogger.e("$TAG: Network error", e)
            LogUploadStatus.NETWORK_ERROR
        }
    }

    // endregion

    // region Internal

    private fun buildUrl(endpoint: String, token: String): String {
        return String.format(Locale.US, UPLOAD_URL, endpoint, token)
    }

    private fun buildBody(logs: List<String>): String {
        return logs.joinToString(separator = ",", prefix = "[", postfix = "]")
    }

    private fun buildRequest(logs: List<String>): Request {
        val url = buildUrl(endpoint, token)
        val body = buildBody(logs)
        sdkLogger.d("$TAG: Sending logs to $url")
        sdkLogger.d("$TAG: $body")
        return Request.Builder()
            .url(url)
            .post(RequestBody.create(null, body))
            .addHeader(HEADER_UA, userAgent)
            .addHeader(HEADER_CT, CONTENT_TYPE)
            .build()
    }

    private fun responseCodeToLogUploadStatus(code: Int): LogUploadStatus {
        return when (code) {
            in 200..299 -> LogUploadStatus.SUCCESS
            in 300..399 -> LogUploadStatus.HTTP_REDIRECTION
            in 400..499 -> LogUploadStatus.HTTP_CLIENT_ERROR
            in 500..599 -> LogUploadStatus.HTTP_SERVER_ERROR
            else -> LogUploadStatus.UNKNOWN_ERROR
        }
    }

    // endregion

    companion object {

        private const val HEADER_UA = "User-Agent"
        private const val HEADER_CT = "Content-Type"

        private const val CONTENT_TYPE = "application/json"

        const val SYSTEM_UA = "http.agent"

        private const val QP_SOURCE = "ddsource"
        private const val DD_SOURCE_MOBILE = "mobile"

        const val UPLOAD_URL = "%s/v1/input/%s?$QP_SOURCE=$DD_SOURCE_MOBILE"
        val TAG = "LogOkHttpUploader".asDataDogTag()
    }
}
