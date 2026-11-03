/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2021 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.category

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.extensions.TAG
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.model.Category
import com.aurora.gplayapi.model.ContentType
import com.aurora.store.CategoryStash
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.play.PlayErrors
import com.aurora.store.data.play.PlayGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val playClient: PlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    private var stash: CategoryStash = mutableMapOf(
        ContentType.APPLICATION to emptyList(),
        ContentType.GAME to emptyList()
    )

    val liveData = MutableLiveData<ViewState>()

    fun getCategoryList(type: ContentType) {
        viewModelScope.launch(Dispatchers.IO) {
            val categories = getCategories(type)

            if (categories.isNotEmpty()) {
                liveData.postValue(ViewState.Success(stash))
                return@launch
            }

            liveData.postValue(ViewState.Loading)

            playGateway.call("categories") { playClient.categories.all(type) }
                .onSuccess {
                    stash[type] = it
                    liveData.postValue(ViewState.Success(stash))
                }
                .onFailure {
                    // Auth failures already redirect to Splash via the gateway.
                    if (!PlayErrors.isAuthFailure(it)) {
                        Log.e(TAG, "Failed fetching list of categories", it)
                        liveData.postValue(ViewState.Error(it.message))
                    }
                }
        }
    }

    private fun getCategories(type: ContentType): List<Category> = stash.getOrPut(type) {
        mutableListOf()
    }
}
