@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.metrolist.android.custom

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val VK_OAUTH_URL =
    "https://oauth.vk.com/authorize?client_id=2685278&scope=audio,offline&redirect_uri=https://oauth.vk.com/blank.html&display=mobile&v=5.131&response_type=token"
private const val YANDEX_OAUTH_URL =
    "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d"
private const val GEMINI_AI_STUDIO_URL = "https://aistudio.google.com/apikey"

private const val MASCOT_BLACK_KEY_AGSL = """
uniform shader content;
uniform float2 iResolution;

float maxRgb(float2 p) {
    half4 s = content.eval(p);
    return float(max(s.r, max(s.g, s.b)));
}

half4 main(float2 fragCoord) {
    half4 c = content.eval(fragCoord);
    float m = float(max(c.r, max(c.g, c.b)));
    float2 uv = fragCoord / max(iResolution, float2(1.0));

    float nL = maxRgb(fragCoord + float2(-4.0, 0.0));
    float nR = maxRgb(fragCoord + float2( 4.0, 0.0));
    float nU = maxRgb(fragCoord + float2( 0.0,-4.0));
    float nD = maxRgb(fragCoord + float2( 0.0, 4.0));

    bool surroundedHoriz = (nL > 0.075 && nR > 0.075);
    bool surroundedVert  = (nU > 0.075 && nD > 0.075);

    float alpha = 1.0;
    if (!surroundedHoriz && !surroundedVert) {
        alpha = smoothstep(0.022, 0.056, m);
    }

    float bottomFade = smoothstep(1.0, 0.90, uv.y);
    float sideFade = smoothstep(0.0, 0.05, uv.x) * smoothstep(1.0, 0.95, uv.x);
    alpha *= bottomFade * sideFade;

    return half4(c.rgb * half(alpha), half(alpha));
}
"""

private enum class OAuthProvider(
    val title: String,
    val authUrl: String,
) {
    VK("Вход в ВКонтакте", VK_OAUTH_URL),
    YANDEX("Вход в Яндекс.Музыку", YANDEX_OAUTH_URL),
}

/**
 * Хелпер тихого фонового автообновления токена ВК при ошибке #5
 * через сохранённые куки встроенного WebView.
 */
object CustomAuthWebViewHelper {
    @Volatile
    private var appContext: Context? = null

    fun registerContext(context: Context) {
        appContext = context.applicationContext
        if (VkMusicProvider.onTokenExpiredRefreshHook == null) {
            VkMusicProvider.onTokenExpiredRefreshHook = { silentRefreshVkToken() }
        }
    }

    fun extractAccessToken(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val marker = "access_token="
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val token = url.substring(idx + marker.length).substringBefore('&').substringBefore('#').trim()
        return token.takeIf { it.length >= 20 }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun silentRefreshVkToken(): String? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    val webView =
                        runCatching { WebView(ctx) }.getOrNull() ?: run {
                            if (cont.isActive) cont.resume(null)
                            return@suspendCancellableCoroutine
                        }
                    var finished = false
                    fun finishWith(token: String?) {
                        if (finished) return
                        finished = true
                        runCatching {
                            webView.stopLoading()
                            webView.destroy()
                        }
                        if (cont.isActive) cont.resume(token)
                    }

                    cont.invokeOnCancellation { finishWith(null) }

                    CookieManager.getInstance().setAcceptCookie(true)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                    }
                    webView.webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val u = request?.url?.toString()
                                val tok = extractAccessToken(u)
                                if (tok != null) {
                                    finishWith(tok)
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(
                                view: WebView?,
                                url: String?,
                            ) {
                                val tok = extractAccessToken(url)
                                if (tok != null) {
                                    finishWith(tok)
                                }
                            }
                        }
                    webView.loadUrl(VK_OAUTH_URL)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ServiceOAuthWebViewDialog(
    provider: OAuthProvider,
    onTokenCaptured: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentUrl by remember { mutableStateOf(provider.authUrl) }
    var delivered by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = provider.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Войди в аккаунт — токен подхватится автоматически",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                }
                HorizontalDivider()
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                            }
                            webViewClient =
                                object : WebViewClient() {
                                    private fun checkUrl(url: String?): Boolean {
                                        if (url.isNullOrBlank()) return false
                                        currentUrl = url
                                        val token = CustomAuthWebViewHelper.extractAccessToken(url)
                                        if (token != null && !delivered) {
                                            delivered = true
                                            CookieManager.getInstance().flush()
                                            onTokenCaptured(token)
                                            return true
                                        }
                                        return false
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = checkUrl(request?.url?.toString())

                                    override fun onPageFinished(
                                        view: WebView?,
                                        url: String?,
                                    ) {
                                        checkUrl(url)
                                    }
                                }
                            loadUrl(provider.authUrl)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private fun pickFallbackMascotPhrase(
    mood: WaveMood,
    lastPhrase: String?,
): String {
    val pool =
        when (mood) {
            WaveMood.DRIVE ->
                listOf(
                    "О, вот это уже пошёл нормальный темп!",
                    "Добавляй громкости, этот трек тащит.",
                    "Погнали — ритм прям то что надо.",
                    "Вот под такой вайб не заскучаешь.",
                    "Хороший разгон, держим волну!",
                )

            WaveMood.NIGHT ->
                listOf(
                    "Ночной вайб — мой любимый режим.",
                    "Идеально под фонари и пустую дорогу.",
                    "Атмосфера на максимуме, слушаем дальше.",
                    "Тёмный, глубокий звук — прямо в точку.",
                    "Тишина вокруг, и только этот бит в наушниках.",
                )

            WaveMood.HARD_BASS ->
                listOf(
                    "Осторожнее, саб сейчас всё вокруг потрясёт!",
                    "Вот это жир — бас прямо в грудь.",
                    "808 отрабатывает на все сто.",
                    "Наушники держатся? Качает знатно!",
                    "Плотнейший звук, не переключай.",
                )

            WaveMood.CHILL ->
                listOf(
                    "Расслабляемся и ловим спокойный поток.",
                    "Мягко заходит, никуда не спешим.",
                    "Уютный трек — можно просто выдохнуть.",
                    "Лёгкий вайб, идеальный фон.",
                    "Плавно идём дальше по волне.",
                )

            WaveMood.SAD ->
                listOf(
                    "Чувствуется настроение… побуду рядом.",
                    "Сильный трек, прямо за душу берёт.",
                    "Иногда именно такая музыка и нужна.",
                    "Тихо слушаем, без лишних слов.",
                    "Глубокая подача, цепляет.",
                )

            WaveMood.ANY ->
                listOf(
                    "О, этот трек хорошо зашёл!",
                    "Ловлю ритм — дальше будет не хуже.",
                    "Кайфовое звучание, оставляем в потоке.",
                    "Вот за такое я и люблю твою волну.",
                    "Настроена на твой вкус — слушаем!",
                    "Прямо в настроение попало.",
                    "Классный переход, держим этот вайб.",
                )
        }
    val candidates = pool.filterNot { it == lastPhrase }
    return (candidates.ifEmpty { pool }).random()
}

@Composable
fun WaveHomeScreen(
    configStore: CustomConfigStore,
    aiWaveEngine: AiWaveEngine,
    geminiClient: GeminiClient,
    yandexProvider: YandexMusicProvider,
    vkProvider: VkMusicProvider,
    playerConnection: PlayerConnection?,
    bottomInset: Dp,
    onExpandPlayer: () -> Unit,
    isScreenActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val isGenerating by aiWaveEngine.isGeneratingWave.collectAsState()
    val waveStatus by aiWaveEngine.statusText.collectAsState()
    val diagnosticsRevision by ResolverDiagnostics.revision.collectAsState()

    DisposableEffect(context) {
        CustomAuthWebViewHelper.registerContext(context)
        onDispose { }
    }

    val phraseBank = remember(context, geminiClient) { MascotPhraseBank(context.applicationContext, geminiClient) }

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val queueTitle by playerConnection?.queueTitle?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

    val isPlayingWave = queueTitle?.startsWith("Моя волна") == true
    val isOutsideWave = currentSong != null && !isPlayingWave

    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)
    val isCurrentLiked = currentSong?.id?.let { it in likedIds } == true

