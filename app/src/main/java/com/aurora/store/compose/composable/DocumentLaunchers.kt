/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.compose.composable

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Launcher for ACTION_OPEN_DOCUMENT. The callback receives the picked [Uri], or `null` when the
 * user dismissed the picker. Shared so every import/export screen wires the contract the same way.
 */
@Composable
fun rememberOpenDocumentLauncher(onResult: (Uri?) -> Unit): ActivityResultLauncher<Array<String>> =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onResult
    )

/**
 * Launcher for ACTION_CREATE_DOCUMENT with the given MIME type. [mimeType] is fixed per screen
 * (JSON / properties / ZIP exports). The callback receives the created [Uri], or `null` when the
 * user dismissed the picker.
 */
@Composable
fun rememberCreateDocumentLauncher(
    mimeType: String,
    onResult: (Uri?) -> Unit
): ActivityResultLauncher<String> = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument(mimeType),
    onResult = onResult
)
