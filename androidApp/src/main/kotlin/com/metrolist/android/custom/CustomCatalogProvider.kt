package com.metrolist.android.custom

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ExternalCatalogTrack(
    val source: String,
    val externalId: String,
    val artist: String,
    val title: String,
    val durationSec: Long? = null,
    val coverUrl: String? = null,
    val isExplicit: Boolean = false,
) {
    fun toSongItem(): SongItem {
        val parsedArtists =
            artist
                .split(",", "&")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { ArtistTiny(id = null, name = it) }
                .ifEmpty { listOf(ArtistTiny(id = null, name = "Неизвестный")) }
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
    suspend fun loadHomeShelves(): List<HomeShelf> =
        coroutineScope {
            val chartTask = async { fetchYandexChart(24) }
            val releasesTask = async { fetchYandexNewReleases(16) }
            val vkPopularTask = async { fetchVkPopular(24) }

            val chartSongs = chartTask.await().map { it.toSongItem() }.distinctBy { it.id }
            val releaseSongs = releasesTask.await().map { it.toSongItem() }.distinctBy { it.id }
            val vkSongs = vkPopularTask.await().map { it.toSongItem() }.distinctBy { it.id }

            buildList {
                if (chartSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_ym_chart_rf",
                            title = "Чарт Я.Музыки • Топ РФ",
                            items = chartSongs,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            compactRows = true,
                        ),
                    )
                }
                if (releaseSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_ym_new_releases",
                            title = "Новые релизы • Я.Музыка",
                            items = releaseSongs,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                        ),
                    )
                }
                if (vkSongs.isNotEmpty()) {
                    add(
                        HomeShelf(
                            key = "custom_vk_popular",
                            title = "Для тебя в VK",
                            items = vkSongs,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            compactRows = true,
                        ),
                    )
                }
            }
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
            runCatching<List<SongItem>> {
                val url = "${yandexApiBase()}/search?text=${CustomHttp.urlEncode(clean)}&type=track&page=0"
                val resp = CustomHttp.get(url, headers = headers, timeoutMs = 8_000)
                if (!resp.isSuccess) return@runCatching emptyList<SongItem>()
                val arr =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONObject("tracks")
                        ?.optJSONArray("results")
                        ?: return@runCatching emptyList<SongItem>()

                buildList {
                    for (i in 0 until minOf(arr.length(), limit)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseYandexTrack(obj)?.toSongItem()?.let(::add)
                    }
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

    suspend fun searchVkTracks(
        query: String,
        limit: Int = 10,
    ): List<SongItem> =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            if (clean.isBlank()) return@withContext emptyList()
            if (System.currentTimeMillis() < vkSearchFloodUntilMs) return@withContext emptyList()
            val token = sanitizeVkToken(configStore.current.vkToken)
            if (token.isBlank()) return@withContext emptyList()

            val encodedQuery = CustomHttp.urlEncode(clean)
            val encodedToken = CustomHttp.urlEncode(token)
            val urls =
                listOf(
                    "${vkApiBase()}/catalog.getAudioSearch?query=$encodedQuery&need_blocks=1&lang=ru&access_token=$encodedToken&v=5.131",
                    "${vkApiBase()}/audio.search?q=$encodedQuery&count=$limit&sort=2&auto_complete=1&lang=ru&access_token=$encodedToken&v=5.95",
                )

            for (url in urls) {
                val found =
                    runCatching<List<SongItem>?> {
                        val resp = CustomHttp.get(url, headers = mapOf("User-Agent" to KATE_UA), timeoutMs = 8_000)
                        if (!resp.isSuccess) return@runCatching null
                        val root = JSONObject(resp.body)
                        if (root.has("error")) {
                            val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                            if (code == 9) {
                                vkSearchFloodUntilMs = System.currentTimeMillis() + 3 * 60 * 1_000L
                                return@runCatching emptyList<SongItem>()
                            }
                            return@runCatching null
                        }
                        val respObj = root.optJSONObject("response") ?: return@runCatching emptyList<SongItem>()
                        val arr = respObj.optJSONArray("audios") ?: respObj.optJSONArray("items")
                        parseVkAudioArray(arr).take(limit).map { it.toSongItem() }.distinctBy { it.id }
                    }.getOrNull()

                if (found != null) return@withContext found
            }
            emptyList()
        }

    suspend fun importYandexLikes(
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): Result<LibraryImportSummary> =
        runCatching {
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            require(token.isNotBlank()) { "Сначала укажи токен Яндекс.Музыки" }

            onProgress(LibraryImportProgress("Подключаюсь к Яндекс.Музыке…"))
            val tracks = fetchAllYandexLikedTracks(token) { count ->
                onProgress(LibraryImportProgress("Я.Музыка: получено треков: $count…", fetchedCount = count))
            }
            check(tracks.isNotEmpty()) { "В Яндекс.Музыке не найдено лайкнутых треков" }

            syncFetchedTracks(
                sourceLabel = "Я.Музыка",
                tracks = tracks,
                syncToLibraryLimit = syncToLibraryLimit,
                onProgress = onProgress,
            )
        }

    suspend fun importVkAudios(
        syncToLibraryLimit: Int,
        onProgress: (LibraryImportProgress) -> Unit,
    ): Result<LibraryImportSummary> =
        runCatching {
            val token = sanitizeVkToken(configStore.current.vkToken)
            require(token.isNotBlank()) { "Сначала укажи токен ВК" }

            onProgress(LibraryImportProgress("Загружаю медиатеку ВК…"))
            val tracks = fetchAllVkAudioTracks(token) { fetched, total ->
                val suffix = if (total > 0) " из $total" else ""
                onProgress(LibraryImportProgress("VK: загружено $fetched$suffix треков…", fetchedCount = fetched))
            }
            check(tracks.isNotEmpty()) { "В ВК не найдено аудиозаписей (или доступ закрыт)" }

            syncFetchedTracks(
                sourceLabel = "VK",
                tracks = tracks,
                syncToLibraryLimit = syncToLibraryLimit,
                onProgress = onProgress,
            )
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
            runCatching {
                val resp = CustomHttp.get("${yandexApiBase()}/landing3/chart", headers = headers, timeoutMs = 10_000)
                if (!resp.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                val chartTracks =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONObject("chart")
                        ?.optJSONArray("tracks")
                        ?: return@runCatching emptyList<ExternalCatalogTrack>()

                buildList {
                    for (i in 0 until minOf(chartTracks.length(), limit)) {
                        val trackObj = chartTracks.optJSONObject(i)?.optJSONObject("track") ?: continue
                        parseYandexTrack(trackObj)?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun fetchYandexNewReleases(limit: Int = 20): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val token = sanitizeYandexToken(configStore.current.yandexToken)
            val headers =
                buildMap {
                    put("User-Agent", YANDEX_UA)
                    put("Accept-Language", "ru")
                    if (token.isNotBlank()) put("Authorization", "OAuth $token")
                }
            runCatching {
                val resp = CustomHttp.get("${yandexApiBase()}/landing3/new-releases", headers = headers, timeoutMs = 10_000)
                if (!resp.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                val albumIdsArr =
                    JSONObject(resp.body)
                        .optJSONObject("result")
                        ?.optJSONArray("newReleases")
                        ?: return@runCatching emptyList<ExternalCatalogTrack>()

                val albumIds =
                    buildList {
                        for (i in 0 until minOf(albumIdsArr.length(), limit)) {
                            val id = albumIdsArr.optString(i).trim()
                            if (id.isNotBlank()) add(id)
                        }
                    }
                if (albumIds.isEmpty()) return@runCatching emptyList<ExternalCatalogTrack>()

                val albumsBody =
                    postForm(
                        url = "${yandexApiBase()}/albums",
                        formBody = "album-ids=${CustomHttp.urlEncode(albumIds.joinToString(","))}",
                        headers = headers,
                    ) ?: return@runCatching emptyList<ExternalCatalogTrack>()

                val albumsArr = JSONObject(albumsBody).optJSONArray("result") ?: return@runCatching emptyList<ExternalCatalogTrack>()
                buildList {
                    for (i in 0 until albumsArr.length()) {
                        val obj = albumsArr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").trim()
                        val title = obj.optString("title").trim()
                        if (id.isBlank() || title.isBlank()) continue
                        val artist = parseYandexArtists(obj.optJSONArray("artists"))
                        val cover = formatYandexCover(obj.optString("coverUri"))
                        val explicit = obj.optString("contentWarning").equals("explicit", ignoreCase = true)
                        add(
                            ExternalCatalogTrack(
                                source = "yandex",
                                externalId = "ym_alb_$id",
                                artist = artist,
                                title = title,
                                coverUrl = cover,
                                isExplicit = explicit,
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

    suspend fun fetchVkPopular(limit: Int = 25): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val token = sanitizeVkToken(configStore.current.vkToken)
            if (token.isBlank()) return@withContext emptyList()
            val encodedToken = CustomHttp.urlEncode(token)
            val safeLimit = limit.coerceIn(1, 50)
            val attempts =
                listOf(
                    "${vkApiBase()}/audio.getRecommendations?count=$safeLimit&shuffle=1&access_token=$encodedToken&v=5.131" to KATE_UA,
                    "${vkApiBase()}/catalog.getAudio?need_blocks=1&access_token=$encodedToken&v=5.131" to KATE_UA,
                    "${vkApiBase()}/audio.getRecommendations?count=$safeLimit&shuffle=1&access_token=$encodedToken&v=5.95" to KATE_UA,
                    "${vkApiBase()}/audio.getPopular?count=$safeLimit&access_token=$encodedToken&v=5.95" to KATE_UA,
                    "${vkApiBase()}/audio.get?count=$safeLimit&offset=0&access_token=$encodedToken&v=5.131" to KATE_UA,
                    "${vkApiBase()}/catalog.getAudio?need_blocks=1&access_token=$encodedToken&v=5.131" to VK_ANDROID_UA,
                )

            for ((url, ua) in attempts) {
                val found =
                    runCatching<List<ExternalCatalogTrack>> {
                        val resp = CustomHttp.get(url, headers = mapOf("User-Agent" to ua), timeoutMs = 10_000)
                        if (!resp.isSuccess) return@runCatching emptyList<ExternalCatalogTrack>()
                        val root = JSONObject(resp.body)
                        if (root.has("error")) return@runCatching emptyList<ExternalCatalogTrack>()
                        val respObj = root.optJSONObject("response")
                        val arr =
                            respObj?.optJSONArray("items")
                                ?: respObj?.optJSONArray("audios")
                                ?: root.optJSONArray("response")
                        parseVkAudioArray(arr).take(safeLimit)
                    }.getOrDefault(emptyList())

                if (found.isNotEmpty()) {
                    return@withContext found
                }
            }
            emptyList()
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
                )
            }
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
                            // Если трека нет на YouTube — добавляем его напрямую как ym_/vk_, чтобы импортировались все 100%
                            matchExternalToSongItem(ext) ?: ext.toSongItem()
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
                            resolvedBackups.add(LikedTrackBackup(id = song.id, artist = art, title = song.title))
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
                        if (score >= 52) song to score else null
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
                    for (i in 0 until rawTracks.length()) {
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
                delay(180L)
            }
            allTracks
        }

    private suspend fun fetchAllVkAudioTracks(
        token: String,
        onPageFetched: (fetched: Int, total: Int) -> Unit,
    ): List<ExternalCatalogTrack> =
        withContext(Dispatchers.IO) {
            val allTracks = mutableListOf<ExternalCatalogTrack>()
            val versionsToTry = listOf("5.131", "5.95")
            var workingVersion: String? = null
            var totalCount = -1
            var offset = 0
            val pageSize = 200
            val maxTracksCap = 5_000

            while (offset < maxTracksCap) {
                val versions = workingVersion?.let { listOf(it) } ?: versionsToTry
                var pageItems: List<ExternalCatalogTrack>? = null
                var lastErr: String? = null

                for (ver in versions) {
                    val url =
                        "${vkApiBase()}/audio.get" +
                            "?count=$pageSize" +
                            "&offset=$offset" +
                            "&access_token=${CustomHttp.urlEncode(token)}" +
                            "&v=$ver"
                    val resp = CustomHttp.get(url, headers = mapOf("User-Agent" to KATE_UA), timeoutMs = 12_000)
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
                            error("Токен ВК истёк (#5)")
                        }
                        lastErr = "VK #$code: $msg"
                        continue
                    }

                    workingVersion = ver
                    val respObj = root.optJSONObject("response")
                    if (respObj != null) {
                        if (totalCount < 0) totalCount = respObj.optInt("count", -1)
                        pageItems = parseVkAudioArray(respObj.optJSONArray("items"))
                    } else {
                        pageItems = parseVkAudioArray(root.optJSONArray("response"))
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
                delay(550L)
            }

            allTracks
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
        return ExternalCatalogTrack(
            source = "yandex",
            externalId = "ym_$id",
            artist = artist,
            title = fullTitle,
            durationSec = durationSec,
            coverUrl = cover,
            isExplicit = explicit,
        )
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
        return normalized.replace("%%", "400x400")
    }

    private fun parseVkAudioArray(items: JSONArray?): List<ExternalCatalogTrack> {
        if (items == null) return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
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
                            thumb.optString("photo_600").ifBlank { thumb.optString("photo_300") }
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

    private fun sanitizeVkToken(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.contains("access_token=")) {
            trimmed.substringAfter("access_token=").substringBefore("&").trim()
        } else {
            trimmed
        }
    }

    companion object {
        private const val TAG = "CustomCatalog"
        private const val YANDEX_UA = "Yandex-Music-API"
        private const val KATE_UA =
            "KateMobileAndroid/103.1 lite-545 (Android 13; SDK 33; arm64-v8a; samsung SM-F956B; ru)"
        private const val VK_ANDROID_UA =
            "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)"
        private const val HOME_SHELVES_TTL_MS = 20 * 60 * 1_000L

        private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var lastLoadedAtMs: Long = 0L

        @Volatile
        private var isHomeLoading: Boolean = false

        @Volatile
        private var vkSearchFloodUntilMs: Long = 0L

        @Volatile
        private var searchJob: Job? = null

        @Volatile
        private var lastSearchedQuery: String = ""

        fun bindHomeShelvesBridge(configStore: CustomConfigStore) {
            val provider = CustomCatalogProvider(configStore)
            CustomHomeShelvesBridge.onRefreshRequested = { force ->
                val now = System.currentTimeMillis()
                val stale = CustomHomeShelvesBridge.shelves.isEmpty() || now - lastLoadedAtMs > HOME_SHELVES_TTL_MS
                if ((force || stale) && !isHomeLoading) {
                    isHomeLoading = true
                    CustomHomeShelvesBridge.isLoading = true
                    homeScope.launch {
                        try {
                            val loaded = provider.loadHomeShelves()
                            if (loaded.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    CustomHomeShelvesBridge.shelves = loaded
                                }
                                lastLoadedAtMs = System.currentTimeMillis()
                            }
                        } catch (err: Exception) {
                            Log.w(TAG, "Failed to load RF home shelves: ${err.message}")
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
                if (clean.isNotBlank() && !clean.equals(lastSearchedQuery, ignoreCase = true)) {
                    lastSearchedQuery = clean
                    searchJob?.cancel()
                    CustomSearchBridge.query = clean
                    CustomSearchBridge.yandexSongs = emptyList()
                    CustomSearchBridge.vkSongs = emptyList()
                    CustomSearchBridge.isLoading = true
                    searchJob =
                        homeScope.launch {
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

        private fun yandexApiBase(): String =
            "https://" + listOf("api", "music", "yandex", "net").joinToString(".")

        private fun vkApiBase(): String =
            "https://" + listOf("api", "vk", "com").joinToString(".") + "/method"
    }
}