    var mascotPhrase by remember { mutableStateOf<String?>(null) }
    var lastPhraseSongId by remember { mutableStateOf<String?>(null) }
    var showWaveSettingsSheet by remember { mutableStateOf(false) }
    var showEngineSettingsSheet by remember { mutableStateOf(false) }
    var showSourcePickerSheet by remember { mutableStateOf(false) }

    // Паузим видео-маскот, когда экран скрыт (другая вкладка, открытый плеер или шторки)
    val isMascotVideoActive =
        isScreenActive && !showWaveSettingsSheet && !showEngineSettingsSheet && !showSourcePickerSheet

    LaunchedEffect(currentSong?.id) {
        val song = currentSong
        if (song == null) {
            mascotPhrase = null
            lastPhraseSongId = null
            return@LaunchedEffect
        }
        if (song.id == lastPhraseSongId && !mascotPhrase.isNullOrBlank()) {
            return@LaunchedEffect
        }
        val previousPhrase = mascotPhrase
        lastPhraseSongId = song.id
        mascotPhrase = null
        delay(650L)
        if (currentSong?.id != song.id) return@LaunchedEffect

        val artist = song.artists.joinToString(", ") { it.name }.trim()
        val bankPhrase =
            runCatching {
                phraseBank.nextPhraseForTrack(
                    artist = artist,
                    title = song.title,
                    moodKey = cfg.waveMood.name,
                    moodLabel = cfg.waveMood.label,
                    hasGeminiKey = cfg.geminiApiKey.isNotBlank(),
                )
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        if (currentSong?.id == song.id) {
            mascotPhrase = bankPhrase ?: pickFallbackMascotPhrase(cfg.waveMood, previousPhrase)
        }
    }

    fun launchFreshWave() {
        val conn = playerConnection ?: return
        val starter = aiWaveEngine.buildInstantStarterSongs(excludeIds = setOfNotNull(currentSong?.id))
        val startedInstant =
            if (starter.isNotEmpty()) {
                conn.playQueue(
                    songs = starter,
                    queueTitle = "Моя волна",
                    finite = false,
                    forceStartPlaying = true,
                )
                true
            } else {
                false
            }

        scope.launch {
            aiWaveEngine
                .generateWaveSongs(backgroundLabel = startedInstant)
                .onSuccess { fresh ->
                    if (startedInstant && conn.queueTitle.value?.startsWith("Моя волна") == true) {
                        conn.replaceUpcomingWaveQueue(fresh)
                    } else {
                        conn.playQueue(
                            songs = fresh,
                            queueTitle = "Моя волна",
                            finite = false,
                            forceStartPlaying = true,
                        )
                    }
                }.onFailure { err ->
                    if (!startedInstant) {
                        Toast.makeText(context, err.message ?: "Не удалось запустить волну", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    fun startOrToggleWave() {
        if (currentSong != null && playerConnection != null) {
            playerConnection.togglePlayPause()
            return
        }
        launchFreshWave()
    }

    val activeBubbleText =
        when {
            !waveStatus.isNullOrBlank() -> waveStatus
            isPlaying && !mascotPhrase.isNullOrBlank() -> mascotPhrase
            else -> null
        }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val qualityBadge =
        remember(diagnosticsRevision, currentSong?.id, playbackResource?.clientName, playbackResource?.bitrate, playbackResource?.mimeType) {
            ResolverDiagnostics.qualityLabelFor(
                videoId = currentSong?.id,
                clientName = playbackResource?.clientName,
                bitrate = playbackResource?.bitrate,
                mimeType = playbackResource?.mimeType,
            )
        }

    val activeContextLabel =
        remember(queueTitle, currentSong?.title) {
            queueTitle?.takeIf { it.isNotBlank() } ?: currentSong?.title.orEmpty()
        }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = statusBarTop + 8.dp, bottom = bottomInset + 12.dp, start = 16.dp, end = 16.dp),
    ) {
        val isWideLayout = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Моя волна",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!qualityBadge.isNullOrBlank()) {
                        NativeSourceBadge(
                            label = qualityBadge,
                            onClick = { if (currentSong != null) showSourcePickerSheet = true },
                        )
                    }
                }

                IconButton(onClick = { showEngineSettingsSheet = true }) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Settings,
                        contentDescription = "Настройки движка",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = isOutsideWave && activeContextLabel.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ActiveExternalContextPill(
                        label = activeContextLabel,
                        onOpenContext = onExpandPlayer,
                        onResetToWave = ::launchFreshWave,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val filterControlsBlock: @Composable () -> Unit = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isOutsideWave) {
                        ReturnToWavePillButton(
                            enabled = !isGenerating,
                            onClick = ::launchFreshWave,
                        )
                    }
                    WaveFilterPillButton(
                        label = cfg.waveFilterSummary(),
                        onClick = { showWaveSettingsSheet = true },
                    )
                }
            }

            val playerBlock: @Composable () -> Unit = {
                WaveBottomStatusAndPlayer(
                    currentSong = currentSong,
                    isLiked = isCurrentLiked,
                    onToggleLike = { song ->
                        val willLike = !isCurrentLiked
                        val artist = song.artists.joinToString(", ") { it.name }.trim()
                        SongLikeState.toggleLike(song)
                        if (willLike) {
                            configStore.likeTrack(
                                artist = artist,
                                title = song.title,
                                songId = song.id,
                                thumbnail = song.thumbnail?.takeUnless { it == CustomMascotAssets.fallbackCoverUri },
                            )
                            aiWaveEngine.recordLike(song)
                            if (cfg.cacheWaveAndDownloadLikes) {
                                CustomMediaCacheManager.downloadLikedTrack(song, playbackResource)
                            }
                        } else {
                            configStore.removeLikedTrackBackup(songId = song.id, artist = artist, title = song.title)
                            CustomMediaCacheManager.removeLikedDownload(song.id)
                        }
                    },
                    onDislikeClick = { song ->
                        aiWaveEngine.recordDislike(song)
                        Toast.makeText(context, "Трек скрыт из волны навсегда", Toast.LENGTH_SHORT).show()
                        playerConnection?.skipToNext()
                    },
                    onPreviousClick = {
                        playerConnection?.skipToPrevious()
                    },
                    onNextClick = {
                        currentSong?.let { aiWaveEngine.recordSessionSkip(it) }
                        playerConnection?.skipToNext()
                    },
                    onOpenFullPlayer = onExpandPlayer,
                )
            }

            if (isWideLayout) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedWaveMascotStage(
                            isPlaying = isPlaying,
                            isGenerating = isGenerating,
                            showMascot = cfg.mascotEnabled,
                            isStageVisible = isMascotVideoActive,
                            speechBubbleText = activeBubbleText,
                            onSpeechBubbleClick = {
                                if (waveStatus.isNullOrBlank() && isPlaying) {
                                    mascotPhrase = pickFallbackMascotPhrase(cfg.waveMood, mascotPhrase)
                                }
                            },
                            stageSize = 280.dp,
                            onClick = ::startOrToggleWave,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1.1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        filterControlsBlock()
                        playerBlock()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedWaveMascotStage(
                        isPlaying = isPlaying,
                        isGenerating = isGenerating,
                        showMascot = cfg.mascotEnabled,
                        isStageVisible = isMascotVideoActive,
                        speechBubbleText = activeBubbleText,
                        onSpeechBubbleClick = {
                            if (waveStatus.isNullOrBlank() && isPlaying) {
                                mascotPhrase = pickFallbackMascotPhrase(cfg.waveMood, mascotPhrase)
                            }
                        },
                        stageSize = 290.dp,
                        onClick = ::startOrToggleWave,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    filterControlsBlock()

                    playerBlock()
                }
            }
        }
    }

