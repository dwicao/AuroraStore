/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-FileCopyrightText: 2025 The Calyx Institute
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.viewmodel.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.model.Review
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

@HiltViewModel(assistedFactory = ReviewViewModel.Factory::class)
class ReviewViewModel @AssistedInject constructor(
    @Assisted private val packageName: String,
    private val playClient: PlayClient,
    private val playGateway: PlayGateway
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(packageName: String): ReviewViewModel
    }

    private val _reviews = MutableStateFlow<PagingData<Review>>(PagingData.Companion.empty())
    val reviews = _reviews.asStateFlow()

    init {
        fetchReviews()
    }

    fun fetchReviews(filter: Review.Filter = Review.Filter.ALL) {
        var reviewsNextPageUrl: String? = null

        manualPager { page ->
            // Routed through the gateway: a rejected session redirects to Splash like
            // every other Play call, and any other failure yields an empty page instead
            // of killing the paging flow (which left the list permanently empty).
            val items = playGateway.callOrDefault("reviews", emptyList(), packageName) {
                when (page) {
                    1 -> playClient.reviews.reviews(packageName, filter).also {
                        reviewsNextPageUrl = it.nextPageUrl
                    }.reviews

                    else -> {
                        if (!reviewsNextPageUrl.isNullOrBlank()) {
                            playClient.reviews.next(reviewsNextPageUrl!!).also {
                                reviewsNextPageUrl = it.nextPageUrl
                            }.reviews
                        } else {
                            emptyList()
                        }
                    }
                }
            }
            PageResult(items)
        }.flow.distinctUntilChanged()
            .cachedIn(viewModelScope)
            .onEach { _reviews.value = it }
            .launchIn(viewModelScope)
    }
}
