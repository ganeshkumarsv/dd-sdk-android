/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-2020 Datadog, Inc.
 */

package com.datadog.android.rum

import com.datadog.android.rum.internal.domain.RumContext
import com.datadog.android.rum.internal.monitor.NoOpRumMonitor
import com.datadog.android.utils.forge.Configurator
import com.datadog.tools.unit.setStaticValue
import com.nhaarman.mockitokotlin2.mock
import fr.xgouchet.elmyr.annotation.Forgery
import fr.xgouchet.elmyr.junit5.ForgeConfiguration
import fr.xgouchet.elmyr.junit5.ForgeExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extensions
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class)
)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(Configurator::class)
internal class GlobalRumTest {

    @BeforeEach
    fun `set up`() {
        GlobalRum.isRegistered.set(false)
        GlobalRum.monitor = NoOpRumMonitor()
        GlobalRum::class.java.setStaticValue("sessionStartNs", AtomicLong(0L))
        GlobalRum::class.java.setStaticValue("lastUserInteractionNs", AtomicLong(0L))
    }

    @AfterEach
    fun `tear down`() {
        GlobalRum.isRegistered.set(false)
        GlobalRum.monitor = NoOpRumMonitor()
        GlobalRum.updateContext(RumContext())
        GlobalRum::class.java.setStaticValue("sessionStartNs", AtomicLong(0L))
        GlobalRum::class.java.setStaticValue("lastUserInteractionNs", AtomicLong(0L))
    }

    @Test
    fun `register monitor`() {
        val monitor: RumMonitor = mock()

        GlobalRum.registerIfAbsent(monitor)

        assertThat(GlobalRum.get())
            .isSameAs(monitor)
    }

    @Test
    fun `register monitor only once`() {
        val monitor: RumMonitor = mock()
        val monitor2: RumMonitor = mock()

        GlobalRum.registerIfAbsent(monitor)
        GlobalRum.registerIfAbsent(monitor2)

        assertThat(GlobalRum.get())
            .isSameAs(monitor)
    }

    @Test
    fun updateApplicationId(
        @Forgery applicationId: UUID
    ) {
        GlobalRum.updateApplicationId(applicationId)

        assertThat(GlobalRum.getRumContext().applicationId)
            .isEqualTo(applicationId)
    }

    @Test
    fun updateViewId(
        @Forgery viewId: UUID
    ) {
        GlobalRum.updateViewId(viewId)

        assertThat(GlobalRum.getRumContext().viewId)
            .isEqualTo(viewId)
    }

    @Test
    fun `getRumContext updates sessionId if first call`() {
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isNotEqualTo(UUID(0, 0))
    }

    @Test
    fun `getRumContext updates sessionId if last interaction too old`() {
        val firstSessionId = GlobalRum.getRumContext().sessionId

        Thread.sleep(INACTIVITY_MS * 2)
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isNotEqualTo(UUID(0, 0))
            .isNotEqualTo(firstSessionId)
    }

    @Test
    fun `getRumContext updates sessionId if duration is too long`() {
        val firstSessionId = GlobalRum.getRumContext().sessionId

        Thread.sleep(INACTIVITY_MS * 2)
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isNotEqualTo(UUID(0, 0))
            .isNotEqualTo(firstSessionId)
    }

    @Test
    fun `addUserInteraction updates sessionId if last interaction too old`() {
        val firstSessionId = GlobalRum.getRumContext().sessionId

        Thread.sleep(INACTIVITY_MS * 2)
        GlobalRum.addUserInteraction()
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isNotEqualTo(UUID(0, 0))
            .isNotEqualTo(firstSessionId)
    }

    @Test
    fun `getRumContext keeps sessionId if last interaction is recent`() {
        val firstSessionId = GlobalRum.getRumContext().sessionId

        Thread.sleep(INACTIVITY_MS / 3)
        GlobalRum.addUserInteraction()
        Thread.sleep(INACTIVITY_MS / 3)
        GlobalRum.addUserInteraction()
        Thread.sleep(INACTIVITY_MS / 3)
        GlobalRum.addUserInteraction()
        Thread.sleep(INACTIVITY_MS / 3)
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isEqualTo(firstSessionId)
    }

    @Test
    fun `updateContext resets the last interaction timestamp`(
        @Forgery injectedContext: RumContext
    ) {
        GlobalRum.updateContext(injectedContext)
        for (i in 0..16) {
            Thread.sleep(INACTIVITY_MS / 3)
            GlobalRum.addUserInteraction()
        }
        val context = GlobalRum.getRumContext()

        assertThat(context.sessionId)
            .isNotEqualTo(injectedContext.sessionId)
    }

    companion object {
        private const val INACTIVITY_MS = 100L
        private val INACTIVITY_NS = TimeUnit.MILLISECONDS.toNanos(INACTIVITY_MS)
        private val MAXIMUM_NS = TimeUnit.MILLISECONDS.toNanos(INACTIVITY_MS * 4)

        @JvmStatic
        @BeforeAll
        fun `set up constants`() {
            GlobalRum::class.java.setStaticValue("SESSION_INACTIVITY_NS", INACTIVITY_NS)
            GlobalRum::class.java.setStaticValue("SESSION_MAX_NS", MAXIMUM_NS)
        }

        @JvmStatic
        @AfterAll
        fun `tear down constants`() {
            GlobalRum::class.java.setStaticValue(
                "SESSION_INACTIVITY_NS",
                TimeUnit.MINUTES.toNanos(15)
            )
            GlobalRum::class.java.setStaticValue(
                "SESSION_MAX_NS",
                TimeUnit.HOURS.toNanos(4)
            )
        }
    }
}