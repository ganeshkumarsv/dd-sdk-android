/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.recorder

import android.app.Activity
import android.view.ViewTreeObserver
import com.datadog.android.sessionreplay.processor.Processor
import com.datadog.android.sessionreplay.utils.TimeProvider

internal class ScreenRecorder(
    private val processor: Processor,
    private val snapshotProducer: SnapshotProducer,
    private val timeProvider: TimeProvider
) : Recorder {
    internal val drawListeners: MutableMap<Int, ViewTreeObserver.OnDrawListener> = HashMap()

    override fun startRecording(activity: Activity) {
        if (activity.window != null) {
            with(
                RecorderOnDrawListener(
                    activity,
                    activity.resources.displayMetrics.density,
                    processor,
                    snapshotProducer
                )
            ) {
                drawListeners[activity.hashCode()] = this
                activity.window.decorView.viewTreeObserver?.addOnDrawListener(this)
            }
            activity.window.callback = RecorderWindowCallback(
                processor,
                activity.resources.displayMetrics.density,
                activity.window.callback,
                timeProvider
            )
        }
    }

    override fun stopRecording(activity: Activity) {
        activity.hashCode().let { windowHashCode ->
            drawListeners.remove(windowHashCode)?.let {
                activity.window.decorView.viewTreeObserver.removeOnDrawListener(it)
            }
            activity.window.callback =
                (activity.window.callback as? RecorderWindowCallback)?.wrappedCallback
        }
    }
}
