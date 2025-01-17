/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.recorder

import android.view.MotionEvent
import android.view.Window
import com.datadog.android.sessionreplay.model.MobileSegment
import com.datadog.android.sessionreplay.processor.Processor
import com.datadog.android.sessionreplay.utils.ForgeConfigurator
import com.datadog.android.sessionreplay.utils.TimeProvider
import com.datadog.tools.unit.forge.aThrowable
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doAnswer
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import fr.xgouchet.elmyr.Forge
import fr.xgouchet.elmyr.annotation.IntForgery
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
import java.util.concurrent.TimeUnit

@Extensions(
    ExtendWith(MockitoExtension::class),
    ExtendWith(ForgeExtension::class)
)
@MockitoSettings(strictness = Strictness.LENIENT)
@ForgeConfiguration(ForgeConfigurator::class)
internal class RecorderWindowCallbackTest {

    lateinit var testedWindowCallback: RecorderWindowCallback

    @Mock
    lateinit var mockProcessor: Processor

    @Mock
    lateinit var mockWrappedCallback: Window.Callback

    @Mock
    lateinit var mockTimeProvider: TimeProvider

    @LongForgery(min = 0)
    var fakeTimestamp: Long = 0L

    // we will use ints here to avoid flakiness when converting to longs. It can happen
    // that the expected position to be 1 value higher due to the decimals conversion to longs when
    // normalizing with the device density.
    // This will not affect the tests validity in any way.
    @IntForgery(min = 1, max = 10)
    var fakeDensity: Int = 1

    @BeforeEach
    fun `set up`() {
        whenever(mockTimeProvider.getDeviceTimestamp()).thenReturn(fakeTimestamp)
        testedWindowCallback = RecorderWindowCallback(
            mockProcessor,
            fakeDensity.toFloat(),
            mockWrappedCallback,
            mockTimeProvider,
            copyEvent = { it },
            TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS,
            TEST_FLUSH_BUFFER_THRESHOLD_NS
        )
    }

    // region Unit Tests

    @Test
    fun `M delegate to the wrappedCallback W onTouchEvent`(forge: Forge) {
        // Given
        val fakeReturnedValue = forge.aBool()
        val mockEvent: MotionEvent = mock()
        whenever(mockWrappedCallback.dispatchTouchEvent(mockEvent)).thenReturn(fakeReturnedValue)

        // When
        val eventConsumed = testedWindowCallback.dispatchTouchEvent(mockEvent)

        // Then
        verify(mockWrappedCallback).dispatchTouchEvent(mockEvent)
        assertThat(eventConsumed).isEqualTo(fakeReturnedValue)
    }

    @Test
    fun `M recycle the copy after using it W onTouchEvent`(forge: Forge) {
        // Given
        val fakeReturnedValue = forge.aBool()
        val mockEvent: MotionEvent = mock()
        whenever(mockWrappedCallback.dispatchTouchEvent(mockEvent)).thenReturn(fakeReturnedValue)

        // When
        testedWindowCallback.dispatchTouchEvent(mockEvent)

        // Then
        verify(mockWrappedCallback).dispatchTouchEvent(mockEvent)
        verify(mockEvent).recycle()
    }

    @Test
    fun `M do nothing W onTouchEvent { event is null }`() {
        // Given
        val mockEvent: MotionEvent = mock()

        // When
        testedWindowCallback.dispatchTouchEvent(mockEvent)

        // Then
        assertThat(testedWindowCallback.positions).isEmpty()
    }

    @Test
    fun `M consume the event if wrappedCallback throws W onTouchEvent`(forge: Forge) {
        // Given
        val mockEvent: MotionEvent = mock()
        val fakeThrowable = forge.aThrowable()
        doThrow(fakeThrowable).whenever(mockWrappedCallback).dispatchTouchEvent(mockEvent)

        // When
        val eventConsumed = testedWindowCallback.dispatchTouchEvent(mockEvent)

        // Then
        verify(mockWrappedCallback).dispatchTouchEvent(mockEvent)
        assertThat(eventConsumed).isEqualTo(true)
    }

