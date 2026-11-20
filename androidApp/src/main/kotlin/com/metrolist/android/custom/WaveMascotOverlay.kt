@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.metrolist.android.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val PurpleNeonPrimary = Color(0xFF9D4EDD)
private val PurpleNeonGlow = Color(0xFF7B2CBF)
private val PurpleNeonSoft = Color(0xFFC77DFF)

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
    onSwitchToClassicHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val isGenerating by aiWaveEngine.isGeneratingWave.collectAsState()
    val waveStatus by aiWaveEngine.statusText.collectAsState()
    val activeModel by geminiClient.activeModel.collectAsState()

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)
    val isCurrentLiked = currentSong?.id?.let { it in likedIds } == true

    var bassEnergy by remember { mutableFloatStateOf(0f) }
    var beatKick by remember { mutableFloatStateOf(0f) }
    var spectrumBands by remember { mutableStateOf(FloatArray(18)) }
    var mascotPhrase by remember { mutableStateOf<String?>(null) }
    var showWaveSettingsSheet by remember { mutableStateOf(false) }
    var showEngineSettingsSheet by remember { mutableStateOf(false) }

    val accentColor = rememberNeonAccentColor(cfg.neonColorMode, currentSong?.thumbnail)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            while (isActive) {
                bassEnergy = (bassEnergy * 0.84f).coerceAtLeast(0f)
                beatKick = (beatKick * 0.80f).coerceAtLeast(0f)
                val decayed = FloatArray(18) { i -> (spectrumBands[i] * 0.82f).coerceAtLeast(0f) }
                spectrumBands = decayed
                if (bassEnergy < 0.01f && beatKick < 0.01f) break
                delay(32L)
            }
            return@LaunchedEffect
        }
        com.metrolist.shared.equalizer.AudioSpectrumMeter.addConsumer()
        var avgBass = 0.15f
        try {
            while (isActive) {
                val levels = com.metrolist.shared.equalizer.AudioSpectrumMeter.levels()
                val nextBands =
                    FloatArray(18) { i ->
                        val raw = levels.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                        val prev = spectrumBands[i]
                        if (raw > prev) raw else (prev * 0.72f + raw * 0.28f)
                    }
                spectrumBands = nextBands

                val rawBass = ((nextBands[0] * 0.45f) + (nextBands[1] * 0.35f) + (nextBands[2] * 0.20f)).coerceIn(0f, 1f)
                bassEnergy = if (rawBass > bassEnergy) rawBass else (bassEnergy * 0.78f + rawBass * 0.22f)

                val delta = (rawBass - avgBass).coerceAtLeast(0f)
                avgBass = avgBass * 0.90f + rawBass * 0.10f
                val kickNow = (delta * 3.4f).coerceIn(0f, 1f)
                beatKick = if (kickNow > beatKick) kickNow else (beatKick * 0.76f)

                delay(32L)
            }
        } finally {
            com.metrolist.shared.equalizer.AudioSpectrumMeter.removeConsumer()
        }
    }

    LaunchedEffect(currentSong?.id, isPlaying) {
        val song = currentSong ?: return@LaunchedEffect
        if (!isPlaying || cfg.geminiApiKey.isBlank()) return@LaunchedEffect
        delay(1_200L)
        if (currentSong?.id == song.id) {
            aiWaveEngine.generateMascotPhrase(song)?.let { phrase ->
                mascotPhrase = phrase
            }
        }
    }

    fun startOrToggleWave() {
        if (currentSong != null && playerConnection != null) {
            playerConnection.togglePlayPause()
            return
        }
        scope.launch {
            val result = aiWaveEngine.generateWaveSongs()
            result.onSuccess { songs ->
                playerConnection?.playQueue(songs, queueTitle = "Моя волна", finite = false)
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "Не удалось запустить волну", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshWaveWithCurrentFilters() {
        scope.launch {
            val result = aiWaveEngine.generateWaveSongs()
            result.onSuccess { songs ->
                playerConnection?.playQueue(songs, queueTitle = "Моя волна", finite = false)
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "Не удалось обновить волну", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = statusBarTop + 8.dp, bottom = bottomInset + 12.dp, start = 18.dp, end = 18.dp),
    ) {
        val isWideLayout = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        color = Color.White,
                    )
                    SourceBadge(clientName = playbackResource?.clientName)
                }

                IconButton(onClick = { showEngineSettingsSheet = true }) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Settings,
                        contentDescription = "Настройки движка",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isWideLayout) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        MascotStage(
                            mascotEnabled = cfg.mascotEnabled,
                            folderUri = cfg.mascotFolderUri,
                            trackKey = currentSong?.id,
                            accentColor = accentColor,
                            bassEnergy = bassEnergy,
                            beatKick = beatKick,
                            spectrumBands = spectrumBands,
                            isPlaying = isPlaying,
                            size = 250.dp,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1.1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        WaveControlsBlock(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            isGenerating = isGenerating,
                            isLiked = isCurrentLiked,
                            filterSummary = cfg.waveFilterSummary(),
                            statusOrPhrase = waveStatus ?: mascotPhrase,
                            activeModel = activeModel,
                            accentColor = accentColor,
                            onPlayPauseClick = ::startOrToggleWave,
                            onOpenWaveSettings = { showWaveSettingsSheet = true },
                            onToggleLike = { song -> SongLikeState.toggleLike(song) },
                            onNextClick = { playerConnection?.skipToNext() },
                            onOpenFullPlayer = onExpandPlayer,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MascotStage(
                        mascotEnabled = cfg.mascotEnabled,
                        folderUri = cfg.mascotFolderUri,
                        trackKey = currentSong?.id,
                        accentColor = accentColor,
                        bassEnergy = bassEnergy,
                        beatKick = beatKick,
                        spectrumBands = spectrumBands,
                        isPlaying = isPlaying,
                        size = 252.dp,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    WaveControlsBlock(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        isGenerating = isGenerating,
                        isLiked = isCurrentLiked,
                        filterSummary = cfg.waveFilterSummary(),
                        statusOrPhrase = waveStatus ?: mascotPhrase,
                        activeModel = activeModel,
                        accentColor = accentColor,
                        onPlayPauseClick = ::startOrToggleWave,
                        onOpenWaveSettings = { showWaveSettingsSheet = true },
                        onToggleLike = { song -> SongLikeState.toggleLike(song) },
                        onNextClick = { playerConnection?.skipToNext() },
                        onOpenFullPlayer = onExpandPlayer,
                    )
                }
            }
        }
    }

    if (showWaveSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showWaveSettingsSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF121018),
            contentColor = Color.White,
        ) {
            WaveFilterSheetContent(
                config = cfg,
                accentColor = accentColor,
                onUpdate = { transform -> configStore.update(transform) },
                onApplyAndWave = {
                    showWaveSettingsSheet = false
                    refreshWaveWithCurrentFilters()
                },
            )
        }
    }

    if (showEngineSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showEngineSettingsSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF121018),
            contentColor = Color.White,
        ) {
            CustomEngineSettingsContent(
                configStore = configStore,
                yandexProvider = yandexProvider,
                vkProvider = vkProvider,
                geminiClient = geminiClient,
                onOpenClassicHome = {
                    showEngineSettingsSheet = false
                    onSwitchToClassicHome()
                },
            )
        }
    }
}

