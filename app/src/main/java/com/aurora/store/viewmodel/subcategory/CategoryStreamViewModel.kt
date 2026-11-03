/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2024 Aurora OSS
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.subcategory

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.extensions.TAG
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import com.aurora.gplayapi.web.WebPlayClient
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = CategoryStreamViewModel.Factory::class)
class CategoryStreamViewModel @AssistedInject constructor(
    @Assisted val browseUrl: String,
    private val webPlayClient: WebPlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(browseUrl: String): CategoryStreamViewModel
    }

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Loading)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private var streamBundle = StreamBundle.EMPTY

    init {
        fetch()
    }

    fun fetch() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!streamBundle.hasClusters() || streamBundle.hasNext()) {
                playGateway.call("category-stream") {
                    if (streamBundle.hasClusters()) {
                        webPlayClient.streams.next(streamBundle.next!!)
                    } else {
                        webPlayClient.categories.stream(browseUrl)
                    }
                }.onSuccess { newBundle ->
                    streamBundle = streamBundle.copy(
                        clusters = streamBundle.clusters + newBundle.clusters,
                        next = newBundle.next
                    )

                    _viewState.value = ViewState.Success(streamBundle)
                }.onFailure {
                    // Auth failures already redirect to Splash via the gateway.
                    if (!PlayErrors.isAuthFailure(it)) {
                        _viewState.value = ViewState.Error(it.message)
                    }
                }
            } else {
                Log.i(TAG, "End of Bundle")
            }
        }
    }

    fun fetchNextCluster(streamCluster: StreamCluster) {
        viewModelScope.launch(Dispatchers.IO) {
            if (streamCluster.hasNext()) {
                playGateway.call("category-stream-next") {
                    webPlayClient.streams.next(streamCluster.next!!)
                }.onSuccess { newCluster ->
                    val mergedCluster = streamCluster.copy(
                        apps = streamCluster.apps + newCluster.apps,
                        next = newCluster.next
                    )

                    streamBundle = streamBundle.copy(
                        clusters = streamBundle.clusters.map {
                            if (it.id == streamCluster.id) mergedCluster else it
                        }
                    )
                    _viewState.value = ViewState.Success(streamBundle)
                }.onFailure {
                    if (!PlayErrors.isAuthFailure(it)) {
                        Log.e(TAG, "Failed to fetch next cluster", it)
                    }
                }
            } else {
                Log.i(TAG, "End of cluster")
            }
        }
    }
}
