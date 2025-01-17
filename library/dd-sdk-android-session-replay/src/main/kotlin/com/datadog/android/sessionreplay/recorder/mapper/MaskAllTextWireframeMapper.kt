/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */

package com.datadog.android.sessionreplay.recorder.mapper

import android.widget.TextView

internal class MaskAllTextWireframeMapper(
    viewWireframeMapper: ViewWireframeMapper = ViewWireframeMapper(),
    private val stringObfuscator: StringObfuscator = StringObfuscator()
) : TextWireframeMapper(viewWireframeMapper) {

    override fun resolveTextValue(textView: TextView): String {
        return stringObfuscator.obfuscate(textView.text.toString())
    }
}
