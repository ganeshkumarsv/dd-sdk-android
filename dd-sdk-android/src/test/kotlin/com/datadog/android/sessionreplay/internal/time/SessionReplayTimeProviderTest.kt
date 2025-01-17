/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.internal.time

import com.datadog.android.rum.internal.RumFeature
import com.datadog.android.utils.forge.Configurator
import com.datadog.android.v2.api.SdkCore
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.whenever
import fr.xgouchet.elmyr.annotation.LongForgery
import fr.xgouchet.elmyr.junit5.ForgeConfiguration
import fr.xgouchet.elmyr.junit5.ForgeExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class)

)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(Configurator::class)
internal class SessionReplayTimeProviderTest {

    @Mock
    lateinit var mockSdkCore: SdkCore

    lateinit var testedTimeProvider: SessionReplayTimeProvider

    @LongForgery(min = 1)
    var fakeCurrentTimeInMillis: Long = 0L

    @BeforeEach
    fun `set up`() {
        testedTimeProvider = SessionReplayTimeProvider(mockSdkCore) {
            fakeCurrentTimeInMillis
        }
    }

    @Test
    fun `M add the rum view timestamp offset W getDeviceTimestamp() {offset provided}`(
        @LongForgery(min = 100000L) fakeTimestampOffset: Long
    ) {
        // Given
        val fakeFeaturesContext =
            mapOf(RumFeature.VIEW_TIMESTAMP_OFFSET_IN_MS_KEY to fakeTimestampOffset)
        whenever(
            mockSdkCore.getFeatureContext(RumFeature.RUM_FEATURE_NAME)
        ) doReturn fakeFeaturesContext

        // When
        val deviceTimestamp = testedTimeProvider.getDeviceTimestamp()

        // Then
        assertThat(deviceTimestamp).isEqualTo(fakeCurrentTimeInMillis + fakeTimestampOffset)
    }

    @Test
    fun `M return the current timestamp W getDeviceTimestamp() {no entry for offset value}`() {
        // Given
        whenever(mockSdkCore.getFeatureContext(RumFeature.RUM_FEATURE_NAME)) doReturn emptyMap()

        // When
        val deviceTimestamp = testedTimeProvider.getDeviceTimestamp()

        // Then
        assertThat(deviceTimestamp).isEqualTo(fakeCurrentTimeInMillis)
    }
}
