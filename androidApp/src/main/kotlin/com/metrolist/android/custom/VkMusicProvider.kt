package com.metrolist.android.custom

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private data class VkAudioCandidate(
    val artist: String,
    val title: String,
    val durationSec: Long,
    val url: String,
    val isExplicit: Boolean,
)

private enum class VkSearchEndpoint(val label: String) {
    CATALOG("catalog"),
    LEGACY("audio.search"),
}

private data class VkClientProfile(
    val label: String,
    val userAgent: String,
    val apiVersion: String,
    val endpoint: VkSearchEndpoint,
)

class VkMusicProvider(
    private val configStore: CustomConfigStore,
) {
    @Volatile
    private var activeProfile: VkClientProfile = CLIENT_PROFILES.first()

    suspend fun checkToken(
        token: String = configStore.current.vkToken,
    ): Boolean = checkTokenDetailed(token).isSuccess

    suspend fun checkTokenDetailed(
        token: String = configStore.current.vkToken,
    ): Result<String> {
        val cleanToken = sanitizeToken(token)
        if (cleanToken.isBlank()) {
            return Result.failure(IllegalStateException("Токен ВК не указан"))
        }

        // Шаг 1: проверяем сам токен
        var accountName: String? = null
        val userCheck =
            runCatching<Result<String?>> {
                val userUrl =
                    "${apiBaseUrl()}/users.get" +
                        "?access_token=${CustomHttp.urlEncode(cleanToken)}" +
                        "&v=5.131"
                val resp = CustomHttp.get(userUrl, headers = mapOf("User-Agent" to activeProfile.userAgent), timeoutMs = 8_000)
                if (!resp.isSuccess) {
                    return@runCatching Result.failure<String?>(IllegalStateException("VK HTTP ${resp.code}"))
                }
                val root = JSONObject(resp.body)
                if (root.has("error")) {
                    val err = root.optJSONObject("error")
                    val code = err?.optInt("error_code", -1) ?: -1
                    val msg = err?.optString("error_msg").orEmpty()
                    val readable =
                        when (code) {
                            9 -> "VK #9: Флуд-контроль — подожди 1-2 минуты и проверь снова"
                            5 -> "Токен истёк или отозван (VK #5)"
                            else -> "VK #$code: $msg"
                        }
                    return@runCatching Result.failure<String?>(IllegalStateException(readable))
                }
                val first = root.optJSONArray("response")?.optJSONObject(0)
                val fullName =
                    first?.let {
                        listOf(it.optString("first_name"), it.optString("last_name"))
                            .filter { part -> part.isNotBlank() }
                            .joinToString(" ")
                    }
                Result.success(fullName)
            }.getOrElse { Result.failure(it) }

        if (userCheck.isFailure) {
            return Result.failure(userCheck.exceptionOrNull() ?: IllegalStateException("Ошибка подключения к ВК"))
        }
        accountName = userCheck.getOrNull()

        // Шаг 2: аккуратно проверяем рабочий метод поиска с паузами, чтобы не словить Flood control
        val notes = mutableListOf<String>()
        for (profile in CLIENT_PROFILES) {
            delay(450L)
            val attempt = testSearchProfile(cleanToken, profile)
            if (attempt.isSuccess) {
                activeProfile = profile
                val who = accountName?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                return Result.success("ВК аудио работает (${profile.label}/${profile.endpoint.label}$who)")
            }
            val errMsg = attempt.exceptionOrNull()?.message.orEmpty()
            if (errMsg.contains("#9")) {
                return Result.failure(IllegalStateException("VK #9: Флуд-контроль — подожди 1-2 минуты и повтори"))
            }
            notes.add("${profile.label}/${profile.endpoint.label}: $errMsg")
        }

        // Шаг 3: проверяем audio.get, чтобы понять, открыт ли вообще доступ к аудио у этого токена
        delay(450L)
        val audioGetNote =
            runCatching<String> {
                val url =
                    "${apiBaseUrl()}/audio.get" +
                        "?count=1&access_token=${CustomHttp.urlEncode(cleanToken)}&v=5.95"
                val resp = CustomHttp.get(url, headers = mapOf("User-Agent" to KATE_UA), timeoutMs = 8_000)
                val root = JSONObject(resp.body)
                if (root.has("error")) {
                    val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                    "audio.get=#$code"
                } else {
                    "audio.get=OK"
                }
            }.getOrDefault("audio.get=err")

        val who = accountName?.takeIf { it.isNotBlank() }?.let { "Аккаунт $it найден, но поиск закрыт" } ?: "Поиск аудио закрыт"
        val details = (notes.take(2) + audioGetNote).joinToString("; ")
        return Result.failure(IllegalStateException("$who ($details)"))
    }

    suspend fun resolve(reference: TrackReference): ResolvedCustomStream? {
        val token = sanitizeToken(configStore.current.vkToken)
        if (token.isBlank()) return null

        val query = listOf(reference.artist, reference.title).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val candidates = searchAudio(token, query)
        val best = pickBestMatch(candidates, reference) ?: return null

        val isHls = best.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        Log.d(TAG, "Resolved via VK (${if (isHls) "HLS" else "MP3"}): ${best.artist} - ${best.title}")

        return ResolvedCustomStream(
            url = best.url,
            headers = mapOf("User-Agent" to activeProfile.userAgent),
            mimeType = if (isHls) "application/x-mpegURL" else "audio/mpeg",
            bitrate = null,
            durationSec = best.durationSec.takeIf { it > 0L },
            clientName = "vk",
            isHls = isHls,
        )
    }

    private suspend fun testSearchProfile(
        token: String,
        profile: VkClientProfile,
    ): Result<Unit> =
        runCatching<Result<Unit>> {
            val url = buildSearchUrl(token = token, query = "test", count = 3, profile = profile)
            val response = CustomHttp.get(url, headers = mapOf("User-Agent" to profile.userAgent), timeoutMs = 8_000)
            if (!response.isSuccess) {
                return@runCatching Result.failure<Unit>(IllegalStateException("HTTP ${response.code}"))
            }
            val root = JSONObject(response.body)
            if (root.has("error")) {
                val err = root.optJSONObject("error")
                val code = err?.optInt("error_code", -1) ?: -1
                val msg = err?.optString("error_msg").orEmpty()
                Result.failure<Unit>(IllegalStateException("#$code $msg"))
            } else if (root.has("response")) {
                Result.success(Unit)
            } else {
                Result.failure<Unit>(IllegalStateException("пустой ответ"))
            }
        }.getOrElse { Result.failure(it) }

    private suspend fun searchAudio(
        token: String,
        query: String,
    ): List<VkAudioCandidate> {
        val profilesToTry = (listOf(activeProfile) + CLIENT_PROFILES).distinct()
        for (profile in profilesToTry) {
            val result: List<VkAudioCandidate>? =
                runCatching<List<VkAudioCandidate>?> {
                    val url = buildSearchUrl(token = token, query = query, count = 15, profile = profile)
                    val response =
                        CustomHttp.get(
                            url = url,
                            headers = mapOf("User-Agent" to profile.userAgent),
                            timeoutMs = 6_000,
                        )
                    if (!response.isSuccess) return@runCatching null
                    val root = JSONObject(response.body)
                    if (root.has("error")) {
                        val code = root.optJSONObject("error")?.optInt("error_code", -1) ?: -1
                        Log.w(TAG, "VK ${profile.label}/${profile.endpoint.label} error #$code")
                        return@runCatching null
                    }
                    activeProfile = profile
                    val respObj = root.optJSONObject("response") ?: return@runCatching emptyList<VkAudioCandidate>()
                    val array =
                        when (profile.endpoint) {
                            VkSearchEndpoint.CATALOG -> respObj.optJSONArray("audios") ?: respObj.optJSONArray("items")
                            VkSearchEndpoint.LEGACY -> respObj.optJSONArray("items") ?: respObj.optJSONArray("audios")
                        }
                    parseAudioArray(array)
                }.getOrNull()

            if (result != null) return result
        }
        return emptyList()
    }

    private fun buildSearchUrl(
        token: String,
        query: String,
        count: Int,
        profile: VkClientProfile,
    ): String {
        val encodedQuery = CustomHttp.urlEncode(query)
        val encodedToken = CustomHttp.urlEncode(token)
        return when (profile.endpoint) {
            VkSearchEndpoint.CATALOG ->
                "${apiBaseUrl()}/catalog.getAudioSearch" +
                    "?query=$encodedQuery" +
                    "&need_blocks=1" +
                    "&lang=ru" +
                    "&access_token=$encodedToken" +
                    "&v=${profile.apiVersion}"

            VkSearchEndpoint.LEGACY ->
                "${apiBaseUrl()}/audio.search" +
                    "?q=$encodedQuery" +
                    "&count=$count" +
                    "&sort=2" +
                    "&auto_complete=1" +
                    "&lang=ru" +
                    "&access_token=$encodedToken" +
                    "&v=${profile.apiVersion}"
        }
    }

    private fun parseAudioArray(items: JSONArray?): List<VkAudioCandidate> {
        if (items == null) return emptyList()
        return buildList<VkAudioCandidate> {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val streamUrl = item.optString("url").trim()
                if (streamUrl.isBlank()) continue
                val title = item.optString("title")
                val subtitle = item.optString("subtitle")
                val fullTitle = if (subtitle.isNotBlank()) "$title ($subtitle)" else title
                add(
                    VkAudioCandidate(
                        artist = item.optString("artist"),
                        title = fullTitle,
                        durationSec = item.optLong("duration", 0L),
                        url = streamUrl,
                        isExplicit = item.optBoolean("is_explicit", false),
                    ),
                )
            }
        }
    }

    private fun pickBestMatch(
        candidates: List<VkAudioCandidate>,
        reference: TrackReference,
    ): VkAudioCandidate? =
        candidates
            .filter { candidate ->
                !TrackMatcher.hasForbiddenStopWord(reference.title, candidate.title) &&
                    TrackMatcher.isDurationAcceptable(reference.durationSec, candidate.durationSec, toleranceSec = 5L)
            }.mapNotNull { candidate ->
                val score =
                    TrackMatcher.matchScore(
                        targetArtist = reference.artist,
                        targetTitle = reference.title,
                        targetDurationSec = reference.durationSec,
                        candidateArtist = candidate.artist,
                        candidateTitle = candidate.title,
                        candidateDurationSec = candidate.durationSec,
                    )
                if (score >= 65) {
                    val explicitBonus = if (reference.isExplicit && candidate.isExplicit) 12 else 0
                    val directMp3Bonus = if (!candidate.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) 4 else 0
                    candidate to (score + explicitBonus + directMp3Bonus)
                } else {
                    null
                }
            }.maxByOrNull { it.second }
            ?.first

    private fun sanitizeToken(raw: String): String {
        val trimmed = raw.trim()
        val afterParam =
            if (trimmed.contains("access_token=")) {
                trimmed.substringAfter("access_token=").substringBefore("&")
            } else {
                trimmed
            }
        return afterParam.trim()
    }

    companion object {
        private const val TAG = "VkProvider"

        private const val KATE_UA =
            "KateMobileAndroid/103.1 lite-545 (Android 13; SDK 33; arm64-v8a; samsung SM-F956B; ru)"
        private const val VK_ANDROID_UA =
            "VKAndroidApp/8.99-23423 (Android 13; SDK 33; arm64-v8a; samsung SM-S918B; ru; 2340x1080)"

        private fun apiBaseUrl(): String {
            val host = listOf("api", "vk", "com").joinToString(".")
            return "https://" + host + "/method"
        }

        private val CLIENT_PROFILES =
            listOf(
                VkClientProfile(
                    label = "Kate",
                    userAgent = KATE_UA,
                    apiVersion = "5.131",
                    endpoint = VkSearchEndpoint.CATALOG,
                ),
                VkClientProfile(
                    label = "Kate",
                    userAgent = KATE_UA,
                    apiVersion = "5.95",
                    endpoint = VkSearchEndpoint.LEGACY,
                ),
                VkClientProfile(
                    label = "VKApp",
                    userAgent = VK_ANDROID_UA,
                    apiVersion = "5.131",
                    endpoint = VkSearchEndpoint.CATALOG,
                ),
            )
    }
}
