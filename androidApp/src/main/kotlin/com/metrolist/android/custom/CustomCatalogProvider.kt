package com.metrolist.android.custom

import android.content.Context
import android.util.Log
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.screens.CustomHomeShelvesBridge
import com.metrolist.shared.ui.screens.CustomSearchBridge
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfHeaderAction
import com.metrolist.shared.ui.screens.HomeShelfStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ExternalCatalogTrack(
    val source: String,
    val externalId: String,
    val artist: String,
    val title: String,
    val durationSec: Long? = null,
    val coverUrl: String? = null,
    val isExplicit: Boolean = false,
    val releaseYear: Int? = null,
    val releaseDateMs: Long? = null,
) {
    fun toSongItem(): SongItem {
        val parsedArtists =
            CustomConfigStore
                .splitArtistNames(artist)
                .map { ArtistTiny(id = null, name = it) }
                .ifEmpty {
                    artist
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let { listOf(ArtistTiny(id = null, name = it)) }
                        ?: listOf(ArtistTiny(id = null, name = "Неизвестный"))
                }
        val safeId =
            externalId.ifBlank {
                "${source}_${TrackMatcher.normalize(artist)}_${TrackMatcher.normalize(title)}".take(64)
            }
        val effectiveCover = coverUrl?.takeIf { it.isNotBlank() } ?: CustomMascotAssets.fallbackCoverUri
        return SongItem(
            id = safeId,
            title = title,
            thumbnail = effectiveCover,
            artists = parsedArtists,
            album = null,
            duration = durationSec,
            isExplicit = isExplicit,
        )
    }
}

data class HomeCatalogSnapshot(
    val yandexChart: List<ExternalCatalogTrack> = emptyList(),
    val yandexNewReleases: List<ExternalCatalogTrack> = emptyList(),
    val yandexDailyTitle: String = "Плейлист дня",
    val yandexDailyTracks: List<ExternalCatalogTrack> = emptyList(),
    val yandexPremiereTitle: String = "Премьера",
    val yandexPremiereTracks: List<ExternalCatalogTrack> = emptyList(),
    val vkChart: List<ExternalCatalogTrack> = emptyList(),
    val vkPopular: List<ExternalCatalogTrack> = emptyList(),
    val vkMyMusic: List<ExternalCatalogTrack> = emptyList(),
    val deezerChart: List<ExternalCatalogTrack> = emptyList(),
    val releaseRadar: List<ExternalCatalogTrack> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    val isNotEmpty: Boolean
        get() =
            yandexChart.isNotEmpty() ||
                yandexNewReleases.isNotEmpty() ||
                yandexDailyTracks.isNotEmpty() ||
                yandexPremiereTracks.isNotEmpty() ||
                vkChart.isNotEmpty() ||
                vkPopular.isNotEmpty() ||
                vkMyMusic.isNotEmpty() ||
                deezerChart.isNotEmpty() ||
                releaseRadar.isNotEmpty()
}

data class LibraryImportProgress(
    val message: String,
    val fetchedCount: Int = 0,
    val syncedToLibrary: Int = 0,
    val targetToSync: Int = 0,
)

data class LibraryImportSummary(
    val sourceLabel: String,
    val totalFetched: Int,
    val savedForWave: Int,
    val addedToLibrary: Int,
)