    @Test
    fun `M update the positions W onTouchEvent() { ActionDown }`(forge: Forge) {
        // Given
        val fakePositions = forge.positions()
        val relatedMotionEvent = fakePositions.asMotionEvent(MotionEvent.ACTION_DOWN)

        // When
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent)

        // Then
        assertThat(testedWindowCallback.positions).isEqualTo(fakePositions)
    }

    @Test
    fun `M update the positions and flush them W onTouchEvent() { ActionUp }`(forge: Forge) {
        // Given
        val fakePositions = forge.positions()
        val relatedMotionEvent = fakePositions.asMotionEvent(MotionEvent.ACTION_UP)

        // When
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent)

        // Then
        assertThat(testedWindowCallback.positions).isEmpty()
        verify(mockProcessor).process(MobileSegment.MobileIncrementalData.TouchData(fakePositions))
    }

    @Test
    fun `M debounce the positions update W onTouchEvent() {one gesture cycle}`(forge: Forge) {
        // Given
        val fakeEvent1Positions = forge.positions()
        val relatedMotionEvent1 = fakeEvent1Positions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeEvent2Positions = forge.positions()
        val relatedMotionEvent2 = fakeEvent2Positions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeEvent3Positions = forge.positions()
        val relatedMotionEvent3 = fakeEvent3Positions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeEvent4Positions = forge.positions()
        val relatedMotionEvent4 = fakeEvent4Positions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeEvent5Positions = forge.positions()
        val relatedMotionEvent5 = fakeEvent5Positions.asMotionEvent(MotionEvent.ACTION_UP)

        // When
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent1)
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent2)
        // must skip 3 as the motion update delay was not reached
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent3)
        Thread.sleep(
            TimeUnit.NANOSECONDS
                .toMillis(TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS)
        )
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent4)

        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent5)

        // Then
        verify(mockProcessor).process(
            MobileSegment.MobileIncrementalData.TouchData(
                fakeEvent1Positions +
                    fakeEvent2Positions +
                    fakeEvent4Positions +
                    fakeEvent5Positions
            )
        )
    }

    @Test
    fun `M perform intermediary flush W onTouchEvent() {one long gesture cycle}`(forge: Forge) {
        // Given
        val fakeDownEventPositions = forge.positions()
        val fakeDownEvent = fakeDownEventPositions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeMovePositionsBeforeFlush = forge.aList(size = forge.anInt(min = 1, max = 5)) {
            positions()
        }
        val fakeMoveEventsBeforeFlush = fakeMovePositionsBeforeFlush
            .map { it.asMotionEvent(MotionEvent.ACTION_MOVE) }
        val fakeMovePositionsAfterFlush = forge.aList(size = forge.anInt(min = 1, max = 5)) {
            positions()
        }
        val fakeMoveEventsAfterFlush = fakeMovePositionsAfterFlush
            .map { it.asMotionEvent(MotionEvent.ACTION_MOVE) }
        val fakeUpEventPositions = forge.positions()
        val fakeUpEvent = fakeUpEventPositions.asMotionEvent(MotionEvent.ACTION_UP)
        val expectedTouchData1 = MobileSegment.MobileIncrementalData.TouchData(
            fakeDownEventPositions +
                fakeMovePositionsBeforeFlush.flatten()
        )
        val expectedTouchData2 = MobileSegment.MobileIncrementalData.TouchData(
            fakeMovePositionsAfterFlush.flatten() +
                fakeUpEventPositions
        )

        // When
        testedWindowCallback.dispatchTouchEvent(fakeDownEvent)

        fakeMoveEventsBeforeFlush.forEachIndexed { index, event ->
            if (index == fakeMoveEventsBeforeFlush.size - 1) {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TEST_FLUSH_BUFFER_THRESHOLD_NS))
            } else {
                Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS))
            }
            testedWindowCallback.dispatchTouchEvent(event)
        }
        fakeMoveEventsAfterFlush.forEach {
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS))
            testedWindowCallback.dispatchTouchEvent(it)
        }

        testedWindowCallback.dispatchTouchEvent(fakeUpEvent)

        // Then
        val argumentCaptor = argumentCaptor<MobileSegment.MobileIncrementalData.TouchData>()
        verify(mockProcessor, times(2)).process(argumentCaptor.capture())
        assertThat(argumentCaptor.firstValue).isEqualTo(expectedTouchData1)
        assertThat(argumentCaptor.lastValue).isEqualTo(expectedTouchData2)
    }

    @Test
    fun `M always collect the first move event after down W onTouchEvent()`(forge: Forge) {
        // Given
        val fakeGesture1DownPositions = forge.positions()
        val fakeGesture1DownEvent = fakeGesture1DownPositions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeGesture1MovePositions = forge.positions()
        val fakeGesture1MoveEvent = fakeGesture1MovePositions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeGesture1UpPositions = forge.positions()
        val fakeGesture1UpEvent = fakeGesture1UpPositions.asMotionEvent(MotionEvent.ACTION_UP)

        val fakeGesture2DownPositions = forge.positions()
        val fakeGesture2DownEvent = fakeGesture2DownPositions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeGesture2MovePositions = forge.positions()
        val fakeGesture2MoveEvent = fakeGesture2MovePositions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeGesture2UpPositions = forge.positions()
        val fakeGesture2UpEvent = fakeGesture2UpPositions.asMotionEvent(MotionEvent.ACTION_UP)

        val expectedTouchData1 = MobileSegment.MobileIncrementalData.TouchData(
            fakeGesture1DownPositions +
                fakeGesture1MovePositions +
                fakeGesture1UpPositions
        )

        val expectedTouchData2 = MobileSegment.MobileIncrementalData.TouchData(
            fakeGesture2DownPositions +
                fakeGesture2MovePositions +
                fakeGesture2UpPositions
        )

        // When
        testedWindowCallback.dispatchTouchEvent(fakeGesture1DownEvent)
        testedWindowCallback.dispatchTouchEvent(fakeGesture1MoveEvent)
        testedWindowCallback.dispatchTouchEvent(fakeGesture1UpEvent)
        testedWindowCallback.dispatchTouchEvent(fakeGesture2DownEvent)
        testedWindowCallback.dispatchTouchEvent(fakeGesture2MoveEvent)
        testedWindowCallback.dispatchTouchEvent(fakeGesture2UpEvent)

        // Then
        val argumentCaptor = argumentCaptor<MobileSegment.MobileIncrementalData.TouchData>()
        verify(mockProcessor, times(2)).process(argumentCaptor.capture())
        assertThat(argumentCaptor.firstValue).isEqualTo(expectedTouchData1)
        assertThat(argumentCaptor.lastValue).isEqualTo(expectedTouchData2)
    }

    @Test
    fun `M flush the intermediary positions W onTouchEvent() {move event longer than threshold}`(
        forge: Forge
    ) {
        // Given
        val fakeDownPositions = forge.positions()
        val fakeDownEvent = fakeDownPositions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeEvent1MovePositions = forge.positions()
        val fakeMotion1Event = fakeEvent1MovePositions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeEvent2MovePositions = forge.positions()
        val fakeMotion2Event = fakeEvent2MovePositions.asMotionEvent(MotionEvent.ACTION_MOVE)

        val expectedTouchData1 = MobileSegment.MobileIncrementalData.TouchData(
            fakeDownPositions +
                fakeEvent1MovePositions
        )
        val expectedTouchData2 = MobileSegment.MobileIncrementalData.TouchData(fakeEvent2MovePositions)

        // When
        testedWindowCallback.dispatchTouchEvent(fakeDownEvent)
        Thread.sleep(
            TimeUnit.NANOSECONDS.toMillis(TEST_FLUSH_BUFFER_THRESHOLD_NS)
        )
        testedWindowCallback.dispatchTouchEvent(fakeMotion1Event)
        Thread.sleep(
            TimeUnit.NANOSECONDS
                .toMillis(TEST_FLUSH_BUFFER_THRESHOLD_NS)
        )
        testedWindowCallback.dispatchTouchEvent(fakeMotion2Event)

        // Then
        val argumentCaptor = argumentCaptor<MobileSegment.MobileIncrementalData.TouchData>()
        verify(mockProcessor, times(2)).process(argumentCaptor.capture())
        assertThat(argumentCaptor.firstValue).isEqualTo(expectedTouchData1)
        assertThat(argumentCaptor.lastValue).isEqualTo(expectedTouchData2)
    }

    @Test
    fun `M flush the positions W onTouchEvent() { full gesture lifecycle }`(forge: Forge) {
        // Given
        val fakeEvent1Positions = forge.positions()
        val relatedMotionEvent1 = fakeEvent1Positions.asMotionEvent(MotionEvent.ACTION_DOWN)
        val fakeEvent2Positions = forge.positions()
        val relatedMotionEvent2 = fakeEvent2Positions.asMotionEvent(MotionEvent.ACTION_MOVE)
        val fakeEvent3Positions = forge.positions()
        val relatedMotionEvent3 = fakeEvent3Positions.asMotionEvent(MotionEvent.ACTION_UP)

        // When
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent1)
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent2)
        testedWindowCallback.dispatchTouchEvent(relatedMotionEvent3)

        // Then
        verify(mockProcessor).process(
            MobileSegment.MobileIncrementalData.TouchData(
                fakeEvent1Positions +
                    fakeEvent2Positions +
                    fakeEvent3Positions
            )
        )
        assertThat(testedWindowCallback.positions).isEmpty()
    }

    // endregion

    // region Internal

    private fun Forge.positions(): List<MobileSegment.Position> {
        val pointerIds = aList { anInt(min = 1) }
        val positionMaxValue = (FLOAT_MAX_INT_VALUE / fakeDensity).toLong()
        return pointerIds.map {
            MobileSegment.Position(
                it.toLong(),
                aLong(min = 0, max = positionMaxValue),
                aLong(min = 0, max = positionMaxValue),
                fakeTimestamp
            )
        }
    }

    private fun List<MobileSegment.Position>.asMotionEvent(action: Int): MotionEvent {
        val mockMotionEvent: MotionEvent = mock { event ->
            whenever(event.action).thenReturn(action)
            whenever(event.pointerCount).thenReturn(this@asMotionEvent.size)
        }
        this.forEachIndexed { index, position ->
            val pointerId = position.id.toInt()
            whenever(mockMotionEvent.getPointerId(index)).thenReturn(pointerId)
            doAnswer {
                val coords = it.arguments[1] as MotionEvent.PointerCoords
                coords.x = (position.x * fakeDensity).toFloat()
                coords.y = (position.y * fakeDensity).toFloat()
                null
            }.whenever(mockMotionEvent).getPointerCoords(
                eq(index),
                com.nhaarman.mockitokotlin2.any()
            )
        }

        return mockMotionEvent
    }

    // endregion

    companion object {
        private val FLOAT_MAX_INT_VALUE = Math.pow(2.0, 23.0).toFloat()

        // We need to test with higher threshold values in order to avoid flakiness
        private val TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS: Long =
            TimeUnit.MILLISECONDS.toNanos(100)

        private val TEST_FLUSH_BUFFER_THRESHOLD_NS: Long =
            TEST_MOTION_UPDATE_DELAY_THRESHOLD_NS * 10
    }
}