    if (showWaveSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showWaveSettingsSheet = false },
            sheetState = sheetState,
        ) {
            WaveFilterSheetContent(
                config = cfg,
                isGenerating = isGenerating,
                onUpdate = { transform -> configStore.update(transform) },
                onRefreshWave = {
                    showWaveSettingsSheet = false
                    launchFreshWave()
                },
            )
        }
    }

    if (showSourcePickerSheet && currentSong != null) {
        val song = currentSong!!
        val artist = song.artists.joinToString(", ") { it.name }.trim()
        val currentOverride = configStore.getTrackSourceOverride(song.id, artist, song.title)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSourcePickerSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Источник для этого трека",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (artist.isNotBlank()) "$artist — ${song.title}" else song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TrackSourcePreference.entries.forEach { pref ->
                        FilterChip(
                            selected = currentOverride == pref,
                            onClick = {
                                val toastText =
                                    HybridStreamResolver.selectTrackSourceFromMenu(
                                        song = song,
                                        prefName = pref.name,
                                        activeClientName = playbackResource?.clientName,
                                    )
                                showSourcePickerSheet = false
                                playerConnection?.reloadCurrentSongForSourceSwitch()
                                Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                            },
                            label = { Text(pref.label) },
                        )
                    }
                }
                Text(
                    text = "Повторный тап по VK навсегда исключает кривой/питчеванный файл и ищет чистую версию.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showEngineSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEngineSettingsSheet = false },
            sheetState = sheetState,
        ) {
            CustomEngineSettingsContent(
                configStore = configStore,
                yandexProvider = yandexProvider,
                vkProvider = vkProvider,
                geminiClient = geminiClient,
                onRefreshWave = {
                    showEngineSettingsSheet = false
                    launchFreshWave()
                },
            )
        }
    }
}