class CustomCatalogProvider(
    private val configStore: CustomConfigStore,
) {
    private fun deezerClient(): DeezerReferenceClient =
        HybridStreamResolver.getDeezerClient() ?: fallbackDeezerClient

    suspend fun loadHomeSnapshot(): HomeCatalogSnapshot =
        coroutineScope {
            val yandexChartTask = async { fetchYandexChart(24) }
            val yandexNewTask = async { fetchYandexNewReleases(24) }
            val yandexPersonalTask = async { fetchYandexPersonalMixes() }
            val vkBundleTask = async { fetchVkHomeBundle() }
            val deezerChartTask = async { deezerClient().fetchChart(24) }

            val yandexNew = yandexNewTask.await().distinctBy { it.externalId }
            val radarTask = async { fetchReleaseRadar(limit = 20, preloadedYandexNew = yandexNew) }

            val (dailyPair, premierePair) = yandexPersonalTask.await()
            val (vkRecs, vkPop, vkMine) = vkBundleTask.await()

            HomeCatalogSnapshot(
                yandexChart = yandexChartTask.await().distinctBy { it.externalId },
                yandexNewReleases = yandexNew,
                yandexDailyTitle = dailyPair.first,
                yandexDailyTracks = dailyPair.second.distinctBy { it.externalId },
                yandexPremiereTitle = premierePair.first,
                yandexPremiereTracks = premierePair.second.distinctBy { it.externalId },
                vkChart = vkRecs.distinctBy { it.externalId },
                vkPopular = vkPop.distinctBy { it.externalId },
                vkMyMusic = vkMine.distinctBy { it.externalId },
                deezerChart = deezerChartTask.await().distinctBy { it.externalId },
                releaseRadar =
                    radarTask
                        .await()
                        .distinctBy { CustomConfigStore.buildTrackKey(it.artist, it.title).ifBlank { it.externalId } },
                updatedAtMs = System.currentTimeMillis(),
            )
        }

    suspend fun loadHomeShelves(): List<HomeShelf> {
        val snapshot = loadHomeSnapshot()
        if (snapshot.isNotEmpty) {
            _homeSnapshot.value = snapshot
            saveSnapshotToDisk(snapshot)
        }
        return buildHomeShelvesFromSnapshot(
            if (snapshot.isNotEmpty) snapshot else _homeSnapshot.value,
        )
    }

    suspend fun fetchReleaseRadar(
        limit: Int = 20,
        preloadedYandexNew: List<ExternalCatalogTrack>? = null,
        maxAgeDays: Int = 90,
    ): List<ExternalCatalogTrack> =
        coroutineScope {
            val topArtists = configStore.getLocalTopArtists(limit = 16)
            val yandexNewTask = async { preloadedYandexNew ?: fetchYandexNewReleases(28) }

            val deezerRadar = mutableListOf<ExternalCatalogTrack>()
            val deezer = deezerClient()
            for (chunk in topArtists.take(12).chunked(4)) {
                val found =
                    chunk
                        .map { artistName ->
                            async(Dispatchers.IO) {
                                deezer.fetchArtistRecentReleases(
                                    artistName = artistName,
                                    maxAgeDays = maxAgeDays,
                                    limit = 2,
                                )
                            }
                        }.awaitAll()
                        .flatten()
                deezerRadar.addAll(found)
            }

            val yandexNew = yandexNewTask.await()
            val normTopArtists = topArtists.mapTo(HashSet()) { CustomConfigStore.buildArtistKey(it) }
            val matchedYandex =
                yandexNew.filter { track ->
                    CustomConfigStore.splitArtistNames(track.artist).any { single ->
                        val norm = CustomConfigStore.buildArtistKey(single)
                        norm.isNotBlank() && norm in normTopArtists
                    }
                }
            val otherYandex = yandexNew.filterNot { it in matchedYandex }

            val seen = HashSet<String>()
            val combined = ArrayList<ExternalCatalogTrack>(limit)

            fun addUnique(items: List<ExternalCatalogTrack>) {
                for (item in items) {
                    if (combined.size >= limit) break
                    val key = CustomConfigStore.buildTrackKey(item.artist, item.title).ifBlank { item.externalId }
                    if (key.isNotBlank() && seen.add(key)) {
                        combined.add(item)
                    }
                }
            }

            addUnique(deezerRadar)
            addUnique(matchedYandex)
            if (combined.size < limit) {
                addUnique(otherYandex)
            }
            combined
        }

    private suspend fun fetchYandexPersonalMixes(): Pair<Pair<String, List<ExternalCatalogTrack>>, Pair<String, List<ExternalCatalogTrack>>> =
        withContext(Dispatchers.IO) {
            val emptyResult = ("Плейлист дня" to emptyList<ExternalCatalogTrack>()) to ("Премьера" to emptyList<ExternalCatalogTrack>())
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            if (token.isBlank()) return@withContext emptyResult

            val headers =
                mapOf(
                    "Authorization" to "OAuth $token",
                    "User-Agent" to YANDEX_UA,
                    "Accept-Language" to "ru",
                )
            try {
                val resp =
                    CustomHttp.get(
                        url = "${yandexApiBase()}/landing3?blocks=personalplaylists",
                        headers = headers,
                        timeoutMs = 10_000,
                    )
                if (!resp.isSuccess) return@withContext emptyResult
                val blocks = JSONObject(resp.body).optJSONObject("result")?.optJSONArray("blocks") ?: return@withContext emptyResult

                data class PlaylistRef(
                    val uid: String,
                    val kind: String,
                    val title: String,
                    val genType: String,
                )

                val refs = mutableListOf<PlaylistRef>()
                for (b in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(b) ?: continue
                    val entities = block.optJSONArray("entities") ?: continue
                    for (e in 0 until entities.length()) {
                        val ent = entities.optJSONObject(e) ?: continue
                        val data = ent.optJSONObject("data")?.optJSONObject("data") ?: ent.optJSONObject("data") ?: continue
                        val uid = data.optString("uid").trim()
                        val kind = data.optString("kind").trim()
                        val title = data.optString("title").trim()
                        val genType = data.optString("generatedPlaylistType").trim().lowercase()
                        if (uid.isNotBlank() && kind.isNotBlank() && title.isNotBlank()) {
                            refs.add(PlaylistRef(uid, kind, title, genType))
                        }
                    }
                }
                if (refs.isEmpty()) return@withContext emptyResult

                val dailyRef =
                    refs.firstOrNull { it.genType.contains("day") || it.title.contains("дня", ignoreCase = true) }
                        ?: refs.first()
                val premiereRef =
                    refs.firstOrNull {
                        it != dailyRef &&
                            (
                                it.genType.contains("recent") ||
                                    it.genType.contains("premiere") ||
                                    it.genType.contains("new") ||
                                    it.title.contains("премьер", ignoreCase = true) ||
                                    it.title.contains("новин", ignoreCase = true)
                            )
                    } ?: refs.firstOrNull { it != dailyRef }

                suspend fun loadPlaylistTracks(ref: PlaylistRef?): Pair<String, List<ExternalCatalogTrack>> {
                    if (ref == null) return "" to emptyList()
                    val plResp =
                        CustomHttp.get(
                            url = "${yandexApiBase()}/users/${ref.uid}/playlists/${ref.kind}",
                            headers = headers,
                            timeoutMs = 10_000,
                        )
                    if (!plResp.isSuccess) return ref.title to emptyList()
                    val tracksArr =
                        JSONObject(plResp.body)
                            .optJSONObject("result")
                            ?.optJSONArray("tracks")
                            ?: return ref.title to emptyList()
                    val parsed =
                        buildList {
                            for (i in 0 until minOf(tracksArr.length(), 24)) {
                                val trackObj = tracksArr.optJSONObject(i)?.optJSONObject("track") ?: continue
                                parseYandexTrack(trackObj)?.let(::add)
                            }
                        }
                    return ref.title to parsed
                }

                coroutineScope {
                    val dailyTask = async { loadPlaylistTracks(dailyRef) }
                    val premTask = async { loadPlaylistTracks(premiereRef) }
                    val d = dailyTask.await()
                    val p = premTask.await()
                    (d.first.ifBlank { "Плейлист дня" } to d.second) to
                        (p.first.ifBlank { "Премьера" } to p.second)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyResult
            }
        }

    /**
     * Загружает 3 отдельные полки для вкладки VK через веб-сессию (`web.api.vk.ru`, `6287487`).
     */
    private suspend fun fetchVkHomeBundle(): Triple<List<ExternalCatalogTrack>, List<ExternalCatalogTrack>, List<ExternalCatalogTrack>> =
        withContext(Dispatchers.IO) {
            val rawStored = configStore.current.vkToken
            var token = VkWebSession.resolveAccessToken(rawStored) ?: return@withContext Triple(emptyList(), emptyList(), emptyList())

            suspend fun callVk(
                calls: List<Triple<String, String, Map<String, String>>>,
                allowRefresh: Boolean = true,
            ): List<ExternalCatalogTrack> {
                for ((host, method, params) in calls) {
                    val ver = if (method.startsWith("catalog.")) "5.236" else "5.131"
                    val res =
                        try {
                            val resp =
                                VkWebSession.postApiMethod(
                                    host = host,
                                    method = method,
                                    apiVersion = ver,
                                    params = params,
                                    accessToken = token,
                                    timeoutMs = 10_000,
                                )
                            if (!resp.isSuccess) {
                                null
                            } else {
                                val root = JSONObject(resp.body)
                                if (root.has("error")) {
                                    val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                                    if (code == 5 && allowRefresh) {
                                        VkWebSession.invalidateCachedToken()
                                        val fresh = VkWebSession.resolveAccessToken(rawStored, forceRefresh = true)
                                        if (!fresh.isNullOrBlank()) {
                                            token = fresh
                                            return callVk(calls, allowRefresh = false)
                                        }
                                    }
                                    null
                                } else {
                                    val respObj = root.optJSONObject("response")
                                    val arr =
                                        respObj?.optJSONArray("items")
                                            ?: respObj?.optJSONArray("audios")
                                            ?: root.optJSONArray("response")
                                    parseVkAudioArray(arr, rejectUnavailableUrl = true).take(24)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    if (!res.isNullOrEmpty()) return res
                }
                return emptyList()
            }

            val recs =
                callVk(
                    listOf(
                        Triple(WEB_API_RU_HOST, "audio.getRecommendations", mapOf("count" to "24", "shuffle" to "1")),
                        Triple(WEB_API_RU_HOST, "catalog.getAudio", mapOf("need_blocks" to "1")),
                        Triple(API_VK_COM_HOST, "audio.getRecommendations", mapOf("count" to "24", "shuffle" to "1")),
                    ),
                )
            delay(380L)
            val popular =
                callVk(
                    listOf(
                        Triple(WEB_API_RU_HOST, "audio.getPopular", mapOf("count" to "24")),
                        Triple(API_VK_COM_HOST, "audio.getPopular", mapOf("count" to "24")),
                    ),
                )
            delay(380L)
            val myMusic =
                callVk(
                    listOf(
                        Triple(WEB_API_RU_HOST, "audio.get", mapOf("count" to "24", "offset" to "0")),
                        Triple(API_VK_COM_HOST, "audio.get", mapOf("count" to "24", "offset" to "0")),
                    ),
                )
            Triple(recs, popular, myMusic)
        }

    suspend fun searchYandexTracks(
        query: String,
        limit: Int = 10,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            val headers =
                buildMap {
                    put("User-Agent", YANDEX_UA)
                    put("Accept-Language", "ru")
                    if (token.isNotBlank()) put("Authorization", "OAuth $token")
                }
            try {
                val url = "${yandexApiBase()}/search?text=${CustomHttp.urlEncode(clean)}&type=track&page=0"
                val resp = CustomHttp.get(url, headers = headers, timeoutMs = 8_000)
                if (!resp.isSuccess) return@withContext emptyList()
                val arr =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONObject("tracks")
                        ?.optJSONArray("results")
                        ?: return@withContext emptyList()

                buildList {
                    for (i in 0 until minOf(arr.length(), limit)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseYandexTrack(obj)?.toSongItem()?.let(::add)
                    }
                }.distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun searchVkTracks(
        query: String,
        limit: Int = 10,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            if (System.currentTimeMillis() < vkSearchFloodUntilMs) return@withContext emptyList()
            val rawStored = configStore.current.vkToken
            var token = VkWebSession.resolveAccessToken(rawStored) ?: return@withContext emptyList()

            val attempts =
                listOf(
                    Triple(WEB_API_RU_HOST, "catalog.getAudioSearch", mapOf("query" to clean, "need_blocks" to "1", "lang" to "ru")),
                    Triple(WEB_API_RU_HOST, "audio.search", mapOf("q" to clean, "count" to limit.toString(), "sort" to "2", "auto_complete" to "1", "lang" to "ru")),
                    Triple(API_VK_COM_HOST, "audio.search", mapOf("q" to clean, "count" to limit.toString(), "sort" to "2", "auto_complete" to "1", "lang" to "ru")),
                )

            var retriedRefresh = false
            for ((host, method, params) in attempts) {
                val ver = if (method.startsWith("catalog.")) "5.236" else "5.131"
                val found =
                    try {
                        val resp =
                            VkWebSession.postApiMethod(
                                host = host,
                                method = method,
                                apiVersion = ver,
                                params = params,
                                accessToken = token,
                                timeoutMs = 8_000,
                            )
                        if (!resp.isSuccess) {
                            null
                        } else {
                            val root = JSONObject(resp.body)
                            if (root.has("error")) {
                                val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                                if (code == 9) {
                                    vkSearchFloodUntilMs = System.currentTimeMillis() + 3 * 60 * 1_000L
                                    emptyList()
                                } else if (code == 5 && !retriedRefresh) {
                                    retriedRefresh = true
                                    VkWebSession.invalidateCachedToken()
                                    val fresh = VkWebSession.resolveAccessToken(rawStored, forceRefresh = true)
                                    if (!fresh.isNullOrBlank()) {
                                        token = fresh
                                        val retryResp =
                                            VkWebSession.postApiMethod(
                                                host = host,
                                                method = method,
                                                apiVersion = ver,
                                                params = params,
                                                accessToken = token,
                                                timeoutMs = 8_000,
                                            )
                                        val retryRoot = if (retryResp.isSuccess) JSONObject(retryResp.body) else null
                                        val retryObj = retryRoot?.takeUnless { it.has("error") }?.optJSONObject("response")
                                        val retryArr = retryObj?.optJSONArray("audios") ?: retryObj?.optJSONArray("items")
                                        if (retryObj != null) {
                                            parseVkAudioArray(retryArr, rejectUnavailableUrl = true)
                                                .take(limit)
                                                .map { it.toSongItem() }
                                                .distinctBy { it.id }
                                        } else {
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            } else {
                                val respObj = root.optJSONObject("response")
                                if (respObj == null) {
                                    emptyList()
                                } else {
                                    val arr = respObj.optJSONArray("audios") ?: respObj.optJSONArray("items")
                                    parseVkAudioArray(arr, rejectUnavailableUrl = true)
                                        .take(limit)
                                        .map { it.toSongItem() }
                                        .distinctBy { it.id }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                if (found != null) return@withContext found
            }
            emptyList()
        }

    suspend fun importYandexLikes(
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): Result<LibraryImportSummary> =
        try {
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            require(token.isNotBlank()) { "Сначала войди в Яндекс.Музыку или укажи токен" }

            onProgress(LibraryImportProgress("Подключаюсь к Яндекс.Музыке…"))
            val tracks = fetchAllYandexLikedTracks(token) { count ->
                onProgress(LibraryImportProgress("Я.Музыка: получено треков: $count…", fetchedCount = count))
            }
            check(tracks.isNotEmpty()) { "В Яндекс.Музыке не найдено лайкнутых треков" }

            Result.success(
                syncFetchedTracks(
                    sourceLabel = "Я.Музыка",
                    tracks = tracks,
                    syncToLibraryLimit = syncToLibraryLimit,
                    onProgress = onProgress,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun importVkAudios(
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): Result<LibraryImportSummary> =
        try {
            val rawStored = configStore.current.vkToken.trim()
            require(rawStored.isNotBlank()) { "Сначала войди в ВК" }

            onProgress(LibraryImportProgress("Загружаю медиатеку ВК…"))
            val tracks = fetchAllVkAudioTracks(rawStored) { fetched, total ->
                val suffix = if (total > 0) " из $total" else ""
                onProgress(LibraryImportProgress("VK: загружено $fetched$suffix треков…", fetchedCount = fetched))
            }
            check(tracks.isNotEmpty()) { "В ВК не найдено аудиозаписей (или доступ закрыт)" }

            Result.success(
                syncFetchedTracks(
                    sourceLabel = "VK",
                    tracks = tracks,
                    syncToLibraryLimit = syncToLibraryLimit,
                    onProgress = onProgress,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun importYouTubeLikes(
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): Result<LibraryImportSummary> =
        try {
            val fetcher =
                externalYouTubeLikesFetcher
                    ?: error("Сначала подключи аккаунт YouTube Music в настройках")

            onProgress(LibraryImportProgress("Загружаю лайки из YouTube Music…"))
            val tracks = fetcher()
            check(tracks.isNotEmpty()) { "В YouTube Music не найдено лайкнутых треков (или нет входа в аккаунт)" }

            Result.success(
                syncFetchedTracks(
                    sourceLabel = "YouTube",
                    tracks = tracks,
                    syncToLibraryLimit = syncToLibraryLimit,
                    onProgress = onProgress,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun scanExternalTasteProfile(): Int =
        coroutineScope {
            val yandexToken = sanitizeYandexToken(configStore.current.yandexToken)
            val vkStored = configStore.current.vkToken.trim()

            val ymTask =
                async(Dispatchers.IO) {
                    if (yandexToken.isBlank()) {
                        emptyList()
                    } else {
                        try {
                            fetchAllYandexLikedTracks(
                                token = yandexToken,
                                maxTracksCap = BACKGROUND_SCAN_CAP,
                                onBatchFetched = {},
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }

            val vkTask =
                async(Dispatchers.IO) {
                    if (vkStored.isBlank()) {
                        emptyList()
                    } else {
                        try {
                            fetchAllVkAudioTracks(
                                rawStored = vkStored,
                                maxTracksCap = BACKGROUND_SCAN_CAP,
                                onPageFetched = { _, _ -> },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }

            val ytTask =
                async(Dispatchers.IO) {
                    val fetcher = externalYouTubeLikesFetcher ?: return@async emptyList()
                    try {
                        fetcher().take(BACKGROUND_SCAN_CAP)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

            val combined = ymTask.await() + vkTask.await() + ytTask.await()
            if (combined.isEmpty()) return@coroutineScope configStore.current.externalTasteProfile.size

            val entries =
                combined.map { item ->
                    LikedTrackBackup(
                        id = item.externalId,
                        artist = item.artist,
                        title = item.title,
                        thumbnail = null,
                    )
                }
            val mergedCount = configStore.mergeExternalTasteProfile(entries)
            Log.d(TAG, "Background taste scan updated externalTasteProfile: $mergedCount tracks")
            mergedCount
        }

    suspend fun fetchYandexChart(limit: Int = 25): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            val headers =
                buildMap {
                    put("User-Agent", YANDEX_UA)
                    put("Accept-Language", "ru")
                    if (token.isNotBlank()) put("Authorization", "OAuth $token")
                }
            try {
                val resp = CustomHttp.get("${yandexApiBase()}/landing3/chart", headers = headers, timeoutMs = 10_000)
                if (!resp.isSuccess) return@withContext emptyList()
                val chartTracks =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONObject("chart")
                        ?.optJSONArray("tracks")
                        ?: return@withContext emptyList()

                buildList {
                    for (i in 0 until minOf(chartTracks.length(), limit)) {
                        val trackObj = chartTracks.optJSONObject(i)?.optJSONObject("track") ?: continue
                        parseYandexTrack(trackObj)?.let(::add)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun fetchYandexNewReleases(limit: Int = 24): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            val headers =
                buildMap {
                    put("User-Agent", YANDEX_UA)
                    put("Accept-Language", "ru")
                    if (token.isNotBlank()) put("Authorization", "OAuth $token")
                }
            try {
                val resp = CustomHttp.get("${yandexApiBase()}/landing3/new-releases", headers = headers, timeoutMs = 10_000)
                if (!resp.isSuccess) return@withContext emptyList()
                val albumIdsArr =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONArray("newReleases")
                        ?: return@withContext emptyList()

                val albumIds =
                    buildList {
                        for (i in 0 until minOf(albumIdsArr.length(), limit)) {
                            val id = albumIdsArr.optString(i).trim()
                            if (id.isNotBlank()) add(id)
                        }
                    }
                if (albumIds.isEmpty()) return@withContext emptyList()

                val albumsBody =
                    postForm(
                        url = "${yandexApiBase()}/albums",
                        formBody = "album-ids=${CustomHttp.urlEncode(albumIds.joinToString(","))}",
                        headers = headers,
                    ) ?: return@withContext emptyList()

                val albumsArr = JSONObject(albumsBody).optJSONArray("result") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until albumsArr.length()) {
                        val obj = albumsArr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        val title = obj.optString("title").trim()
                        if (id.isBlank() || title.isBlank()) continue
                        val artist = parseYandexArtists(obj.optJSONArray("artists"))
                        val cover = formatYandexCover(obj.optString("coverUri"))
                        val explicit = obj.optString("contentWarning").equals("explicit", ignoreCase = true)
                        val rawDate = obj.optString("releaseDate").trim()
                        val relYear =
                            obj.optInt("year", 0).takeIf { it in 1900..2100 }
                                ?: rawDate.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
                        val relDateMs = parseIsoDateMs(rawDate)
                        add(
                            ExternalCatalogTrack(
                                source = "yandex",
                                externalId = "ym_alb_$id",
                                artist = artist,
                                title = title,
                                coverUrl = cover,
                                isExplicit = explicit,
                                releaseYear = relYear,
                                releaseDateMs = relDateMs,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun fetchVkPopular(limit: Int = 25): List<ExternalCatalogTrack> {
        val (recs, pop, mine) = fetchVkHomeBundle()
        return (recs.ifEmpty { pop }.ifEmpty { mine }).take(limit)
    }

    private suspend fun syncFetchedTracks(
        sourceLabel: String,
        tracks: List<ExternalCatalogTrack>,
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): LibraryImportSummary {
        val backupEntries =
            tracks.map { item ->
                LikedTrackBackup(
                    id = item.externalId,
                    artist = item.artist,
                    title = item.title,
                    thumbnail = item.coverUrl,
                )
            }
        configStore.mergeExternalTasteProfile(backupEntries)
        val savedTotal = configStore.mergeImportedLikes(backupEntries)

        val targetTracks =
            if (syncToLibraryLimit <= 0) {
                tracks
            } else {
                tracks.take(syncToLibraryLimit)
            }

        if (targetTracks.isEmpty()) {
            return LibraryImportSummary(
                sourceLabel = sourceLabel,
                totalFetched = tracks.size,
                savedForWave = savedTotal,
                addedToLibrary = 0,
            )
        }

        val existingLikedIds = withContext(Dispatchers.Main) { SongLikeState.likedSongIds.toMutableSet() }
        var addedToLibrary = 0
        var processed = 0
        val resolvedBackups = mutableListOf<LikedTrackBackup>()

        for (batch in targetTracks.chunked(5)) {
            val matchedSongs =
                coroutineScope {
                    batch.map { ext ->
                        async(Dispatchers.IO) {
                            val song =
                                if (ext.source == "youtube" && ext.externalId.isNotBlank() && !ext.externalId.startsWith("ym_") && !ext.externalId.startsWith("vk_")) {
                                    ext.toSongItem()
                                } else {
                                    matchExternalToSongItem(ext) ?: ext.toSongItem()
                                }

                            TrackBindingCache.put(song.id, ext.artist, ext.title) { prev ->
                                prev.copy(
                                    yandexId = if (ext.source == "yandex") ext.externalId.removePrefix("ym_") else prev.yandexId,
                                    vkAudioKey = if (ext.source == "vk") ext.externalId.removePrefix("vk_") else prev.vkAudioKey,
                                    hdCoverUrl = ext.coverUrl ?: prev.hdCoverUrl,
                                    durationSec = ext.durationSec ?: prev.durationSec,
                                    isExplicit = ext.isExplicit || prev.isExplicit,
                                )
                            }
                            song
                        }
                    }.awaitAll()
                }

            val newlyMatched = matchedSongs.filter { it.id.isNotBlank() && it.id !in existingLikedIds }
            if (newlyMatched.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    newlyMatched.forEach { song ->
                        if (song.id !in SongLikeState.likedSongIds) {
                            SongLikeState.toggleLike(song)
                            existingLikedIds.add(song.id)
                            addedToLibrary += 1
                            val art = song.artists.joinToString(", ") { it.name }.trim()
                            resolvedBackups.add(
                                LikedTrackBackup(
                                    id = song.id,
                                    artist = art,
                                    title = song.title,
                                    thumbnail = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri },
                                ),
                            )
                        }
                    }
                }
            }

            processed += batch.size
            onProgress(
                LibraryImportProgress(
                    message = "$sourceLabel: найдено ${tracks.size} • в Библиотеку $processed/${targetTracks.size} (добавлено: $addedToLibrary)",
                    fetchedCount = tracks.size,
                    syncedToLibrary = processed,
                    targetToSync = targetTracks.size,
                ),
            )
        }

        if (resolvedBackups.isNotEmpty()) {
            configStore.mergeImportedLikes(resolvedBackups)
        }

        return LibraryImportSummary(
            sourceLabel = sourceLabel,
            totalFetched = tracks.size,
            savedForWave = savedTotal,
            addedToLibrary = addedToLibrary,
        )
    }

    private suspend fun matchExternalToSongItem(ext: ExternalCatalogTrack): SongItem? {
        val query = listOf(ext.artist, ext.title).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return null
        return try {
            val page =
                GlobalYouTubeRepository.instance.searchFiltered(
                    query = query,
                    filter = SearchFilter.SONG,
                    allowOfflineFallback = false,
                )
            val candidates = page.items.filterIsInstance<SongItem>().filter { !it.isUnavailable && it.id.isNotBlank() }
            if (candidates.isEmpty()) return null

            val best =
                candidates
                    .mapNotNull { song ->
                        val art = song.artists.joinToString(", ") { it.name }.trim()
                        val score =
                            TrackMatcher.matchScore(
                                targetArtist = ext.artist,
                                targetTitle = ext.title,
                                targetDurationSec = ext.durationSec,
                                candidateArtist = art,
                                candidateTitle = song.title,
                                candidateDurationSec = song.duration,
                            )
                        if (score >= 55) song to score else null
                    }.maxByOrNull { it.second }
                    ?.first
                    ?: return null

            if (best.thumbnail.isNullOrBlank()) {
                val cover = ext.coverUrl?.takeIf { it.isNotBlank() } ?: CustomMascotAssets.fallbackCoverUri
                if (!cover.isNullOrBlank()) best.copy(thumbnail = cover) else best
            } else {
                best
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            Log.d(TAG, "Match failed for $query: ${err.message}")
            null
        }
    }

    private suspend fun fetchAllYandexLikedTracks(
        token: String,
        maxTracksCap: Int = 5_000,
        onBatchFetched: (Int) -> Unit,
    ): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val headers =
                mapOf(
                    "Authorization" to "OAuth $token",
                    "User-Agent" to YANDEX_UA,
                    "Accept-Language" to "ru",
                )
            val statusResp = CustomHttp.get("${yandexApiBase()}/account/status", headers = headers, timeoutMs = 10_000)
            check(statusResp.isSuccess) { "Я.Музыка: HTTP ${statusResp.code} — проверь токен" }
            val uid =
                JSONObject(statusResp.body)
                    .optJSONObject("result")
                    ?.optJSONObject("account")
                    ?.optString("uid")
                    .orEmpty()
                    .trim()
            check(uid.isNotBlank()) { "Не удалось получить UID Яндекс.Музыки" }

            val likesResp = CustomHttp.get("${yandexApiBase()}/users/$uid/likes/tracks", headers = headers, timeoutMs = 15_000)
            check(likesResp.isSuccess) { "Я.Музыка: не удалось загрузить лайки (HTTP ${likesResp.code})" }
            val rawTracks =
                JSONObject(likesResp.body)
                    .optJSONObject("result")
                    ?.optJSONObject("library")
                    ?.optJSONArray("tracks")
                    ?: return@withContext emptyList<ExternalCatalogTrack>()

            val trackIds =
                buildList {
                    for (i in 0 until minOf(rawTracks.length(), maxTracksCap)) {
                        val obj = rawTracks.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        if (id.isNotBlank()) add(id)
                    }
                }
            if (trackIds.isEmpty()) return@withContext emptyList<ExternalCatalogTrack>()

            val allTracks = mutableListOf<ExternalCatalogTrack>()
            for (chunk in trackIds.chunked(100)) {
                val body =
                    postForm(
                        url = "${yandexApiBase()}/tracks",
                        formBody = "track-ids=${CustomHttp.urlEncode(chunk.joinToString(","))}",
                        headers = headers,
                    ) ?: continue
                val arr = JSONObject(body).optJSONArray("result") ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseYandexTrack(item)?.let(allTracks::add)
                }
                onBatchFetched(allTracks.size)
                if (allTracks.size >= maxTracksCap) break
                delay(180L)
            }
            allTracks.take(maxTracksCap)
        }

    private suspend fun fetchAllVkAudioTracks(
        rawStored: String,
        maxTracksCap: Int = 5_000,
        onPageFetched: (fetched: Int, total: Int) -> Unit,
    ): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            var token =
                VkWebSession.resolveAccessToken(rawStored)
                    ?: error("Сессия ВК истекла — обнови вход в ВК")
            val allTracks = mutableListOf<ExternalCatalogTrack>()
            val hostsToTry = listOf(WEB_API_RU_HOST, API_VK_COM_HOST)
            var workingHost: String? = null
            var totalCount = -1
            var offset = 0
            val pageSize = minOf(200, maxTracksCap)

            while (offset < maxTracksCap) {
                val hosts = workingHost?.let { listOf(it) } ?: hostsToTry
                var pageItems: List<ExternalCatalogTrack>? = null
                var lastErr: String? = null
                var refreshedOnce = false

                for (host in hosts) {
                    val resp =
                        VkWebSession.postApiMethod(
                            host = host,
                            method = "audio.get",
                            apiVersion = "5.131",
                            params = mapOf("count" to pageSize.toString(), "offset" to offset.toString()),
                            accessToken = token,
                            timeoutMs = 12_000,
                        )
                    if (!resp.isSuccess) {
                        lastErr = "VK HTTP ${resp.code}"
                        continue
                    }
                    val root = JSONObject(resp.body)
                    if (root.has("error")) {
                        val err = root.optJSONObject("error")
                        val code = err?.optInt("error_code", -1) ?: -1
                        val msg = err?.optString("error_msg").orEmpty()
                        if (code == 9) {
                            if (allTracks.isNotEmpty()) break
                            error("VK #9: Флуд-контроль — подожди пару минут и повтори")
                        }
                        if (code == 5) {
                            if (!refreshedOnce) {
                                refreshedOnce = true
                                VkWebSession.invalidateCachedToken()
                                val fresh = VkWebSession.resolveAccessToken(rawStored, forceRefresh = true)
                                if (!fresh.isNullOrBlank()) {
                                    token = fresh
                                    continue
                                }
                            }
                            error("Сессия ВК истекла (#5) — обнови через «Обновить ВК»")
                        }
                        lastErr = "VK #$code: $msg"
                        continue
                    }

                    workingHost = host
                    val respObj = root.optJSONObject("response")
                    if (respObj != null) {
                        if (totalCount < 0) totalCount = respObj.optInt("count", -1)
                        pageItems = parseVkAudioArray(respObj.optJSONArray("items"), rejectUnavailableUrl = false)
                    } else {
                        pageItems = parseVkAudioArray(root.optJSONArray("response"), rejectUnavailableUrl = false)
                    }
                    break
                }

                if (pageItems == null) {
                    if (allTracks.isEmpty()) {
                        error(lastErr ?: "Не удалось загрузить аудиозаписи ВК")
                    }
                    break
                }
                if (pageItems.isEmpty()) break

                allTracks.addAll(pageItems)
                offset += pageItems.size
                onPageFetched(allTracks.size, totalCount)

                if (pageItems.size < pageSize) break
                if (totalCount > 0 && offset >= totalCount) break
                if (allTracks.size >= maxTracksCap) break
                delay(550L)
            }

            allTracks.take(maxTracksCap)
        }

    private fun parseYandexTrack(obj: JSONObject): ExternalCatalogTrack? {
        val id = obj.optString("id").trim()
        val title = obj.optString("title").trim()
        if (id.isBlank() || title.isBlank()) return null
        val version = obj.optString("version").trim()
        val fullTitle = if (version.isNotBlank() && !title.contains(version, ignoreCase = true)) "$title ($version)" else title
        val artist = parseYandexArtists(obj.optJSONArray("artists"))
        val durationSec = obj.optLong("durationMs", 0L).takeIf { it > 0L }?.div(1000L)
        val cover = formatYandexCover(obj.optString("coverUri"))
        val explicit = obj.optString("contentWarning").equals("explicit", ignoreCase = true)
        val (relYear, relDateMs) = parseYandexAlbumRelease(obj.optJSONArray("albums"))
        return ExternalCatalogTrack(
            source = "yandex",
            externalId = "ym_$id",
            artist = artist,
            title = fullTitle,
            durationSec = durationSec,
            coverUrl = cover,
            isExplicit = explicit,
            releaseYear = relYear,
            releaseDateMs = relDateMs,
        )
    }

    private fun parseYandexAlbumRelease(albums: JSONArray?): Pair<Int?, Long?> {
        if (albums == null || albums.length() == 0) return null to null
        val nonCompilations = mutableListOf<Pair<Int?, Long?>>()
        val allParsed = mutableListOf<Pair<Int?, Long?>>()
        for (i in 0 until albums.length()) {
            val alb = albums.optJSONObject(i) ?: continue
            val rawDate = alb.optString("releaseDate").trim()
            val yearFromField = alb.optInt("year", 0).takeIf { it in 1900..2100 }
            val yearFromDate = rawDate.takeIf { it.length >= 4 }?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
            val year = yearFromField ?: yearFromDate
            val ms = parseIsoDateMs(rawDate)
            if (year == null && ms == null) continue
            val pair = year to ms
            allParsed.add(pair)
            if (!alb.optString("type").trim().equals("compilation", ignoreCase = true)) {
                nonCompilations.add(pair)
            }
        }
        val pool = nonCompilations.ifEmpty { allParsed }
        if (pool.isEmpty()) return null to null
        return pool.minWithOrNull(
            compareBy<Pair<Int?, Long?>>(
                { it.first ?: Int.MAX_VALUE },
                { it.second ?: Long.MAX_VALUE },
            ),
        ) ?: pool.first()
    }

    private fun parseIsoDateMs(rawDate: String?): Long? {
        val clean = rawDate?.trim().orEmpty()
        if (clean.length < 10) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(clean.take(10))?.time
        }.getOrNull()
    }

    private fun parseYandexArtists(arr: JSONArray?): String {
        if (arr == null) return ""
        return buildList {
            for (i in 0 until arr.length()) {
                val name = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }.joinToString(", ")
    }

    private fun formatYandexCover(rawUri: String): String? {
        val clean = rawUri.trim()
        if (clean.isBlank()) return null
        val normalized = if (clean.startsWith("http")) clean else "https://$clean"
        return normalized.replace("%%", "600x600")
    }

    private fun parseVkAudioArray(
        items: JSONArray?,
        rejectUnavailableUrl: Boolean = false,
    ): List<ExternalCatalogTrack> {
        if (items == null) return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                if (rejectUnavailableUrl) {
                    val streamUrl = obj.optString("url").trim()
                    if (streamUrl.contains("audio_api_unavailable", ignoreCase = true)) continue
                }
                val artist = obj.optString("artist").trim()
                val title = obj.optString("title").trim()
                if (title.isBlank()) continue
                val subtitle = obj.optString("subtitle").trim()
                val fullTitle = if (subtitle.isNotBlank()) "$title ($subtitle)" else title
                val ownerId = obj.optLong("owner_id", 0L)
                val id = obj.optLong("id", 0L)
                val extId = if (id != 0L) "vk_${ownerId}_$id" else ""
                val durationSec = obj.optLong("duration", 0L).takeIf { it > 0L }
                val cover =
                    obj
                        .optJSONObject("album")
                        ?.optJSONObject("thumb")
                        ?.let { thumb ->
                            thumb.optString("photo_1200")
                                .ifBlank { thumb.optString("photo_600") }
                                .ifBlank { thumb.optString("photo_300") }
                        }?.takeIf { it.isNotBlank() }
                add(
                    ExternalCatalogTrack(
                        source = "vk",
                        externalId = extId,
                        artist = artist,
                        title = fullTitle,
                        durationSec = durationSec,
                        coverUrl = cover,
                        isExplicit = obj.optBoolean("is_explicit", false),
                    ),
                )
            }
        }
    }

    private fun postForm(
        url: String,
        formBody: String,
        headers: Map<String, String>,
    ): String? =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 12_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(formBody)
                    writer.flush()
                }
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    private fun sanitizeYandexToken(raw: String): String =
        raw.trim().removePrefix("OAuth ").removePrefix("Bearer ").trim()

    companion object {
        private const val TAG = "CustomCatalog"
        private const val YANDEX_UA = "Yandex-Music-API"
        private const val WEB_API_RU_HOST = "web.api.vk.ru"
        private const val API_VK_COM_HOST = "api.vk.com"
        private const val HOME_SHELVES_TTL_MS = 3L * 60 * 60 * 1_000L
        private const val FRESH_POOL_TTL_MS = 2L * 60 * 60 * 1_000L
        private const val DAY_MS = 24L * 3600L * 1_000L
        private const val BACKGROUND_SCAN_CAP = 200
        private const val HOME_CACHE_FILE_NAME = "custom_home_snapshot_v1.json"

        private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val fallbackDeezerClient by lazy { DeezerReferenceClient() }
        private val freshPoolMutex = Mutex()

        private val _homeSnapshot = MutableStateFlow(HomeCatalogSnapshot())
        val homeSnapshot: StateFlow<HomeCatalogSnapshot> = _homeSnapshot.asStateFlow()

        @Volatile
        private var cachedFreshArtistReleases: List<ExternalCatalogTrack> = emptyList()

        @Volatile
        private var cachedFreshArtistAtMs: Long = 0L

        @Volatile
        var externalYouTubeLikesFetcher: (suspend () -> List<ExternalCatalogTrack>)? = null

        @Volatile
        private var homeCacheFile: File? = null

        @Volatile
        private var lastLoadedAtMs: Long = 0L

        @Volatile
        private var isHomeLoading: Boolean = false

        @Volatile
        private var isTasteScanning: Boolean = false

        @Volatile
        private var vkSearchFloodUntilMs: Long = 0L

        @Volatile
        private var searchJob: Job? = null

        @Volatile
        private var lastSearchedQuery: String = ""

        /**
         * Возвращает кэшированный пул реальных релизов любимых артистов пользователя за последний год (<= 365 дней).
         */
        suspend fun getCachedFreshArtistReleases(
            configStore: CustomConfigStore,
            maxAgeDays: Int = 365,
            limit: Int = 24,
        ): List<ExternalCatalogTrack> {
            val now = System.currentTimeMillis()
            if (cachedFreshArtistReleases.isNotEmpty() && now - cachedFreshArtistAtMs < FRESH_POOL_TTL_MS) {
                return cachedFreshArtistReleases.take(limit)
            }
            return freshPoolMutex.withLock {
                val recheckNow = System.currentTimeMillis()
                if (cachedFreshArtistReleases.isNotEmpty() && recheckNow - cachedFreshArtistAtMs < FRESH_POOL_TTL_MS) {
                    return@withLock cachedFreshArtistReleases.take(limit)
                }
                val topArtists = configStore.getLocalTopArtists(limit = 16)
                if (topArtists.isEmpty()) return@withLock emptyList()
                val deezer = HybridStreamResolver.getDeezerClient() ?: fallbackDeezerClient
                val fetched =
                    coroutineScope {
                        topArtists
                            .take(12)
                            .chunked(4)
                            .flatMap { chunk ->
                                chunk
                                    .map { artistName ->
                                        async(Dispatchers.IO) {
                                            deezer.fetchArtistRecentReleases(
                                                artistName = artistName,
                                                maxAgeDays = maxAgeDays,
                                                limit = 3,
                                            )
                                        }
                                    }.awaitAll()
                                    .flatten()
                            }
                    }.distinctBy { CustomConfigStore.buildTrackKey(it.artist, it.title).ifBlank { it.externalId } }

                if (fetched.isNotEmpty()) {
                    cachedFreshArtistReleases = fetched
                    cachedFreshArtistAtMs = System.currentTimeMillis()
                }
                fetched.take(limit)
            }
        }

        /**
         * Быстрый пул реально вышедших свежих треков (<= maxAgeDays) из «Премьеры», «Новых релизов»,
         * свежей части чартов Яндекса/Deezer и радара любимых артистов.
         */
        suspend fun getLiveFreshCatalogPool(
            configStore: CustomConfigStore,
            maxAgeDays: Int = 366,
            limit: Int = 48,
        ): List<ExternalCatalogTrack> =
            coroutineScope {
                val existingSnapshot = _homeSnapshot.value
                val snapshotTask =
                    async(Dispatchers.IO) {
                        if (existingSnapshot.isNotEmpty) {
                            existingSnapshot
                        } else {
                            withTimeoutOrNull(2_400L) {
                                val provider = CustomCatalogProvider(configStore)
                                val ymChart = async { provider.fetchYandexChart(24) }
                                val ymNew = async { provider.fetchYandexNewReleases(20) }
                                val dzChart = async { (HybridStreamResolver.getDeezerClient() ?: fallbackDeezerClient).fetchChart(24) }
                                HomeCatalogSnapshot(
                                    yandexChart = ymChart.await(),
                                    yandexNewReleases = ymNew.await(),
                                    deezerChart = dzChart.await(),
                                    updatedAtMs = System.currentTimeMillis(),
                                )
                            } ?: existingSnapshot
                        }
                    }

                val artistFreshTask =
                    async(Dispatchers.IO) {
                        if (cachedFreshArtistReleases.isNotEmpty()) {
                            cachedFreshArtistReleases
                        } else {
                            withTimeoutOrNull(1_600L) {
                                getCachedFreshArtistReleases(
                                    configStore = configStore,
                                    maxAgeDays = maxAgeDays,
                                    limit = 20,
                                )
                            }.orEmpty()
                        }
                    }

                val snap = snapshotTask.await()
                val artistFresh = artistFreshTask.await()

                val seen = HashSet<String>()
                val out = ArrayList<ExternalCatalogTrack>(limit)

                fun addFiltered(
                    source: List<ExternalCatalogTrack>,
                    trustWithoutDate: Boolean,
                ) {
                    for (t in source) {
                        if (out.size >= limit) break
                        if (t.artist.isBlank() || t.title.isBlank()) continue
                        if (!isTrackFreshForCatalog(t, maxAgeDays = maxAgeDays, trustWithoutDate = trustWithoutDate)) continue
                        val key = CustomConfigStore.buildTrackKey(t.artist, t.title)
                        if (key.isNotBlank() && seen.add(key)) {
                            out.add(t)
                        }
                    }
                }

                // Сначала персональные свежие источники, потом свежая часть чартов
                addFiltered(artistFresh, trustWithoutDate = true)
                addFiltered(snap.yandexPremiereTracks, trustWithoutDate = true)
                addFiltered(snap.releaseRadar, trustWithoutDate = true)
                addFiltered(snap.yandexChart, trustWithoutDate = false)
                addFiltered(snap.deezerChart, trustWithoutDate = false)
                addFiltered(snap.yandexNewReleases, trustWithoutDate = true)
                out
            }

        private fun isTrackFreshForCatalog(
            track: ExternalCatalogTrack,
            maxAgeDays: Int,
            trustWithoutDate: Boolean,
        ): Boolean {
            val nowMs = System.currentTimeMillis()
            val relMs = track.releaseDateMs
            if (relMs != null && relMs > 0L) {
                return (nowMs - relMs) <= maxAgeDays.toLong() * DAY_MS
            }
            val relYear = track.releaseYear
            if (relYear != null && relYear in 1900..2100) {
                val cal = Calendar.getInstance()
                val currentYear = cal.get(Calendar.YEAR)
                val currentMonth = cal.get(Calendar.MONTH) + 1
                val cutoffCal = Calendar.getInstance().apply { timeInMillis = nowMs - maxAgeDays.toLong() * DAY_MS }
                val cutoffYear = cutoffCal.get(Calendar.YEAR)
                return when {
                    relYear >= currentYear -> true
                    relYear < cutoffYear -> false
                    maxAgeDays <= 366 && currentMonth >= 10 -> false
                    else -> true
                }
            }
            return trustWithoutDate
        }

        fun initDiskCache(appContext: Context) {
            if (homeCacheFile != null) return
            val file = File(appContext.filesDir, HOME_CACHE_FILE_NAME)
            homeCacheFile = file
            homeScope.launch {
                val restored = loadSnapshotFromDisk(file) ?: return@launch
                if (restored.isNotEmpty) {
                    _homeSnapshot.value = restored
                    lastLoadedAtMs = restored.updatedAtMs
                    val shelves = buildHomeShelvesFromSnapshot(restored)
                    if (shelves.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (CustomHomeShelvesBridge.shelves.isEmpty()) {
                                CustomHomeShelvesBridge.shelves = shelves
                            }
                        }
                    }
                }
            }
        }

        fun triggerBackgroundTasteScan(
            configStore: CustomConfigStore,
            force: Boolean = false,
        ) {
            val cfg = configStore.current
            if (cfg.yandexToken.isBlank() && cfg.vkToken.isBlank() && externalYouTubeLikesFetcher == null) return
            val needsInitialScan = cfg.externalTasteProfile.isEmpty()
            if (!force && !needsInitialScan) return
            if (isTasteScanning) return

            isTasteScanning = true
            homeScope.launch {
                try {
                    CustomCatalogProvider(configStore).scanExternalTasteProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Exception) {
                    Log.w(TAG, "Background taste scan skipped: ${err.message}")
                } finally {
                    isTasteScanning = false
                }
            }
        }

        fun bindHomeShelvesBridge(configStore: CustomConfigStore) {
            val provider = CustomCatalogProvider(configStore)

            triggerBackgroundTasteScan(configStore, force = false)

            CustomHomeShelvesBridge.onRefreshRequested = { force ->
                val cached = _homeSnapshot.value
                val now = System.currentTimeMillis()
                val stale = !cached.isNotEmpty || now - lastLoadedAtMs > HOME_SHELVES_TTL_MS
                if ((force || stale) && !isHomeLoading) {
                    isHomeLoading = true
                    homeScope.launch {
                        withContext(Dispatchers.Main) {
                            if (CustomHomeShelvesBridge.shelves.isEmpty() && cached.isNotEmpty) {
                                CustomHomeShelvesBridge.shelves = buildHomeShelvesFromSnapshot(cached)
                            }
                            CustomHomeShelvesBridge.isLoading = true
                        }
                        try {
                            val loaded = provider.loadHomeShelves()
                            if (loaded.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    CustomHomeShelvesBridge.shelves = loaded
                                }
                                lastLoadedAtMs = System.currentTimeMillis()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (err: Exception) {
                            Log.w(TAG, "Failed to load home shelves: ${err.message}")
                        } finally {
                            isHomeLoading = false
                            withContext(Dispatchers.Main) {
                                CustomHomeShelvesBridge.isLoading = false
                            }
                        }
                    }
                }
            }

            CustomSearchBridge.onSearchRequested = { rawQuery ->
                val clean = rawQuery.trim()
                val alreadyHasResults =
                    clean.equals(lastSearchedQuery, ignoreCase = true) &&
                        (CustomSearchBridge.isLoading || CustomSearchBridge.yandexSongs.isNotEmpty() || CustomSearchBridge.vkSongs.isNotEmpty())
                if (clean.isNotBlank() && !alreadyHasResults) {
                    lastSearchedQuery = clean
                    searchJob?.cancel()
                    searchJob =
                        homeScope.launch {
                            withContext(Dispatchers.Main) {
                                CustomSearchBridge.query = clean
                                CustomSearchBridge.yandexSongs = emptyList()
                                CustomSearchBridge.vkSongs = emptyList()
                                CustomSearchBridge.isLoading = true
                            }
                            try {
                                val (ymList, vkList) =
                                    coroutineScope {
                                        val ymTask = async { provider.searchYandexTracks(clean, limit = 10) }
                                        val vkTask = async { provider.searchVkTracks(clean, limit = 10) }
                                        ymTask.await() to vkTask.await()
                                    }
                                if (lastSearchedQuery.equals(clean, ignoreCase = true)) {
                                    withContext(Dispatchers.Main) {
                                        CustomSearchBridge.query = clean
                                        CustomSearchBridge.yandexSongs = ymList
                                        CustomSearchBridge.vkSongs = vkList
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (err: Exception) {
                                Log.w(TAG, "Custom search failed: ${err.message}")
                            } finally {
                                if (lastSearchedQuery.equals(clean, ignoreCase = true)) {
                                    withContext(Dispatchers.Main) {
                                        CustomSearchBridge.isLoading = false
                                    }
                                }
                            }
                        }
                }
            }
        }

        private fun buildHomeShelvesFromSnapshot(snapshot: HomeCatalogSnapshot): List<HomeShelf> {
            val radarSongs = snapshot.releaseRadar.map { it.toSongItem() }.distinctBy { it.id }
            val chartSongs = snapshot.yandexChart.map { it.toSongItem() }.distinctBy { it.id }
            val vkSongs = snapshot.vkChart.map { it.toSongItem() }.distinctBy { it.id }
            val deezerSongs = snapshot.deezerChart.map { it.toSongItem() }.distinctBy { it.id }

            return buildList {
                if (radarSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_release_radar",
                            title = "Радар новинок",
                            items = radarSongs,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                        ),
                    )
                }
                if (chartSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_ym_chart_rf",
                            title = "Чарт Я.Музыки",
                            items = chartSongs,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            compactRows = true,
                        ),
                    )
                }
                if (vkSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_vk_popular",
                            title = "Рекомендации VK",
                            items = vkSongs,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            compactRows = true,
                        ),
                    )
                }
                if (deezerSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_deezer_chart",
                            title = "Мировой чарт • Deezer",
                            items = deezerSongs,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            compactRows = true,
                        ),
                    )
                }
            }
        }

        private fun saveSnapshotToDisk(snapshot: HomeCatalogSnapshot) {
            val file = homeCacheFile ?: return
            runCatching {
                val root =
                    JSONObject()
                        .put("updatedAtMs", snapshot.updatedAtMs)
                        .put("yandexChart", serializeTrackList(snapshot.yandexChart))
                        .put("yandexNewReleases", serializeTrackList(snapshot.yandexNewReleases))
                        .put("yandexDailyTitle", snapshot.yandexDailyTitle)
                        .put("yandexDailyTracks", serializeTrackList(snapshot.yandexDailyTracks))
                        .put("yandexPremiereTitle", snapshot.yandexPremiereTitle)
                        .put("yandexPremiereTracks", serializeTrackList(snapshot.yandexPremiereTracks))
                        .put("vkChart", serializeTrackList(snapshot.vkChart))
                        .put("vkPopular", serializeTrackList(snapshot.vkPopular))
                        .put("vkMyMusic", serializeTrackList(snapshot.vkMyMusic))
                        .put("deezerChart", serializeTrackList(snapshot.deezerChart))
                        .put("releaseRadar", serializeTrackList(snapshot.releaseRadar))
                val tmp = File(file.parentFile, "$HOME_CACHE_FILE_NAME.tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to save home snapshot cache: ${err.message}")
            }
        }

        private fun loadSnapshotFromDisk(file: File): HomeCatalogSnapshot? {
            if (!file.exists()) return null
            return runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                HomeCatalogSnapshot(
                    yandexChart = parseTrackList(root.optJSONArray("yandexChart")),
                    yandexNewReleases = parseTrackList(root.optJSONArray("yandexNewReleases")),
                    yandexDailyTitle = root.optString("yandexDailyTitle", "Плейлист дня").ifBlank { "Плейлист дня" },
                    yandexDailyTracks = parseTrackList(root.optJSONArray("yandexDailyTracks")),
                    yandexPremiereTitle = root.optString("yandexPremiereTitle", "Премьера").ifBlank { "Премьера" },
                    yandexPremiereTracks = parseTrackList(root.optJSONArray("yandexPremiereTracks")),
                    vkChart = parseTrackList(root.optJSONArray("vkChart")),
                    vkPopular = parseTrackList(root.optJSONArray("vkPopular")),
                    vkMyMusic = parseTrackList(root.optJSONArray("vkMyMusic")),
                    deezerChart = parseTrackList(root.optJSONArray("deezerChart")),
                    releaseRadar = parseTrackList(root.optJSONArray("releaseRadar")),
                    updatedAtMs = root.optLong("updatedAtMs", 0L),
                )
            }.getOrNull()
        }

        private fun serializeTrackList(list: List<ExternalCatalogTrack>): JSONArray =
            JSONArray().apply {
                list.forEach { t ->
                    put(
                        JSONObject()
                            .put("src", t.source)
                            .put("id", t.externalId)
                            .put("a", t.artist)
                            .put("t", t.title)
                            .apply {
                                if (t.durationSec != null && t.durationSec > 0L) put("d", t.durationSec)
                                if (!t.coverUrl.isNullOrBlank()) put("c", t.coverUrl)
                                if (t.isExplicit) put("e", true)
                                if (t.releaseYear != null && t.releaseYear > 0) put("y", t.releaseYear)
                                if (t.releaseDateMs != null && t.releaseDateMs > 0L) put("rd", t.releaseDateMs)
                            },
                    )
                }
            }

        private fun parseTrackList(arr: JSONArray?): List<ExternalCatalogTrack> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("t").trim()
                    if (title.isBlank()) continue
                    add(
                        ExternalCatalogTrack(
                            source = o.optString("src", "yandex"),
                            externalId = o.optString("id").trim(),
                            artist = o.optString("a").trim(),
                            title = title,
                            durationSec = o.optLong("d", -1L).takeIf { it > 0L },
                            coverUrl = o.optString("c").takeIf { it.isNotBlank() },
                            isExplicit = o.optBoolean("e", false),
                            releaseYear = o.optInt("y", -1).takeIf { it in 1900..2100 },
                            releaseDateMs = o.optLong("rd", -1L).takeIf { it > 0L },
                        ),
                    )
                }
            }
        }

        private fun yandexApiBase(): String =
            "https://" + listOf("api", "music", "yandex", "net").joinToString(".")
    }
}
