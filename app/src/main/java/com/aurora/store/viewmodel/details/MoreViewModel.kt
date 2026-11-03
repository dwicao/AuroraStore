/*
 * SPDX-FileCopyrightText: 2023-2025 The Calyx Institute
 * SPDX-FileCopyrightText: 2024 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.details

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.extensions.TAG
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.model.App
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = MoreViewModel.Factory::class)
class MoreViewModel @AssistedInject constructor(
    @Assisted private val dependencies: List<String>,
    private val playClient: PlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(dependencies: List<String>): MoreViewModel
    }

    private val _dependentApps = MutableStateFlow<List<App>?>(emptyList())
    val dependentApps = _dependentApps.asStateFlow()

    init {
        fetchDependencies()
    }

    private fun fetchDependencies() {
        viewModelScope.launch(Dispatchers.IO) {
            playGateway.call("dependencies") {
                playClient.apps.details(dependencies)
            }.onSuccess {
                _dependentApps.value = it
            }.onFailure {
                // Auth failures already redirect to Splash via the gateway.
                if (!PlayErrors.isAuthFailure(it)) {
                    Log.e(TAG, "Failed to fetch dependencies", it)
                    _dependentApps.value = null
                }
            }
        }
    }
}