@Composable
private fun MascotSpeechBubble(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(18.dp),
            color = bubbleColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 120.dp, max = 300.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Canvas(modifier = Modifier.size(width = 16.dp, height = 8.dp)) {
            val fillPath =
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
            drawPath(path = fillPath, color = bubbleColor)
            val strokePx = 1.dp.toPx()
            drawLine(
                color = borderColor,
                start = Offset(0f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = strokePx,
            )
            drawLine(
                color = borderColor,
                start = Offset(size.width, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = strokePx,
            )
        }
    }
}

@Composable
private fun ActiveExternalContextPill(
    label: String,
    onOpenContext: () -> Unit,
    onResetToWave: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp).widthIn(max = 320.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onOpenContext)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Surface(
                onClick = onResetToWave,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val crossColor = MaterialTheme.colorScheme.onPrimary
                    Canvas(modifier = Modifier.size(10.dp)) {
                        val strokePx = 1.8.dp.toPx()
                        drawLine(
                            color = crossColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = crossColor,
                            start = Offset(size.width, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReturnToWavePillButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = MetrolistIcons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
            Text(
                text = "Вернуться к волне",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LiveMascotVideoLayer(
    isPlaying: Boolean,
    isStageVisible: Boolean,
    bassEnergy: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val sleepRes = CustomMascotAssets.sleepVideoResId
    val wakeRes = CustomMascotAssets.wakeVideoResId
    val awakeRes = CustomMascotAssets.awakeVideoResId

    fun rawUri(resId: Int): Uri = Uri.parse("android.resource://${context.packageName}/$resId")

    val exoPlayer =
        remember(context) {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                playWhenReady = isStageVisible
            }
        }

    var isLifecycleStarted by remember { mutableStateOf(true) }
    var wasPlaying by remember { mutableStateOf<Boolean?>(null) }
    var isInWakeTransition by remember { mutableStateOf(false) }
    var videoWidth by remember { mutableIntStateOf(16) }
    var videoHeight by remember { mutableIntStateOf(9) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var lastAppliedSpeed by remember { mutableFloatStateOf(1f) }
    var lastSpeedUpdateMs by remember { mutableLongStateOf(0L) }

    val shouldRenderVideo = isStageVisible && isLifecycleStarted

    fun applyCenterCropTransform(
        tv: TextureView,
        vw: Int,
        vh: Int,
    ) {
        val viewW = tv.width
        val viewH = tv.height
        if (viewW <= 0 || viewH <= 0 || vw <= 0 || vh <= 0) return
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        val videoAspect = vw.toFloat() / vh.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        }
        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        tv.setTransform(matrix)
    }

    fun applyKeyShader(tv: TextureView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val w = tv.width
        val h = tv.height
        if (w <= 0 || h <= 0) return
        runCatching {
            val shader = RuntimeShader(MASCOT_BLACK_KEY_AGSL)
            shader.setFloatUniform("iResolution", w.toFloat(), h.toFloat())
            tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }
    }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                        textureViewRef?.let { applyCenterCropTransform(it, videoSize.width, videoSize.height) }
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (isInWakeTransition && exoPlayer.currentMediaItemIndex > 0) {
                        isInWakeTransition = false
                        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    }
                }
            }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            textureViewRef?.let { exoPlayer.clearVideoTextureView(it) }
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> isLifecycleStarted = true
                    Lifecycle.Event.ON_STOP -> isLifecycleStarted = false
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(shouldRenderVideo) {
        exoPlayer.playWhenReady = shouldRenderVideo
    }

    LaunchedEffect(isPlaying, sleepRes, wakeRes, awakeRes) {
        val previous = wasPlaying
        wasPlaying = isPlaying

        if (isPlaying) {
            val loopTarget = awakeRes.takeIf { it != 0 } ?: wakeRes.takeIf { it != 0 } ?: sleepRes
            if (previous == false && wakeRes != 0 && awakeRes != 0) {
                isInWakeTransition = true
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                exoPlayer.setMediaItems(
                    listOf(
                        MediaItem.fromUri(rawUri(wakeRes)),
                        MediaItem.fromUri(rawUri(awakeRes)),
                    ),
                    true,
                )
            } else if (loopTarget != 0) {
                isInWakeTransition = false
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer.setMediaItem(MediaItem.fromUri(rawUri(loopTarget)))
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = shouldRenderVideo
        } else {
            val sleepTarget = sleepRes.takeIf { it != 0 } ?: awakeRes
            isInWakeTransition = false
            exoPlayer.setPlaybackSpeed(1f)
            lastAppliedSpeed = 1f
            if (sleepTarget != 0) {
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer.setMediaItem(MediaItem.fromUri(rawUri(sleepTarget)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = shouldRenderVideo
            }
        }
    }

    LaunchedEffect(shouldRenderVideo, isPlaying, isInWakeTransition, bassEnergy) {
        if (!shouldRenderVideo || !isPlaying || isInWakeTransition) {
            if (abs(lastAppliedSpeed - 1f) > 0.02f) {
                exoPlayer.setPlaybackSpeed(1f)
                lastAppliedSpeed = 1f
            }
            return@LaunchedEffect
        }
        val now = System.currentTimeMillis()
        if (now - lastSpeedUpdateMs < 220L) return@LaunchedEffect
        val targetSpeed = (0.90f + bassEnergy.coerceIn(0f, 1f) * 0.30f).coerceIn(0.88f, 1.22f)
        if (abs(targetSpeed - lastAppliedSpeed) >= 0.04f) {
            exoPlayer.setPlaybackSpeed(targetSpeed)
            lastAppliedSpeed = targetSpeed
            lastSpeedUpdateMs = now
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                textureViewRef = this
                exoPlayer.setVideoTextureView(this)
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    applyCenterCropTransform(this, videoWidth, videoHeight)
                    applyKeyShader(this)
                }
            }
        },
        update = { tv ->
            textureViewRef = tv
            applyCenterCropTransform(tv, videoWidth, videoHeight)
            applyKeyShader(tv)
        },
        modifier = modifier.clipToBounds(),
    )
}

@Composable
private fun AnimatedWaveMascotStage(
    isPlaying: Boolean,
    isGenerating: Boolean,
    showMascot: Boolean,
    isStageVisible: Boolean,
    speechBubbleText: String?,
    onSpeechBubbleClick: () -> Unit,
    stageSize: Dp,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer

    val bassEnergy = remember { mutableFloatStateOf(0f) }
    val beatKick = remember { mutableFloatStateOf(0f) }
    val eqBars = remember { FloatArray(28) }
    val eqTick = remember { mutableLongStateOf(0L) }
    val kickCounter = remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying, isStageVisible) {
        if (!isPlaying || !isStageVisible) {
            while (isActive && isStageVisible) {
                bassEnergy.floatValue = (bassEnergy.floatValue * 0.85f).coerceAtLeast(0f)
                beatKick.floatValue = (beatKick.floatValue * 0.80f).coerceAtLeast(0f)
                var maxBar = 0f
                for (i in eqBars.indices) {
                    eqBars[i] = (eqBars[i] * 0.82f).coerceAtLeast(0f)
                    if (eqBars[i] > maxBar) maxBar = eqBars[i]
                }
                eqTick.longValue += 1L
                if (bassEnergy.floatValue < 0.01f && beatKick.floatValue < 0.01f && maxBar < 0.01f) break
                delay(33L)
            }
            return@LaunchedEffect
        }
        com.metrolist.shared.equalizer.AudioSpectrumMeter.addConsumer()
        var avgBass = 0.14f
        var lastKickAt = 0L
        try {
            while (isActive) {
                val levels = com.metrolist.shared.equalizer.AudioSpectrumMeter.levels()
                val rawBass =
                    (
                        (levels.getOrElse(0) { 0f } * 0.45f) +
                            (levels.getOrElse(1) { 0f } * 0.35f) +
                            (levels.getOrElse(2) { 0f } * 0.20f)
                    ).coerceIn(0f, 1f)
                bassEnergy.floatValue =
                    if (rawBass > bassEnergy.floatValue) rawBass else (bassEnergy.floatValue * 0.76f + rawBass * 0.24f)

                val delta = (rawBass - avgBass).coerceAtLeast(0f)
                avgBass = avgBass * 0.88f + rawBass * 0.12f
                val kickNow = (delta * 3.4f).coerceIn(0f, 1f)
                val nowMs = System.currentTimeMillis()
                if (kickNow > 0.35f && nowMs - lastKickAt > 260L) {
                    kickCounter.intValue += 1
                    lastKickAt = nowMs
                }
                beatKick.floatValue = if (kickNow > beatKick.floatValue) kickNow else (beatKick.floatValue * 0.74f)

                val srcSize = levels.size.coerceAtLeast(1)
                for (i in eqBars.indices) {
                    val sampleIdx = (i * srcSize / eqBars.size).coerceIn(0, srcSize - 1)
                    val target = levels.getOrElse(sampleIdx) { rawBass }.coerceIn(0f, 1f)
                    eqBars[i] = if (target > eqBars[i]) target else (eqBars[i] * 0.72f + target * 0.28f)
                }
                eqTick.longValue += 1L
                delay(33L)
            }
        } finally {
            com.metrolist.shared.equalizer.AudioSpectrumMeter.removeConsumer()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "mascotMotion")
    val breathPhase =
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "breathPhase",
        )
    val headSwayPhase =
        infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 880, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "headSwayPhase",
        )
    val zzzPhase =
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "zzzPhase",
        )

    val useVideoMascot = showMascot && CustomMascotAssets.hasVideoMascot
    val hasDedicatedSprites = CustomMascotAssets.hasDedicatedSprites
    val mascotResId =
        if (!showMascot || useVideoMascot) {
            0
        } else if (!isPlaying) {
            CustomMascotAssets.sleepResId.takeIf { it != 0 } ?: CustomMascotAssets.drawableResId
        } else {
            val vibe = CustomMascotAssets.vibeResId
            if (vibe != 0 && beatKick.floatValue > 0.45f) {
                vibe
            } else {
                CustomMascotAssets.awakeResId.takeIf { it != 0 } ?: CustomMascotAssets.drawableResId
            }
        }

    val hasAnyMascot = useVideoMascot || mascotResId != 0
    val tapInteraction = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.height(54.dp),
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !speechBubbleText.isNullOrBlank(),
                enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.9f, animationSpec = tween(260)),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.94f, animationSpec = tween(180)),
            ) {
                MascotSpeechBubble(
                    text = speechBubbleText.orEmpty(),
                    onClick = onSpeechBubbleClick,
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(width = stageSize * 1.08f, height = stageSize * 0.96f)
                    .clickable(
                        interactionSource = tapInteraction,
                        indication = null,
                        onClick = onClick,
                    ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val _tick = eqTick.longValue
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f
                val pulse = bassEnergy.floatValue.coerceIn(0f, 1f)
                val kick = beatKick.floatValue.coerceIn(0f, 1f)
                val breath = breathPhase.value
                val energy = if (isPlaying) pulse else breath * 0.25f

                val outerR = maxR * (0.86f + energy * 0.14f + kick * 0.05f)
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    primaryColor.copy(alpha = (0.30f + energy * 0.28f + kick * 0.12f).coerceIn(0f, 0.70f)),
                                    tertiaryColor.copy(alpha = (0.14f + energy * 0.14f).coerceIn(0f, 0.38f)),
                                    Color.Transparent,
                                ),
                            center = center,
                            radius = outerR,
                        ),
                    radius = outerR,
                    center = center,
                )

                val barCount = eqBars.size
                val innerRingR = maxR * (0.62f + kick * 0.03f)
                val maxBarLen = maxR * 0.24f
                val barStroke = 3.dp.toPx()
                for (i in 0 until barCount) {
                    val angle = (i.toFloat() / barCount) * (2.0 * PI) - PI / 2.0
                    val barAmp =
                        if (isPlaying) {
                            (eqBars[i] * 0.85f + pulse * 0.22f).coerceIn(0.06f, 1f)
                        } else {
                            0.08f + 0.06f * sin(breath * PI + i * 0.5).toFloat().coerceAtLeast(0f)
                        }
                    val startR = innerRingR
                    val endR = innerRingR + maxBarLen * barAmp
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    val barColor =
                        if (i % 2 == 0) {
                            primaryColor.copy(alpha = (0.28f + barAmp * 0.55f).coerceIn(0.2f, 0.85f))
                        } else {
                            tertiaryColor.copy(alpha = (0.22f + barAmp * 0.48f).coerceIn(0.18f, 0.75f))
                        }
                    drawLine(
                        color = barColor,
                        start = Offset(center.x + cosA * startR, center.y + sinA * startR),
                        end = Offset(center.x + cosA * endR, center.y + sinA * endR),
                        strokeWidth = barStroke,
                        cap = StrokeCap.Round,
                    )
                }

                val starAngles = floatArrayOf(0.4f, 1.35f, 2.25f, 3.55f, 4.45f, 5.45f)
                val starRadii = floatArrayOf(0.84f, 0.90f, 0.82f, 0.88f, 0.83f, 0.91f)
                for (idx in starAngles.indices) {
                    val a = starAngles[idx] + breath * 0.08f
                    val r = maxR * starRadii[idx]
                    val sx = center.x + cos(a) * r
                    val sy = center.y + sin(a) * r
                    val twinkle = (0.25f + 0.55f * ((sin(breath * PI * 2.0 + idx).toFloat() + 1f) * 0.5f))
                    drawCircle(
                        color = if (idx % 2 == 0) primaryColor.copy(alpha = twinkle) else tertiaryColor.copy(alpha = twinkle),
                        radius = (1.6.dp + (idx % 2).dp).toPx() * (1f + kick * 0.4f),
                        center = Offset(sx, sy),
                    )
                }
            }

            if (useVideoMascot) {
                Box(
                    contentAlignment = Alignment.BottomCenter,
                    modifier =
                        Modifier
                            .size(width = stageSize * 0.98f, height = stageSize * 0.84f)
                            .graphicsLayer {
                                val pulse = bassEnergy.floatValue.coerceIn(0f, 1f)
                                val kick = beatKick.floatValue.coerceIn(0f, 1f)
                                if (isPlaying && isStageVisible) {
                                    translationY = (kick * 7.dp.toPx()) + (pulse * 1.5.dp.toPx())
                                    scaleX = 1f + kick * 0.025f
                                    scaleY = 1f - kick * 0.022f
                                } else {
                                    translationY = 0f
                                    scaleX = 1f
                                    scaleY = 1f
                                }
                            },
                ) {
                    LiveMascotVideoLayer(
                        isPlaying = isPlaying,
                        isStageVisible = isStageVisible,
                        bassEnergy = bassEnergy.floatValue,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else if (mascotResId != 0) {
                val mascotSize = stageSize * 0.78f
                val spriteModifier =
                    if (hasDedicatedSprites) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush =
                                        Brush.radialGradient(
                                            0.0f to Color.Black,
                                            0.58f to Color.Black,
                                            0.84f to Color.Black.copy(alpha = 0.5f),
                                            1.0f to Color.Transparent,
                                            center = Offset(size.width / 2f, size.height / 2f),
                                            radius = size.minDimension / 2f,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(mascotSize)
                            .graphicsLayer {
                                val pulse = bassEnergy.floatValue.coerceIn(0f, 1f)
                                val kick = beatKick.floatValue.coerceIn(0f, 1f)
                                val sway = headSwayPhase.value
                                val breath = breathPhase.value

                                if (isPlaying) {
                                    val beatDir = if (kickCounter.intValue % 2 == 0) 1f else -1f
                                    rotationZ = sway * (2.6f + pulse * 3.8f) + beatDir * kick * 2.2f
                                    translationY = (kick * 9.dp.toPx()) + (pulse * 2.dp.toPx())
                                    scaleX = 1f + kick * 0.045f + pulse * 0.02f
                                    scaleY = 1f - kick * 0.035f + pulse * 0.025f
                                    alpha = 1f
                                } else {
                                    rotationZ = -2.2f + breath * 1.4f
                                    translationY = 4.dp.toPx() - breath * 3.dp.toPx()
                                    scaleX = 1f + breath * 0.015f
                                    scaleY = 1f + breath * 0.028f
                                    alpha = 0.84f
                                }
                            },
                ) {
                    Image(
                        painter = painterResource(id = mascotResId),
                        contentDescription = if (isPlaying) "Пауза" else "Разбудить и включить волну",
                        contentScale = if (hasDedicatedSprites) ContentScale.Fit else ContentScale.Crop,
                        modifier = spriteModifier,
                    )
                }
            } else {
                Surface(
                    onClick = onClick,
                    shape = CircleShape,
                    color = containerColor.copy(alpha = 0.92f),
                    contentColor = onContainerColor,
                    tonalElevation = 6.dp,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.5.dp, primaryColor.copy(alpha = 0.45f)),
                    modifier =
                        Modifier
                            .size(116.dp)
                            .graphicsLayer {
                                val pulse = bassEnergy.floatValue.coerceIn(0f, 1f)
                                val kick = beatKick.floatValue.coerceIn(0f, 1f)
                                val s = if (isPlaying) 1f + pulse * 0.06f + kick * 0.045f else 1f + breathPhase.value * 0.02f
                                scaleX = s
                                scaleY = s
                            },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Запустить волну",
                            modifier = Modifier.size(46.dp),
                        )
                    }
                }
            }

            if (hasAnyMascot && !isPlaying) {
                val zLetters = listOf("z" to 0.0f, "Z" to 0.33f, "z" to 0.66f)
                zLetters.forEachIndexed { idx, (letter, offsetPhase) ->
                    Text(
                        text = letter,
                        fontSize = (14 + idx * 3).sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 34.dp, end = 34.dp)
                                .graphicsLayer {
                                    val p = (zzzPhase.value + offsetPhase) % 1f
                                    translationY = -p * 42.dp.toPx()
                                    translationX = sin(p * PI * 2.0).toFloat() * 7.dp.toPx() + idx * 4.dp.toPx()
                                    alpha =
                                        when {
                                            p < 0.2f -> p / 0.2f
                                            p > 0.75f -> (1f - p) / 0.25f
                                            else -> 1f
                                        }.coerceIn(0f, 0.9f)
                                    val s = 0.8f + p * 0.35f
                                    scaleX = s
                                    scaleY = s
                                },
                    )
                }
            }
        }

        if (isGenerating && !isPlaying) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Подбираю волну…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (!isPlaying && hasAnyMascot) {
            Text(
                text = "Нажми, чтобы разбудить",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun WaveFilterPillButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MetrolistIcons.Outlined.Tune,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DislikeBanIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(19.dp)) {
        val strokePx = 1.8.dp.toPx()
        val r = size.minDimension / 2f - strokePx
        drawCircle(
            color = tint,
            radius = r,
            style = Stroke(width = strokePx),
        )
        val inset = r * 0.68f
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(
            color = tint,
            start = Offset(cx - inset, cy - inset),
            end = Offset(cx + inset, cy + inset),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun WaveBottomStatusAndPlayer(
    currentSong: SongItem?,
    isLiked: Boolean,
    onToggleLike: (SongItem) -> Unit,
    onDislikeClick: (SongItem) -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onOpenFullPlayer: () -> Unit,
) {
    AnimatedVisibility(
        visible = currentSong != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val song = currentSong ?: return@AnimatedVisibility
        val artistLine = song.artists.joinToString(", ") { it.name }.trim()
        val hdCover = ResolverDiagnostics.hdCoverFor(song.id) ?: song.thumbnail ?: CustomMascotAssets.fallbackCoverUri

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        url = hdCover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClick = onOpenFullPlayer),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f).clickable(onClick = onOpenFullPlayer),
                    ) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artistLine.isNotBlank()) {
                            Text(
                                text = artistLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onDislikeClick(song) }, modifier = Modifier.size(38.dp)) {
                        DislikeBanIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onToggleLike(song) }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                            contentDescription = "Лайк",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onPreviousClick, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.SkipNext,
                            contentDescription = "Предыдущий",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.graphicsLayer { scaleX = -1f },
                        )
                    }
                    IconButton(onClick = onNextClick, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.SkipNext,
                            contentDescription = "Следующий",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeSourceBadge(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun WaveFilterSheetContent(
    config: CustomAppConfig,
    isGenerating: Boolean,
    onUpdate: ((CustomAppConfig) -> CustomAppConfig) -> Unit,
    onRefreshWave: () -> Unit,
) {
    val hasCustomFilters =
        config.waveCharacter != WaveCharacter.DEFAULT ||
            config.waveEra != WaveEra.ANY ||
            config.waveLanguage != WaveLanguage.ANY ||
            config.waveMood != WaveMood.ANY

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Настройка волны",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        FilterGroupTitle("Характер")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveCharacter.entries.forEach { item ->
                FilterChip(
                    selected = config.waveCharacter == item,
                    onClick = { onUpdate { it.copy(waveCharacter = item) } },
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }

        FilterGroupTitle("Эпоха")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveEra.entries.forEach { item ->
                FilterChip(
                    selected = config.waveEra == item,
                    onClick = { onUpdate { it.copy(waveEra = item) } },
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }

        FilterGroupTitle("Язык")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveLanguage.entries.forEach { item ->
                FilterChip(
                    selected = config.waveLanguage == item,
                    onClick = { onUpdate { it.copy(waveLanguage = item) } },
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }

        FilterGroupTitle("Настроение")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveMood.entries.forEach { item ->
                FilterChip(
                    selected = config.waveMood == item,
                    onClick = { onUpdate { it.copy(waveMood = item) } },
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasCustomFilters) {
                OutlinedButton(
                    onClick = {
                        onUpdate {
                            it.copy(
                                waveCharacter = WaveCharacter.DEFAULT,
                                waveEra = WaveEra.ANY,
                                waveLanguage = WaveLanguage.ANY,
                                waveMood = WaveMood.ANY,
                            )
                        }
                    },
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Сбросить")
                }
            }
            Button(
                enabled = !isGenerating,
                onClick = onRefreshWave,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text("Обновить волну", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun FilterGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun CustomEngineSettingsContent(
    configStore: CustomConfigStore,
    yandexProvider: YandexMusicProvider,
    vkProvider: VkMusicProvider,
    geminiClient: GeminiClient,
    onOpenClassicHome: () -> Unit = {},
    onRefreshWave: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val activeModel by geminiClient.activeModel.collectAsState()
    val resolverLogs by ResolverDiagnostics.logs.collectAsState()
    val cacheStats by CustomMediaCacheManager.stats.collectAsState()
    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)
    val catalogProvider = remember(configStore) { CustomCatalogProvider(configStore) }

    DisposableEffect(context) {
        CustomAuthWebViewHelper.registerContext(context)
        onDispose { }
    }

    var yandexInput by remember(cfg.yandexToken) { mutableStateOf(cfg.yandexToken) }
    var vkInput by remember(cfg.vkToken) { mutableStateOf(cfg.vkToken) }
    var lfmKeyInput by remember(cfg.lastFmApiKey) { mutableStateOf(cfg.lastFmApiKey) }
    var lfmSecretInput by remember(cfg.lastFmSecret) { mutableStateOf(cfg.lastFmSecret) }
    var lfmUserInput by remember(cfg.lastFmUsername) { mutableStateOf(cfg.lastFmUsername) }
    var geminiKeyInput by remember(cfg.geminiApiKey) { mutableStateOf(cfg.geminiApiKey) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingApis by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showManualTokens by remember { mutableStateOf(false) }
    var activeOAuthProvider by remember { mutableStateOf<OAuthProvider?>(null) }

    var importSyncLimit by remember { mutableIntStateOf(100) }
    var importProgressText by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val isImporting = importJob?.isActive == true

    fun persistInputs() {
        configStore.update {
            it.copy(
                yandexToken = yandexInput.trim(),
                vkToken = vkInput.trim(),
                lastFmApiKey = lfmKeyInput.trim(),
                lastFmSecret = lfmSecretInput.trim(),
                lastFmUsername = lfmUserInput.trim(),
                geminiApiKey = geminiKeyInput.trim(),
            )
        }
    }

    val exportFileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            persistInputs()
            val payload = configStore.exportToJson(extraLikedIds = SongLikeState.likedSongIds)
            val saved =
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось открыть файл для записи")
                }
            statusMessage =
                saved.fold(
                    onSuccess = { "Бэкап сохранён в файл (лайков: ${maxOf(cfg.likedTracksBackup.size, likedIds.size)})" },
                    onFailure = { err -> "Ошибка сохранения файла: ${err.message}" },
                )
        }

    val importFileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val readResult =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                ?: error("Не удалось прочитать файл")
                        }
                    }
                val raw =
                    readResult.getOrElse { err ->
                        statusMessage = "Ошибка чтения файла: ${err.message}"
                        return@launch
                    }
                configStore
                    .importFromJson(raw)
                    .onSuccess { imported ->
                        yandexInput = imported.yandexToken
                        vkInput = imported.vkToken
                        lfmKeyInput = imported.lastFmApiKey
                        lfmSecretInput = imported.lastFmSecret
                        lfmUserInput = imported.lastFmUsername
                        geminiKeyInput = imported.geminiApiKey

                        val toRestore =
                            imported.likedTracksBackup
                                .filter { it.title.isNotBlank() && it.id !in SongLikeState.likedSongIds }
                                .take(200)
                        if (toRestore.isEmpty()) {
                            statusMessage = "Бэкап загружен"
                        } else {
                            statusMessage = "Бэкап загружен, восстанавливаю лайки (${toRestore.size})…"
                            val restoredCount =
                                withContext(Dispatchers.IO) {
                                    var ok = 0
                                    for (entry in toRestore) {
                                        val found =
                                            runCatching {
                                                val q = listOf(entry.artist, entry.title).filter { it.isNotBlank() }.joinToString(" ")
                                                val page =
                                                    GlobalYouTubeRepository.instance.searchFiltered(
                                                        query = q,
                                                        filter = SearchFilter.SONG,
                                                        allowOfflineFallback = false,
                                                    )
                                                val songs = page.items.filterIsInstance<SongItem>()
                                                songs.firstOrNull { entry.id.isNotBlank() && it.id == entry.id } ?: songs.firstOrNull()
                                            }.getOrNull()
                                        if (found != null && found.id !in SongLikeState.likedSongIds) {
                                            withContext(Dispatchers.Main) {
                                                if (found.id !in SongLikeState.likedSongIds) {
                                                    SongLikeState.toggleLike(found)
                                                }
                                            }
                                            ok += 1
                                        }
                                    }
                                    ok
                                }
                            statusMessage = "Бэкап загружен, восстановлено лайков: $restoredCount"
                        }
                    }.onFailure { err ->
                        statusMessage = "Ошибка JSON: ${err.message}"
                    }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Источники и ИИ-движок",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        if (onRefreshWave != null) {
            Button(
                onClick = onRefreshWave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(MetrolistIcons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Обновить волну сейчас", fontWeight = FontWeight.SemiBold)
            }
        }

        SettingSwitchRow(
            title = "Гибридный резолвер (Deezer / Яндекс / VK / YTM)",
            checked = cfg.hybridResolverEnabled,
            onCheckedChange = { v -> configStore.update { it.copy(hybridResolverEnabled = v) } },
        )
        SettingSwitchRow(
            title = "Антицензура РФ (Explicit и обрезанные → VK)",
            checked = cfg.preferVkForExplicit,
            onCheckedChange = { v -> configStore.update { it.copy(preferVkForExplicit = v) } },
        )
        SettingSwitchRow(
            title = "Кэш 4 ГБ (FIFO) и защищённый кэш лайков",
            checked = cfg.cacheWaveAndDownloadLikes,
            onCheckedChange = { v -> configStore.update { it.copy(cacheWaveAndDownloadLikes = v) } },
        )
        SettingSwitchRow(
            title = "Анимированный маскот на экране «Волны»",
            checked = cfg.mascotEnabled,
            onCheckedChange = { v -> configStore.update { it.copy(mascotEnabled = v) } },
        )
        SettingSwitchRow(
            title = "Открывать вкладку «Волна» при старте",
            checked = cfg.useWaveAsHome,
            onCheckedChange = { v -> configStore.update { it.copy(useWaveAsHome = v) } },
        )

        FilterGroupTitle("Треков в ИИ-плейлисте: ${cfg.aiPlaylistSize}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 25, 50, 100).forEach { size ->
                FilterChip(
                    selected = cfg.aiPlaylistSize == size,
                    onClick = { configStore.update { it.copy(aiPlaylistSize = size) } },
                    label = { Text("$size", maxLines = 1) },
                )
            }
        }

        HorizontalDivider()

        FilterGroupTitle("Вход в сервисы (встроенный браузер)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { activeOAuthProvider = OAuthProvider.VK },
            ) {
                Text(if (vkInput.isBlank()) "Войти в ВК" else "Обновить ВК")
            }
            Button(
                onClick = { activeOAuthProvider = OAuthProvider.YANDEX },
            ) {
                Text(if (yandexInput.isBlank()) "Войти в Яндекс" else "Обновить Яндекс")
            }
            OutlinedButton(
                onClick = { showManualTokens = !showManualTokens },
            ) {
                Text(if (showManualTokens) "Скрыть токены" else "Вручную")
            }
        }
        Text(
            text =
                "ВК: ${if (vkInput.isNotBlank()) "подключён" else "не подключён"} • " +
                    "Яндекс: ${if (yandexInput.isNotBlank()) "подключён" else "не подключён"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (showManualTokens) {
            OutlinedTextField(
                value = yandexInput,
                onValueChange = { yandexInput = it },
                label = { Text("Токен Яндекс.Музыки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vkInput,
                onValueChange = { vkInput = it },
                label = { Text("Токен ВК (Kate / VK Android)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HorizontalDivider()

        FilterGroupTitle("Gemini API (для «Моей волны» и подборок)")
        OutlinedTextField(
            value = geminiKeyInput,
            onValueChange = { geminiKeyInput = it },
            label = { Text("Gemini API Key (AI Studio)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_AI_STUDIO_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Открыть AI Studio")
            }
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) {
                        geminiKeyInput = clip
                        configStore.update { it.copy(geminiApiKey = clip) }
                        statusMessage = "Gemini API ключ вставлен и сохранён"
                    } else {
                        Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Вставить")
            }
        }

        FilterGroupTitle("Last.fm (необязательно — резерв и скробблинг)")
        OutlinedTextField(
            value = lfmKeyInput,
            onValueChange = { lfmKeyInput = it },
            label = { Text("Last.fm API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lfmSecretInput,
            onValueChange = { lfmSecretInput = it },
            label = { Text("Last.fm Secret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lfmUserInput,
            onValueChange = { lfmUserInput = it },
            label = { Text("Last.fm Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FilterGroupTitle("Режим моделей Gemini (автопереключение при 429/503)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = cfg.geminiModelMode == CustomAppConfig.MODEL_MODE_AUTO,
                onClick = { configStore.update { it.copy(geminiModelMode = CustomAppConfig.MODEL_MODE_AUTO) } },
                label = { Text("AUTO (каскад)", maxLines = 1) },
            )
            GeminiClient.DEFAULT_MODELS.forEach { modelName ->
                FilterChip(
                    selected = cfg.geminiModelMode == modelName,
                    onClick = { configStore.update { it.copy(geminiModelMode = modelName) } },
                    label = { Text(modelName.removePrefix("gemini-").removePrefix("gemma-"), maxLines = 1) },
                )
            }
        }
        if (!activeModel.isNullOrBlank()) {
            Text(
                text = "Активная модель: $activeModel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                enabled = !isCheckingApis,
                onClick = {
                    persistInputs()
                    isCheckingApis = true
                    statusMessage = "Проверяю Яндекс, VK и Gemini…"
                    scope.launch {
                        val report =
                            coroutineScope {
                                val yandexTask =
                                    async {
                                        val token = yandexInput.trim()
                                        if (token.isBlank()) {
                                            "Я.Музыка: не указан"
                                        } else {
                                            when (yandexProvider.checkPlusStatus(token)) {
                                                YandexPlusStatus.ACTIVE -> "Я.Музыка: OK (Плюс активен)"
                                                YandexPlusStatus.NO_PLUS -> "Я.Музыка: нет Плюса (идёт на VK/YTM)"
                                                YandexPlusStatus.INVALID_TOKEN -> "Я.Музыка: невалидный токен"
                                                YandexPlusStatus.NO_TOKEN -> "Я.Музыка: не указан"
                                                YandexPlusStatus.UNKNOWN -> "Я.Музыка: ошибка сети"
                                            }
                                        }
                                    }
                                val vkTask =
                                    async {
                                        val token = vkInput.trim()
                                        if (token.isBlank()) {
                                            "VK: не указан"
                                        } else {
                                            vkProvider.checkTokenDetailed(token).fold(
                                                onSuccess = { "VK: $it" },
                                                onFailure = { "VK: ${it.message ?: "ошибка"}" },
                                            )
                                        }
                                    }
                                val geminiTask =
                                    async {
                                        val key = geminiKeyInput.trim()
                                        if (key.isBlank()) {
                                            "Gemini: не указан"
                                        } else {
                                            geminiClient.testConnection(key).fold(
                                                onSuccess = { "Gemini: OK ($it)" },
                                                onFailure = { "Gemini: ${it.message ?: "ошибка"}" },
                                            )
                                        }
                                    }
                                listOf(yandexTask.await(), vkTask.await(), geminiTask.await()).joinToString("\n")
                            }
                        statusMessage = report
                        isCheckingApis = false
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isCheckingApis) "Проверяю…" else "Проверить все API")
            }

            Button(
                onClick = {
                    persistInputs()
                    statusMessage = "Ключи и настройки сохранены"
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Сохранить")
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        HorizontalDivider()

        FilterGroupTitle("Ручной импорт лайков в Библиотеку")
        Text(
            text = "Вкус сканируется в фоне автоматически. Выбери, сколько лайков добавить прямо в «Мне нравится»:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100 to "100", 300 to "300", 500 to "500", 0 to "Все").forEach { (limit, label) ->
                FilterChip(
                    selected = importSyncLimit == limit,
                    enabled = !isImporting,
                    onClick = { importSyncLimit = limit },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !isImporting,
                onClick = {
                    persistInputs()
                    importProgressText = "Запускаю импорт из Я.Музыки…"
                    importJob =
                        scope.launch {
                            catalogProvider
                                .importYandexLikes(importSyncLimit) { prog ->
                                    importProgressText = prog.message
                                }.onSuccess { res ->
                                    importProgressText =
                                        "Готово (${res.sourceLabel}): загружено ${res.totalFetched}, в памяти ${res.savedForWave}, добавлено в Библиотеку ${res.addedToLibrary}"
                                }.onFailure { err ->
                                    importProgressText = "Ошибка импорта: ${err.message}"
                                }
                        }
                },
            ) {
                Text("Я.Музыка")
            }
            OutlinedButton(
                enabled = !isImporting,
                onClick = {
                    persistInputs()
                    importProgressText = "Запускаю импорт из VK…"
                    importJob =
                        scope.launch {
                            catalogProvider
                                .importVkAudios(importSyncLimit) { prog ->
                                    importProgressText = prog.message
                                }.onSuccess { res ->
                                    importProgressText =
                                        "Готово (${res.sourceLabel}): загружено ${res.totalFetched}, в памяти ${res.savedForWave}, добавлено в Библиотеку ${res.addedToLibrary}"
                                }.onFailure { err ->
                                    importProgressText = "Ошибка импорта: ${err.message}"
                                }
                        }
                },
            ) {
                Text("VK")
            }
            OutlinedButton(
                enabled = !isImporting,
                onClick = {
                    persistInputs()
                    importProgressText = "Запускаю импорт из YouTube…"
                    importJob =
                        scope.launch {
                            catalogProvider
                                .importYouTubeLikes(importSyncLimit) { prog ->
                                    importProgressText = prog.message
                                }.onSuccess { res ->
                                    importProgressText =
                                        "Готово (${res.sourceLabel}): загружено ${res.totalFetched}, в памяти ${res.savedForWave}, добавлено в Библиотеку ${res.addedToLibrary}"
                                }.onFailure { err ->
                                    importProgressText = "Ошибка импорта: ${err.message}"
                                }
                        }
                },
            ) {
                Text("YouTube")
            }
            if (isImporting) {
                OutlinedButton(
                    onClick = {
                        importJob?.cancel()
                        importJob = null
                        importProgressText = "Импорт остановлен (уже найденные треки сохранены)"
                    },
                ) {
                    Text("Стоп")
                }
            }
        }
        if (!importProgressText.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = importProgressText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        HorizontalDivider()

        FilterGroupTitle("Кэш 4 ГБ (FIFO) и защищённые лайки")
        Text(
            text =
                "Очередь и волна: ${CustomMediaCacheManager.formatSize(cacheStats.waveCacheBytes)} / 4 ГБ (${cacheStats.waveCacheCount} треков)\n" +
                    "Защищённые лайки: ${CustomMediaCacheManager.formatSize(cacheStats.likedDownloadsBytes)} (${cacheStats.likedDownloadsCount} треков)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    CustomMediaCacheManager.clearWaveCache()
                    statusMessage = "Кэш 4 ГБ очищен"
                },
            ) {
                Text("Очистить кэш")
            }
            OutlinedButton(
                onClick = {
                    CustomMediaCacheManager.clearLikedDownloads()
                    statusMessage = "Защищённый кэш лайков очищен"
                },
            ) {
                Text("Очистить лайки")
            }
        }

        HorizontalDivider()

        val mutedCount = cfg.artistReputation.values.count { it.isAutoMuted }
        val overridesCount = cfg.trackSourceOverrides.keys.count { !it.startsWith("id::") }
        val savedLikesCount = maxOf(cfg.likedTracksBackup.size, likedIds.size)
        val topArtistsCount = cfg.localArtistStats.size
        FilterGroupTitle("Локальная память вкуса и источников")
        Text(
            text =
                "Лайков: $savedLikesCount • Дизлайков: ${cfg.dislikedTracks.size} • Топ артистов: $topArtistsCount\n" +
                    "Скан вкуса: ${cfg.externalTasteProfile.size} • ЧС файлов ВК: ${cfg.blacklistedVkAudioIds.size} • Скрытых: $mutedCount • Источников: $overridesCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cfg.dislikedTracks.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        configStore.clearDislikedTracks()
                        statusMessage = "Список дизлайков очищен"
                    },
                ) {
                    Text("Сбросить дизлайки")
                }
            }
            if (mutedCount > 0) {
                OutlinedButton(
                    onClick = {
                        configStore.resetMutedArtists()
                        statusMessage = "Скрытые артисты возвращены"
                    },
                ) {
                    Text("Вернуть артистов")
                }
            }
            if (cfg.trackSourceOverrides.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        configStore.clearTrackSourceOverrides()
                        statusMessage = "Ручные источники сброшены"
                    },
                ) {
                    Text("Сбросить источники")
                }
            }
            if (cfg.blacklistedVkAudioIds.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        configStore.clearBlacklistedVkAudios()
                        statusMessage = "Чёрный список файлов VK очищен"
                    },
                ) {
                    Text("Очистить ЧС VK")
                }
            }
            OutlinedButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "Скрыть лог" else "Лог резолвера (${resolverLogs.size})")
            }
        }

        if (showLogs) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (resolverLogs.isEmpty()) {
                        Text(
                            text = "Лог пока пуст — включи любой трек",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        resolverLogs.take(12).forEach { entry ->
                            Text(
                                text = "${entry.qualityBadge} (${entry.detail}) — ${entry.trackTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        FilterGroupTitle("Полный бэкап .json (ключи, лайки, вкус, источники, ЧС)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    persistInputs()
                    exportFileLauncher.launch("metrolist_backup.json")
                },
            ) {
                Icon(MetrolistIcons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Сохранить .json")
            }
            OutlinedButton(
                onClick = {
                    importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            ) {
                Text("Загрузить .json")
            }
            OutlinedButton(
                onClick = {
                    persistInputs()
                    val exported = configStore.exportToJson(extraLikedIds = SongLikeState.likedSongIds)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("metrolist_custom_json", exported))
                    statusMessage = "JSON скопирован в буфер"
                },
            ) {
                Icon(MetrolistIcons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("В буфер")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    activeOAuthProvider?.let { provider ->
        ServiceOAuthWebViewDialog(
            provider = provider,
            onTokenCaptured = { capturedToken ->
                activeOAuthProvider = null
                when (provider) {
                    OAuthProvider.VK -> {
                        vkInput = capturedToken
                        configStore.update { it.copy(vkToken = capturedToken) }
                        statusMessage = "Вход в ВК выполнен — токен сохранён, вкус сканируется в фоне"
                    }
                    OAuthProvider.YANDEX -> {
                        yandexInput = capturedToken
                        configStore.update { it.copy(yandexToken = capturedToken) }
                        statusMessage = "Вход в Яндекс выполнен — проверяю подписку Плюс…"
                        scope.launch {
                            val st = yandexProvider.checkPlusStatus(capturedToken)
                            statusMessage =
                                when (st) {
                                    YandexPlusStatus.ACTIVE -> "Яндекс подключён (Плюс активен)"
                                    YandexPlusStatus.NO_PLUS -> "Яндекс подключён (без Плюса — для потока будет VK/YTM)"
                                    else -> "Токен Яндекса сохранён"
                                }
                        }
                    }
                }
            },
            onDismiss = { activeOAuthProvider = null },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SearchAiPlaylistCard(
    query: String,
    aiWaveEngine: AiWaveEngine,
    onPlaySongs: (List<SongItem>, String) -> Unit,
    onEnqueueSongs: (List<SongItem>) -> Unit,
    onSaveToLibrary: ((String, List<SongItem>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember(query) { mutableStateOf(true) }
    var result by remember(query) { mutableStateOf<AiPlaylistResult?>(null) }
    var errorText by remember(query) { mutableStateOf<String?>(null) }
    var savedToLibrary by remember(query) { mutableStateOf(false) }

    LaunchedEffect(query) {
        isLoading = true
        errorText = null
        result = null
        savedToLibrary = false
        aiWaveEngine
            .generateCustomPlaylist(query)
            .onSuccess { built -> result = built }
            .onFailure { err -> errorText = err.message ?: "Не удалось собрать подборку" }
        isLoading = false
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "ИИ-подборка: «$query»",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text("Подбираю треки…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                errorText != null -> {
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                result != null -> {
                    val songs = result!!.songs
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { onPlaySongs(songs, result!!.query) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(MetrolistIcons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Играть (${songs.size})")
                        }
                        OutlinedButton(
                            onClick = { onEnqueueSongs(songs) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("В очередь")
                        }
                        if (onSaveToLibrary != null) {
                            OutlinedButton(
                                enabled = !savedToLibrary,
                                onClick = {
                                    onSaveToLibrary(result!!.query, songs)
                                    savedToLibrary = true
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (savedToLibrary) "Сохранено" else "В плейлисты")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(songs, key = { _, item -> item.id }) { index, song ->
                            val artistText = song.artists.joinToString(", ") { it.name }
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPlaySongs(songs.drop(index), result!!.query) }
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(26.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (artistText.isNotBlank()) {
                                        Text(
                                            text = artistText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
