/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.homestream

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.extensions.TAG
import com.aurora.gplayapi.model.ContentType
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import com.aurora.gplayapi.web.WebPlayClient
import com.aurora.store.HomeStash
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `WebStreamApi.categoryStream` is named for what the RPC does rather than for a
 * `StreamType` (spec/task-7 finding: v3's own `WebStreamHelper.fetch(type, category)`
 * ignored `type` entirely and always answered the same home-stream content), so unlike
 * v3's `fetch(type, category)` this only ever takes [ContentType].
 */
@HiltViewModel
class StreamViewModel @Inject constructor(
    private val webPlayClient: WebPlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    val liveData: MutableLiveData<ViewState> = MutableLiveData()

    private val stash: HomeStash = mutableMapOf()

    // Mutex to protect stash access for thread safety
    private val stashMutex = Mutex()

    fun getStreamBundle(category: ContentType) {
        liveData.postValue(ViewState.Loading)
        observe(category)
    }

    fun observe(category: ContentType) {
        viewModelScope.launch(Dispatchers.IO) {
            stashMutex.withLock {
                val bundle = targetBundle(category)

                // Post existing data if any clusters exist
                if (bundle.hasClusters()) {
                    liveData.postValue(ViewState.Success(stash.toMap()))
                }

                if (!bundle.hasClusters() || bundle.hasNext()) {
                    // Fetch new stream bundle
                    playGateway.call("home-stream") {
                        if (bundle.hasClusters()) {
                            webPlayClient.streams.next(bundle.next!!)
                        } else {
                            webPlayClient.streams.categoryStream(category)
                        }
                    }.onSuccess { newBundle ->
                        val mergedBundle = bundle.copy(
                            clusters = bundle.clusters + newBundle.clusters,
                            next = newBundle.next
                        )
                        stash[category] = mergedBundle

                        // Post updated to UI
                        liveData.postValue(ViewState.Success(stash.toMap()))
                    }.onFailure {
                        // Auth failures already redirect to Splash via the gateway.
                        if (!PlayErrors.isAuthFailure(it)) {
                            liveData.postValue(ViewState.Error(it.message))
                        }
                    }
                } else {
                    Log.i(TAG, "End of Bundle")
                }
            }
        }
    }

    fun observeCluster(category: ContentType, streamCluster: StreamCluster) {
        viewModelScope.launch(Dispatchers.IO) {
            if (streamCluster.hasNext()) {
                playGateway.call("home-stream-next") {
                    webPlayClient.streams.next(streamCluster.next!!)
                }.onSuccess { newCluster ->
                    stashMutex.withLock {
                        updateCluster(category, streamCluster.id, newCluster)
                    }

                    liveData.postValue(ViewState.Success(stash.toMap()))
                }.onFailure {
                    if (!PlayErrors.isAuthFailure(it)) {
                        liveData.postValue(ViewState.Error(it.message))
                    }
                }
            } else {
                stashMutex.withLock {
                    postClusterEnd(category, streamCluster.id)
                }

                liveData.postValue(ViewState.Success(stash.toMap()))
            }
        }
    }

    private fun updateCluster(category: ContentType, clusterId: String, newCluster: StreamCluster) {
        val bundle = stash[category] ?: return
        val oldCluster = bundle.clusters.firstOrNull { it.id == clusterId } ?: return

        val mergedCluster = oldCluster.copy(
            apps = (oldCluster.apps + newCluster.apps).distinctBy { it.packageName },
            next = newCluster.next
        )

        stash[category] = bundle.copy(
            clusters = bundle.clusters.map { if (it.id == clusterId) mergedCluster else it }
        )
    }

    private fun postClusterEnd(category: ContentType, clusterId: String) {
        val bundle = stash[category] ?: return
        val oldCluster = bundle.clusters.firstOrNull { it.id == clusterId } ?: return

        val updatedCluster = oldCluster.copy(next = null)
        stash[category] = bundle.copy(
            clusters = bundle.clusters.map { if (it.id == clusterId) updatedCluster else it }
        )
    }

    private fun targetBundle(category: ContentType): StreamBundle =
        stash.getOrPut(category) { StreamBundle(id = category.value) }
}
