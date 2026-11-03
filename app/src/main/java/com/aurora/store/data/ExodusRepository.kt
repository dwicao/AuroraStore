/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data

import android.content.Context
import android.util.Log
import com.aurora.Constants
import com.aurora.extensions.TAG
import com.aurora.store.BuildConfig
import com.aurora.store.data.model.ExodusReport
import com.aurora.store.data.model.ExodusTracker
import com.aurora.store.data.model.Report
import com.aurora.store.data.model.TrackersResponse
import com.aurora.store.data.room.download.DownloadDao
import com.aurora.store.data.room.exodus.TrackerDao
import com.aurora.store.data.room.exodus.TrackerEntity
import com.aurora.store.util.DexTrackerScanner
import com.aurora.store.util.PackageUtil
import com.aurora.store.util.PathUtil
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_LAST_TRACKER_SYNC
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
class ExodusRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val trackerDao: TrackerDao,
    private val downloadDao: DownloadDao,
    @ApplicationContext private val context: Context
) {

    companion object {
        private val SYNC_INTERVAL_MS = TimeUnit.DAYS.toMillis(3)
        private const val CATEGORY_SEPARATOR = "\n"

        // Retries for transient Exodus failures (timeouts, DNS blips, 5xx). 4xx and parse
        // errors are permanent and never retried.
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1000L
    }

    private val authHeaders: Map<String, String>
        get() = mapOf(
            "Content-Type" to Constants.JSON_MIME_TYPE,
            "Accept" to Constants.JSON_MIME_TYPE,
            "Authorization" to "Token ${BuildConfig.EXODUS_API_KEY}"
        )

    private fun get(url: String, headers: Map<String, String>): String {
        val request = Request(url = url.toHttpUrl(), headers = headers.toHeaders())
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Exodus HTTP ${response.code}")
            response.body.string()
        }
    }

    /**
     * Runs [block] with exponential backoff over transient failures. Non-transient
     * errors (4xx, malformed bodies, cancellation) propagate immediately so a broken
     * request fails fast instead of burning retries.
     */
    private suspend fun <T> withRetry(label: String, block: suspend () -> T): T {
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                if (!isRetryable(exception)) throw exception
                lastError = exception
                Log.w(TAG, "Exodus $label failed (attempt ${attempt + 1}/$MAX_RETRIES)", exception)
                delay(RETRY_BASE_DELAY_MS shl attempt)
            }
        }
        throw lastError!!
    }

    private fun isRetryable(exception: Exception): Boolean = when {
        exception is UnknownHostException || exception is SocketTimeoutException -> true
        exception is IOException -> {
            // Transport-level I/O (resets, timeouts) is transient; our own HTTP-status
            // wrapper is only transient for 5xx — a 4xx will fail the same way again.
            val message = exception.message ?: return true
            !message.startsWith("Exodus HTTP") || "Exodus HTTP 5" in message
        }
        else -> false
    }

    /**
     * Reports for [packageName]. Failure (network, HTTP error such as a revoked API
     * token, unparseable body) is a [Result.failure]; an empty list means the fetch
     * worked but Exodus simply has no report for this app. Callers must render those
     * two cases differently instead of lumping both into "failed".
     */
    suspend fun fetchReports(packageName: String): Result<List<Report>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = withRetry("fetchReports") {
                    get("${Constants.EXODUS_SEARCH_URL}$packageName", authHeaders)
                }
                if (response.isBlank()) return@runCatching emptyList()
                parseReports(response, packageName).also {
                    if (it.isEmpty()) {
                        Log.w(TAG, "Exodus returned no usable report for $packageName")
                    }
                }
            }.onFailure { Log.e(TAG, "Failed to fetch exodus reports", it) }
        }

    private fun parseReports(response: String, packageName: String): List<Report> = try {
        val exodusObject = JSONObject(response).getJSONObject(packageName)
        json.decodeFromString<ExodusReport>(exodusObject.toString()).reports
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun syncTrackersIfStale() = withContext(Dispatchers.IO) {
        val lastSync = Preferences.getLong(context, PREFERENCE_LAST_TRACKER_SYNC)
        val isStale = System.currentTimeMillis() - lastSync > SYNC_INTERVAL_MS
        if (trackerDao.count() > 0 && !isStale) return@withContext

        val response = withRetry("syncTrackers") {
            get(Constants.EXODUS_TRACKERS_URL, authHeaders)
        }
        val trackers = json.decodeFromString<TrackersResponse>(response)
            .trackers.values.map {
                TrackerEntity(
                    id = it.id,
                    name = it.name,
                    url = it.website,
                    signature = it.codeSignature,
                    date = it.creationDate,
                    categories = it.categories.joinToString(CATEGORY_SEPARATOR)
                )
            }

        if (trackers.isNotEmpty()) {
            trackerDao.purgeAndInsert(trackers)
            Preferences.putLong(context, PREFERENCE_LAST_TRACKER_SYNC, System.currentTimeMillis())
        }
    }

    suspend fun resolveTrackers(ids: List<Int>): List<ExodusTracker> {
        if (ids.isEmpty()) return emptyList()
        val byId = trackerDao.getByIds(ids).associateBy { it.id }
        return ids.map { id ->
            byId[id]?.let {
                ExodusTracker(
                    id = it.id,
                    name = it.name,
                    url = it.url,
                    signature = it.signature,
                    date = it.date,
                    categories = if (it.categories.isBlank()) {
                        emptyList()
                    } else {
                        it.categories.split(CATEGORY_SEPARATOR)
                    }
                )
            } ?: ExodusTracker(id = id, name = "Tracker #$id")
        }
    }

    suspend fun getNewTrackers(
        packageName: String,
        installedVersionCode: Long
    ): List<ExodusTracker> =
        computeNewTrackers(
            fetchReports(packageName).getOrDefault(emptyList()),
            installedVersionCode
        )

    suspend fun computeNewTrackers(
        reports: List<Report>,
        installedVersionCode: Long
    ): List<ExodusTracker> {
        if (reports.isEmpty()) return emptyList()

        val installedReport = reports.firstOrNull {
            it.versionCode.toLongOrNull() == installedVersionCode
        } ?: return emptyList()

        val newestReport = reports.maxByOrNull { it.versionCode.toLongOrNull() ?: -1L }
            ?: return emptyList()

        val newestVersionCode = newestReport.versionCode.toLongOrNull() ?: return emptyList()
        if (newestVersionCode <= installedVersionCode) return emptyList()

        val newIds = newestReport.trackers.toSet() - installedReport.trackers.toSet()
        return resolveTrackers(newIds.toList())
    }

    /**
     * Scans an on-device APK for this package/version and builds a [Report] shaped
     * exactly like a server one (negative id marks it device-scanned). Preferred
     * sources in order: a downloaded-but-not-yet-installed copy of this exact
     * version, then the installed copy when its version matches. Returns null when
     * no matching APK is on disk or no tracker definition matched anything usable.
     *
     * This is the offline fallback for the token-gated reports endpoint: no network,
     * no account, and nothing identifying leaves the device.
     */
    suspend fun scanLocalReport(
        packageName: String,
        versionCode: Long,
        versionName: String
    ): Report? = withContext(Dispatchers.IO) {
        val files = localApkFiles(packageName, versionCode)
        if (files.isEmpty()) return@withContext null

        val key = ScanKey(
            packageName,
            versionCode,
            files.joinToString("|") { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        )
        val cached = localScanCache[key]
        val ids = cached ?: run {
            val signatures = trackerDao.getAll()
                .associate { it.id to it.signature }
                .filterValues { it.isNotBlank() }
            if (signatures.isEmpty()) return@withContext null
            val found = files.flatMapTo(mutableSetOf()) { DexTrackerScanner.scanApk(it, signatures) }
            if (localScanCache.size >= MAX_CACHED_SCANS) localScanCache.clear()
            localScanCache[key] = found
            found
        }

        Report(
            id = LOCAL_SCAN_REPORT_ID,
            version = versionName,
            versionCode = versionCode.toString(),
            trackers = ids.toList()
        )
    }

    private fun localApkFiles(packageName: String, versionCode: Long): List<File> {
        // A downloaded copy of this exact version wins: it is the build the user is
        // about to install, while the installed copy may be older.
        val download = runCatching { downloadDao.getDownload(packageName) }.getOrNull()
        if (download != null && download.versionCode == versionCode) {
            val downloaded = (download.fileList + download.sharedLibs.flatMap { it.fileList })
                .map { PathUtil.getLocalFile(context, it, download) }
                .filter { it.isFile }
            if (downloaded.isNotEmpty()) return downloaded
        }
        // Otherwise the installed copy, but only when it really is this version —
        // scanning the old APK and presenting it as the new build would lie.
        if (PackageUtil.isInstalled(context, packageName) &&
            PackageUtil.getInstalledVersionCode(context, packageName) == versionCode
        ) {
            val info = runCatching {
                PackageUtil.getPackageInfo(context, packageName)
            }.getOrNull()
            val paths = buildList {
                info?.applicationInfo?.sourceDir?.let { add(it) }
                info?.applicationInfo?.splitSourceDirs?.filterNotNull()?.let { addAll(it) }
            }
            return paths.map(::File).filter { it.isFile }
        }
        return emptyList()
    }

    companion object {
        /** Sentinel id for device-scanned reports (server ids are >= 1, -1 is progress). */
        const val LOCAL_SCAN_REPORT_ID = -2

        private const val MAX_CACHED_SCANS = 64
    }

    private data class ScanKey(
        val packageName: String,
        val versionCode: Long,
        val filesFingerprint: String
    )

    private val localScanCache = ConcurrentHashMap<ScanKey, Set<Int>>()
}