@Composable
private fun WaveControlsBlock(
    currentSong: SongItem?,
    isPlaying: Boolean,
    isGenerating: Boolean,
    isLiked: Boolean,
    filterSummary: String,
    statusOrPhrase: String?,
    activeModel: String?,
    accentColor: Color,
    onPlayPauseClick: () -> Unit,
    onOpenWaveSettings: () -> Unit,
    onToggleLike: (SongItem) -> Unit,
    onNextClick: () -> Unit,
    onOpenFullPlayer: () -> Unit,
) {
    val buttonScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "playScale",
    )

    Surface(
        onClick = onPlayPauseClick,
        enabled = !isGenerating,
        shape = CircleShape,
        color = accentColor,
        contentColor = Color.White,
        shadowElevation = 14.dp,
        modifier = Modifier.size(74.dp).scale(buttonScale),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isGenerating) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Запустить волну",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Surface(
        onClick = onOpenWaveSettings,
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFF161121),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.40f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = MetrolistIcons.Outlined.Tune,
                contentDescription = null,
                tint = PurpleNeonSoft,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = filterSummary,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    AnimatedVisibility(visible = currentSong != null) {
        val song = currentSong ?: return@AnimatedVisibility
        val artistLine = song.artists.joinToString(", ") { it.name }.trim()

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF14101D),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        url = song.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onOpenFullPlayer),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f).clickable(onClick = onOpenFullPlayer),
                    ) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artistLine.isNotBlank()) {
                            Text(
                                text = artistLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onToggleLike(song) }) {
                        Icon(
                            imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                            contentDescription = "Лайк",
                            tint = if (isLiked) accentColor else Color.White.copy(alpha = 0.75f),
                        )
                    }
                    IconButton(onClick = onNextClick) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.SkipNext,
                            contentDescription = "Следующий",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }

    if (!statusOrPhrase.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = statusOrPhrase,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }

    if (!activeModel.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "ИИ: $activeModel",
            style = MaterialTheme.typography.labelSmall,
            color = PurpleNeonSoft.copy(alpha = 0.60f),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MascotStage(
    mascotEnabled: Boolean,
    folderUri: String,
    trackKey: String?,
    accentColor: Color,
    bassEnergy: Float,
    beatKick: Float,
    spectrumBands: FloatArray,
    isPlaying: Boolean,
    size: Dp,
) {
    val context = LocalContext.current
    val pulse = bassEnergy.coerceIn(0f, 1f)
    val kick = beatKick.coerceIn(0f, 1f)

    var videos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var videoIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(folderUri, mascotEnabled) {
        videos =
            if (!mascotEnabled || folderUri.isBlank()) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    listVideoUrisInFolder(context, folderUri.toUri())
                }
            }
        videoIndex = 0
    }

    LaunchedEffect(trackKey, videos.size) {
        if (videos.size > 1 && trackKey != null) {
            videoIndex = (videoIndex + 1) % videos.size
        }
    }

    val exoPlayer =
        remember(videos) {
            if (videos.isEmpty()) {
                null
            } else {
                ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    videos.forEach { uri -> addMediaItem(MediaItem.fromUri(uri)) }
                    prepare()
                    playWhenReady = true
                }
            }
        }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    LaunchedEffect(exoPlayer, isPlaying, videoIndex, videos.size) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (videos.isNotEmpty() && player.currentMediaItemIndex != videoIndex) {
            player.seekTo(videoIndex.coerceIn(0, videos.lastIndex), 0L)
        }
        player.playWhenReady = isPlaying
    }

    val infiniteTransition = rememberInfiniteTransition(label = "neonWaveIdle")
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "breath",
    )
    val phaseDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 16000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "phase",
    )

    val secondaryColor = remember(accentColor) { lerp(accentColor, PurpleNeonSoft, 0.55f) }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .clickable(enabled = videos.size > 1) {
                    videoIndex = (videoIndex + 1) % videos.size
                },
    ) {
        // Реактивная плазма и 36-лучевой неоновый спектр вокруг центра
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxR = this.size.minDimension / 2f
            val energy = if (isPlaying) pulse else idleBreath * 0.22f
            val auraRadius = maxR * (0.76f + energy * 0.22f + kick * 0.10f)

            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                accentColor.copy(alpha = (0.36f + energy * 0.34f + kick * 0.16f).coerceIn(0f, 0.85f)),
                                PurpleNeonGlow.copy(alpha = (0.16f + energy * 0.22f).coerceIn(0f, 0.55f)),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = auraRadius,
                    ),
                radius = auraRadius,
                center = center,
            )

            // Кольцо ударной волны при ударе бочки
            if (kick > 0.08f) {
                drawCircle(
                    color = secondaryColor.copy(alpha = (kick * 0.55f).coerceIn(0f, 0.6f)),
                    radius = maxR * (0.58f + kick * 0.26f),
                    center = center,
                    style = Stroke(width = (1.5f + kick * 2.5f).dp.toPx()),
                )
            }

            // 36 неоновых штрихов спектра по кругу (зеркалируем 18 полос)
            val barCount = 36
            val innerR = maxR * (0.48f + energy * 0.05f)
            val maxBarLen = maxR * 0.30f
            for (i in 0 until barCount) {
                val bandIdx = if (i < 18) i else (35 - i)
                val level =
                    if (isPlaying) {
                        spectrumBands.getOrElse(bandIdx) { 0f }
                    } else {
                        val wave = sin(((phaseDeg + i * 20f) * PI / 180.0)).toFloat() * 0.5f + 0.5f
                        0.08f + wave * 0.14f
                    }
                val angleRad = ((i * (360f / barCount) + phaseDeg * 0.25f) * PI / 180.0)
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()
                val barLen = (maxBarLen * (0.14f + level * 0.86f + kick * 0.12f)).coerceAtMost(maxBarLen * 1.15f)
                val start = Offset(center.x + cosA * innerR, center.y + sinA * innerR)
                val end = Offset(center.x + cosA * (innerR + barLen), center.y + sinA * (innerR + barLen))
                val barColor = lerp(accentColor, secondaryColor, level.coerceIn(0f, 1f))
                drawLine(
                    color = barColor.copy(alpha = (0.38f + level * 0.60f).coerceIn(0f, 1f)),
                    start = start,
                    end = end,
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Если нет видео — рисуем живое неоновое ядро вместо пустого круга
            if (exoPlayer == null || videos.isEmpty()) {
                val coreR = maxR * (0.34f + energy * 0.09f + kick * 0.05f)
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors =
                                listOf(
                                    secondaryColor.copy(alpha = 0.85f),
                                    accentColor.copy(alpha = 0.62f),
                                    PurpleNeonGlow.copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                            center = center,
                            radius = coreR,
                        ),
                    radius = coreR,
                    center = center,
                )
            }
        }

        // Если выбрана папка с видео маскота — плавно вплавляем видео в чёрный фон без жёсткой рамки
        if (exoPlayer != null && videos.isNotEmpty()) {
            val videoBoxSize = size * 0.68f
            Box(
                modifier =
                    Modifier
                        .size(videoBoxSize)
                        .scale(1f + pulse * 0.05f + kick * 0.04f)
                        .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            exoPlayer.setVideoTextureView(this)
                        }
                    },
                    update = { view ->
                        exoPlayer.setVideoTextureView(view)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Мягкая чёрная виньетка по краю, чтобы видео растворялось в OLED-фоне
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val r = this.size.minDimension / 2f
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                0.68f to Color.Transparent,
                                0.90f to Color.Black.copy(alpha = 0.72f),
                                1.0f to Color.Black,
                                center = c,
                                radius = r,
                            ),
                        radius = r,
                        center = c,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(clientName: String?) {
    val label =
        when (clientName) {
            "yandex" -> "Я.Музыка"
            "vk" -> "VK"
            null, "" -> return
            else -> "YTM"
        }
    val tint =
        when (clientName) {
            "yandex" -> Color(0xFFFFCC00)
            "vk" -> Color(0xFF4C9AFF)
            else -> PurpleNeonSoft
        }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun WaveFilterSheetContent(
    config: CustomAppConfig,
    accentColor: Color,
    onUpdate: ((CustomAppConfig) -> CustomAppConfig) -> Unit,
    onApplyAndWave: () -> Unit,
) {
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
            color = Color.White,
        )

        FilterGroupTitle("Характер")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveCharacter.entries.forEach { item ->
                NeonFilterChip(
                    selected = config.waveCharacter == item,
                    label = item.label,
                    accentColor = accentColor,
                    onClick = { onUpdate { it.copy(waveCharacter = item) } },
                )
            }
        }

        FilterGroupTitle("Язык")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveLanguage.entries.forEach { item ->
                NeonFilterChip(
                    selected = config.waveLanguage == item,
                    label = item.label,
                    accentColor = accentColor,
                    onClick = { onUpdate { it.copy(waveLanguage = item) } },
                )
            }
        }

        FilterGroupTitle("Настроение")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WaveMood.entries.forEach { item ->
                NeonFilterChip(
                    selected = config.waveMood == item,
                    label = item.label,
                    accentColor = accentColor,
                    onClick = { onUpdate { it.copy(waveMood = item) } },
                )
            }
        }

        Button(
            onClick = onApplyAndWave,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Запустить с настройками", fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun FilterGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White.copy(alpha = 0.7f),
    )
}

