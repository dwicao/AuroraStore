/*
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.extensions

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment

fun Context.toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) = runOnUiThread {
    Toast.makeText(this, resId, duration).apply { show() }
}

fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) = runOnUiThread {
    Toast.makeText(this, text, duration).apply { show() }
}

fun Fragment.toast(resId: Int, duration: Int = Toast.LENGTH_SHORT) =
    requireContext().toast(resId, duration)

fun Fragment.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) =
    requireContext().toast(text, duration)
