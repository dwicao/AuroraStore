/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2024 Aurora OSS
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.ClusterCursor
import com.aurora.store.data.PageResult
import com.aurora.store.data.paging.GenericPagingSource.Companion.manualPager
import com.aurora.store.data.play.PlayGateway
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@HiltViewModel(assistedFactory = ExpandedStreamBrowseViewModel.Factory::class)
class ExpandedStreamBrowseViewModel @AssistedInject constructor(
    @Assisted val browseUrl: String,
    private val playClient: PlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(browseUrl: String): ExpandedStreamBrowseViewModel
    }

    private val _title = MutableStateFlow("")
    val title = _title.asStateFlow()

    private val _apps = MutableStateFlow<PagingData<App>>(PagingData.empty())
    val apps = _apps.asStateFlow()

    init {
        fetchApps()
    }

    private fun fetchApps() {
        var cursor: ClusterCursor? = null

        manualPager { page ->
            // Routed through the gateway: an auth rejection redirects to Splash, and
            // any other failure yields an empty page instead of killing the flow.
            val items = playGateway.callOrDefault("expanded-stream", emptyList()) {
                when (page) {
                    1 -> {
                        val cluster = playClient.streams.expandedBrowse(browseUrl)
                        _title.value = cluster.title
                        cursor = cluster.next
                        cluster.apps
                    }

                    else -> {
                        val nextCursor = cursor
                        if (nextCursor != null) {
                            val cluster = playClient.streams.next(nextCursor)
                            cursor = cluster.next
                            cluster.apps
                        } else {
                            emptyList()
                        }
                    }
                }
            }
            PageResult(items)
        }.flow.distinctUntilChanged()
            .cachedIn(viewModelScope)
            .onEach { _apps.value = it }
            .launchIn(viewModelScope)
    }
}