@Composable
private fun NeonFilterChip(
    selected: Boolean,
    label: String,
    accentColor: Color,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Color(0xFF1C1729),
                labelColor = Color.White.copy(alpha = 0.8f),
                selectedContainerColor = accentColor.copy(alpha = 0.28f),
                selectedLabelColor = Color.White,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = Color.White.copy(alpha = 0.14f),
                selectedBorderColor = accentColor,
            ),
    )
}

@Composable
fun CustomEngineSettingsContent(
    configStore: CustomConfigStore,
    yandexProvider: YandexMusicProvider,
    vkProvider: VkMusicProvider,
    geminiClient: GeminiClient,
    onOpenClassicHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val yandexPlus by configStore.yandexPlusStatus.collectAsState()
    val activeModel by geminiClient.activeModel.collectAsState()

    var yandexInput by remember(cfg.yandexToken) { mutableStateOf(cfg.yandexToken) }
    var vkInput by remember(cfg.vkToken) { mutableStateOf(cfg.vkToken) }
    var lfmKeyInput by remember(cfg.lastFmApiKey) { mutableStateOf(cfg.lastFmApiKey) }
    var lfmSecretInput by remember(cfg.lastFmSecret) { mutableStateOf(cfg.lastFmSecret) }
    var lfmUserInput by remember(cfg.lastFmUsername) { mutableStateOf(cfg.lastFmUsername) }
    var geminiKeyInput by remember(cfg.geminiApiKey) { mutableStateOf(cfg.geminiApiKey) }
    var jsonBackupInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                configStore.update { it.copy(mascotFolderUri = treeUri.toString()) }
                statusMessage = "Папка маскота сохранена"
            }
        }

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

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Движок, ИИ и маскот",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        SettingSwitchRow(
            title = "Гибридный резолвер (Яндекс / ВК / YTM)",
            checked = cfg.hybridResolverEnabled,
            onCheckedChange = { v -> configStore.update { it.copy(hybridResolverEnabled = v) } },
        )
        SettingSwitchRow(
            title = "Приоритет ВК для Explicit-треков",
            checked = cfg.preferVkForExplicit,
            onCheckedChange = { v -> configStore.update { it.copy(preferVkForExplicit = v) } },
        )
        SettingSwitchRow(
            title = "Открывать «Мою волну» на главной",
            checked = cfg.useWaveAsHome,
            onCheckedChange = { v -> configStore.update { it.copy(useWaveAsHome = v) } },
        )
        SettingSwitchRow(
            title = "Видео-маскот (без папки работает неоновый спектр)",
            checked = cfg.mascotEnabled,
            onCheckedChange = { v -> configStore.update { it.copy(mascotEnabled = v) } },
        )

        FilterGroupTitle("Цвет басового неона")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonColorMode.entries.forEach { mode ->
                NeonFilterChip(
                    selected = cfg.neonColorMode == mode,
                    label = mode.label,
                    accentColor = PurpleNeonPrimary,
                    onClick = { configStore.update { it.copy(neonColorMode = mode) } },
                )
            }
        }

        FilterGroupTitle("Треков в подборках ИИ: ${cfg.aiPlaylistSize}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 20, 25, 35).forEach { size ->
                NeonFilterChip(
                    selected = cfg.aiPlaylistSize == size,
                    label = "$size",
                    accentColor = PurpleNeonPrimary,
                    onClick = { configStore.update { it.copy(aiPlaylistSize = size) } },
                )
            }
        }

        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (cfg.mascotFolderUri.isBlank()) "Выбрать папку с видео маскота (.mp4)" else "Сменить папку видео маскота",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cfg.mascotFolderUri.isNotBlank()) {
            TextButton(onClick = { configStore.update { it.copy(mascotFolderUri = "") } }) {
                Text("Сбросить папку и вернуть неоновый спектр", color = PurpleNeonSoft)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        OutlinedTextField(
            value = yandexInput,
            onValueChange = { yandexInput = it },
            label = { Text("Токен Яндекс.Музыки") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    persistInputs()
                    scope.launch {
                        val st = yandexProvider.checkPlusStatus(yandexInput.trim())
                        statusMessage =
                            when (st) {
                                YandexPlusStatus.ACTIVE -> "Яндекс Плюс активен"
                                YandexPlusStatus.NO_PLUS -> "Токен валиден, но Плюса нет — пойдёт на ВК/YTM"
                                YandexPlusStatus.INVALID_TOKEN -> "Токен Яндекс.Музыки невалиден"
                                YandexPlusStatus.NO_TOKEN -> "Токен Яндекса не указан"
                                YandexPlusStatus.UNKNOWN -> "Не удалось проверить статус Яндекса"
                            }
                    }
                },
            ) {
                Text("Проверить Плюс")
            }
            Text(
                text = "Статус: ${yandexPlus.name}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        OutlinedTextField(
            value = vkInput,
            onValueChange = { vkInput = it },
            label = { Text("Токен ВК (Kate / audio)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                persistInputs()
                scope.launch {
                    vkProvider
                        .checkTokenDetailed(vkInput.trim())
                        .onSuccess { msg -> statusMessage = msg }
                        .onFailure { err -> statusMessage = err.message ?: "Ошибка проверки ВК" }
                }
            },
        ) {
            Text("Проверить ВК")
        }

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
            label = { Text("Last.fm Username (для истории волны)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = geminiKeyInput,
            onValueChange = { geminiKeyInput = it },
            label = { Text("Gemini API Key (AI Studio)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FilterGroupTitle("Режим моделей Gemini (автопереключение при 429/503)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonFilterChip(
                selected = cfg.geminiModelMode == CustomAppConfig.MODEL_MODE_AUTO,
                label = "AUTO (каскад)",
                accentColor = PurpleNeonPrimary,
                onClick = { configStore.update { it.copy(geminiModelMode = CustomAppConfig.MODEL_MODE_AUTO) } },
            )
            GeminiClient.DEFAULT_MODELS.forEach { modelName ->
                NeonFilterChip(
                    selected = cfg.geminiModelMode == modelName,
                    label = modelName.removePrefix("gemini-"),
                    accentColor = PurpleNeonPrimary,
                    onClick = { configStore.update { it.copy(geminiModelMode = modelName) } },
                )
            }
        }
        if (!activeModel.isNullOrBlank()) {
            Text(
                text = "Последняя рабочая модель: $activeModel",
                style = MaterialTheme.typography.bodySmall,
                color = PurpleNeonSoft,
            )
        }

        OutlinedButton(
            onClick = {
                persistInputs()
                scope.launch {
                    geminiClient
                        .testConnection(geminiKeyInput.trim())
                        .onSuccess { used -> statusMessage = "Gemini OK (модель: $used)" }
                        .onFailure { err -> statusMessage = err.message ?: "Ошибка Gemini" }
                }
            },
        ) {
            Text("Проверить Gemini")
        }

        Button(
            onClick = {
                persistInputs()
                statusMessage = "Ключи и настройки сохранены"
            },
            colors = ButtonDefaults.buttonColors(containerColor = PurpleNeonPrimary, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить ключи")
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        FilterGroupTitle("JSON-бэкап ключей и настроек")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    persistInputs()
                    val exported = configStore.exportToJson()
                    jsonBackupInput = exported
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("metrolist_custom_json", exported))
                    statusMessage = "JSON скопирован в буфер"
                },
            ) {
                Icon(MetrolistIcons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Экспорт JSON")
            }
            OutlinedButton(
                onClick = {
                    configStore
                        .importFromJson(jsonBackupInput)
                        .onSuccess { imported ->
                            yandexInput = imported.yandexToken
                            vkInput = imported.vkToken
                            lfmKeyInput = imported.lastFmApiKey
                            lfmSecretInput = imported.lastFmSecret
                            lfmUserInput = imported.lastFmUsername
                            geminiKeyInput = imported.geminiApiKey
                            statusMessage = "JSON импортирован"
                        }.onFailure { err ->
                            statusMessage = "Ошибка JSON: ${err.message}"
                        }
                },
            ) {
                Icon(MetrolistIcons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Импорт JSON")
            }
        }

        OutlinedTextField(
            value = jsonBackupInput,
            onValueChange = { jsonBackupInput = it },
            label = { Text("JSON конфиг (вставь сюда для импорта или нажми Экспорт)") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text = statusMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = PurpleNeonSoft,
            )
        }

        TextButton(onClick = onOpenClassicHome) {
            Text("Открыть стандартную ленту YTM", color = Color.White.copy(alpha = 0.5f))
        }

        Spacer(modifier = Modifier.height(20.dp))
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
            color = Color.White,
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
    modifier: Modifier = Modifier,
) {
    var isLoading by remember(query) { mutableStateOf(true) }
    var result by remember(query) { mutableStateOf<AiPlaylistResult?>(null) }
    var errorText by remember(query) { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        isLoading = true
        errorText = null
        result = null
        aiWaveEngine
            .generateCustomPlaylist(query)
            .onSuccess { built -> result = built }
            .onFailure { err -> errorText = err.message ?: "Не удалось собрать подборку" }
        isLoading = false
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, PurpleNeonPrimary.copy(alpha = 0.35f)),
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
                    tint = PurpleNeonSoft,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun rememberNeonAccentColor(
    mode: NeonColorMode,
    artworkUrl: String?,
): Color =
    remember(mode, artworkUrl) {
        when (mode) {
            NeonColorMode.PURPLE -> PurpleNeonPrimary
            NeonColorMode.CYAN -> Color(0xFF00E5FF)
            NeonColorMode.CRIMSON -> Color(0xFFFF2E63)
            NeonColorMode.AUTO -> {
                if (artworkUrl.isNullOrBlank()) {
                    PurpleNeonPrimary
                } else {
                    val hash = artworkUrl.hashCode()
                    val hueShift = ((hash and 0xFF) / 255f)
                    lerp(PurpleNeonPrimary, PurpleNeonGlow, hueShift)
                }
            }
        }
    }

private fun listVideoUrisInFolder(
    context: Context,
    treeUri: Uri,
): List<Uri> =
    runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = mutableListOf<Uri>()
        context.contentResolver
            .query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx) else continue
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().lowercase() else ""
                    val isVideo =
                        mime.startsWith("video/") ||
                            name.endsWith(".mp4") ||
                            name.endsWith(".webm") ||
                            name.endsWith(".mkv")
                    if (isVideo) {
                        out += DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    }
                }
            }
        out
    }.getOrDefault(emptyList())
