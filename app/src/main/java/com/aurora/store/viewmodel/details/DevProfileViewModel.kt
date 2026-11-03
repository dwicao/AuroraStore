/*
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.details

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.extensions.TAG
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.model.DeveloperStream
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class DevProfileViewModel @Inject constructor(
    private val playClient: PlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    val liveData: MutableLiveData<ViewState> = MutableLiveData()
    var devStream: DeveloperStream = DeveloperStream.EMPTY
    var streamBundle: StreamBundle = StreamBundle.EMPTY

    fun getStreamBundle(devId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playGateway.call("dev-stream") { playClient.apps.developerStream(devId) }
                .onSuccess {
                    devStream = it
                    streamBundle = it.streamBundle
                    liveData.postValue(ViewState.Success(devStream))
                }
                .onFailure {
                    // Auth failures already redirect to Splash via the gateway.
                    if (!PlayErrors.isAuthFailure(it)) {
                        liveData.postValue(ViewState.Error(it.message))
                    }
                }
        }
    }

    fun observeCluster(cluster: StreamCluster) {
        val cursor = cluster.next
        if (cursor == null) {
            Log.i(TAG, "End of cluster")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            playGateway.call("dev-stream-next") { playClient.streams.next(cursor) }
                .onSuccess {
                    updateCluster(cluster, it)
                    devStream = devStream.copy(streamBundle = streamBundle)
                    liveData.postValue(ViewState.Success(devStream))
                }
                .onFailure {
                    if (!PlayErrors.isAuthFailure(it)) {
                        liveData.postValue(ViewState.Error(it.message))
                    }
                }
        }
    }

    private fun updateCluster(oldCluster: StreamCluster, newCluster: StreamCluster) {
        val mergedCluster = oldCluster.copy(
            apps = oldCluster.apps + newCluster.apps,
            next = newCluster.next
        )

        streamBundle = streamBundle.copy(
            clusters = streamBundle.clusters.map {
                if (it.id ==
                    oldCluster.id
                ) {
                    mergedCluster
                } else {
                    it
                }
            }
        )
    }
}
