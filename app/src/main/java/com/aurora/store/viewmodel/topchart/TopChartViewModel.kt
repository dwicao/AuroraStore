/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.topchart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.gplayapi.api.TopChartType
import com.aurora.gplayapi.model.ContentType
import com.aurora.gplayapi.model.StreamCluster
import com.aurora.gplayapi.web.WebPlayClient
import com.aurora.store.TopChartStash
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `WebChartApi`/[com.aurora.gplayapi.api.ChartApi] has no continuation for a chart cluster
 * - v3's own web helper had none either (`getNextStreamCluster` always answered
 * `StreamCluster.EMPTY`, "irrelevant for web client as it doesn't support pagination", per
 * its own comment), so unlike v3's Aurora view model this has no `nextCluster`: it would
 * never have had anything to fetch.
 */
@HiltViewModel
class TopChartViewModel @Inject constructor(
    private val webPlayClient: WebPlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    private var stash: TopChartStash = mutableMapOf()

    private val _state = MutableStateFlow<ViewState>(ViewState.Loading)
    val state: StateFlow<ViewState> = _state.asStateFlow()

    fun getStreamCluster(type: ContentType, chart: TopChartType) {
        viewModelScope.launch(Dispatchers.IO) {
            if (targetCluster(type, chart).apps.isNotEmpty()) {
                _state.value = ViewState.Success(targetCluster(type, chart))
                return@launch
            }

            _state.value = ViewState.Loading

            playGateway.call("top-chart") { webPlayClient.charts.cluster(type.value, chart) }
                .onSuccess {
                    stash.getOrPut(type) { mutableMapOf() }[chart] = it
                    _state.value = ViewState.Success(targetCluster(type, chart))
                }
                .onFailure {
                    // Auth failures already redirect to Splash via the gateway.
                    if (!PlayErrors.isAuthFailure(it)) {
                        _state.value = ViewState.Error(it.message)
                    }
                }
        }
    }

    private fun targetCluster(type: ContentType, chart: TopChartType): StreamCluster =
        stash.getOrPut(type) { mutableMapOf() }.getOrPut(chart) { StreamCluster.EMPTY }
}
