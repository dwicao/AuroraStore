/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.compose.composable

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aurora.store.R

/**
 * The standard back IconButton used across the app: a back glyph whose accessible name is
 * [R.string.action_back] and whose press routes through the host activity's back dispatcher,
 * mirroring the system Back behaviour.
 */
@Composable
fun ArrowBackIconButton(
    modifier: Modifier = Modifier,
    painter: Painter = painterResource(R.drawable.ic_arrow_back)
) {
    val activity = LocalActivity.current as? ComponentActivity
    IconButton(
        onClick = { activity?.onBackPressedDispatcher?.onBackPressed() },
        modifier = modifier
    ) {
        Icon(
            painter = painter,
            contentDescription = stringResource(R.string.action_back)
        )
    }
}
