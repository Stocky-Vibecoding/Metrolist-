@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.metrolist.android.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val isGenerating by aiWaveEngine.isGeneratingWave.collectAsState()
    val waveStatus by aiWaveEngine.statusText.collectAsState()
    val activeModel by geminiClient.activeModel.collectAsState()
    val diagnosticsRevision by ResolverDiagnostics.revision.collectAsState()

    val phraseBank = remember(context, geminiClient) { MascotPhraseBank(context.applicationContext, geminiClient) }

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val queueTitle by playerConnection?.queueTitle?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

    val isPlayingWave = queueTitle?.startsWith("Моя волна") == true
    val isOutsideWave = currentSong != null && !isPlayingWave

    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)
    val isCurrentLiked = currentSong?.id?.let { it in likedIds } == true

    var bassEnergy by remember { mutableFloatStateOf(0f) }
    var beatKick by remember { mutableFloatStateOf(0f) }
    var mascotPhrase by remember { mutableStateOf<String?>(null) }
    var lastPhraseSongId by remember { mutableStateOf<String?>(null) }
    var showWaveSettingsSheet by remember { mutableStateOf(false) }
    var showEngineSettingsSheet by remember { mutableStateOf(false) }
    var showSourcePickerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            while (isActive) {
                bassEnergy = (bassEnergy * 0.85f).coerceAtLeast(0f)
                beatKick = (beatKick * 0.80f).coerceAtLeast(0f)
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
                val rawBass =
                    (
                        (levels.getOrElse(0) { 0f } * 0.45f) +
                            (levels.getOrElse(1) { 0f } * 0.35f) +
                            (levels.getOrElse(2) { 0f } * 0.20f)
                    ).coerceIn(0f, 1f)
                bassEnergy = if (rawBass > bassEnergy) rawBass else (bassEnergy * 0.78f + rawBass * 0.22f)

                val delta = (rawBass - avgBass).coerceAtLeast(0f)
                avgBass = avgBass * 0.90f + rawBass * 0.10f
                val kickNow = (delta * 3.2f).coerceIn(0f, 1f)
                beatKick = if (kickNow > beatKick) kickNow else (beatKick * 0.76f)
                delay(32L)
            }
        } finally {
            com.metrolist.shared.equalizer.AudioSpectrumMeter.removeConsumer()
        }
    }

    LaunchedEffect(currentSong?.id, isPlaying) {
        val song = currentSong
        if (song == null) {
            mascotPhrase = null
            lastPhraseSongId = null
            return@LaunchedEffect
        }
        if (song.id != lastPhraseSongId) {
            mascotPhrase = null
            lastPhraseSongId = song.id
        } else if (mascotPhrase != null) {
            return@LaunchedEffect
        }
        if (!isPlaying) return@LaunchedEffect
        delay(1_200L)
        if (currentSong?.id == song.id) {
            val artist = song.artists.joinToString(", ") { it.name }.trim()
            mascotPhrase =
                phraseBank.nextPhraseForTrack(
                    artist = artist,
                    title = song.title,
                    moodKey = cfg.waveMood.name,
                    moodLabel = cfg.waveMood.label,
                    hasGeminiKey = cfg.geminiApiKey.isNotBlank(),
                )
        }
    }

    fun launchFreshWave() {
        scope.launch {
            aiWaveEngine
                .generateWaveSongs()
                .onSuccess { songs ->
                    playerConnection?.playQueue(
                        songs = songs,
                        queueTitle = "Моя волна",
                        finite = false,
                        forceStartPlaying = true,
                    )
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Не удалось запустить волну", Toast.LENGTH_SHORT).show()
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

            // Плашка с крестиком сверху (как в Яндексе), если сейчас играет поиск / альбом / плейлист
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
                        onResetToWave = ::launchFreshWave,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                    statusOrPhrase = waveStatus ?: mascotPhrase,
                    activeModel = activeModel,
                    onToggleLike = { song ->
                        val willLike = !isCurrentLiked
                        val artist = song.artists.joinToString(", ") { it.name }.trim()
                        SongLikeState.toggleLike(song)
                        if (willLike) {
                            configStore.likeTrack(artist = artist, title = song.title, songId = song.id)
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
                        AmbientWaveButton(
                            isPlaying = isPlaying,
                            isGenerating = isGenerating,
                            bassEnergy = bassEnergy,
                            beatKick = beatKick,
                            size = 240.dp,
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
                    AmbientWaveButton(
                        isPlaying = isPlaying,
                        isGenerating = isGenerating,
                        bassEnergy = bassEnergy,
                        beatKick = beatKick,
                        size = 248.dp,
                        onClick = ::startOrToggleWave,
                    )

                    Spacer(modifier = Modifier.height(18.dp))

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
                onUpdate = { transform -> configStore.update(transform) },
                onApplyAndWave = {
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
                                val alreadyOnVk =
                                    pref == TrackSourcePreference.VK &&
                                        (currentOverride == TrackSourcePreference.VK || playbackResource?.clientName == "vk")
                                if (alreadyOnVk) {
                                    HybridStreamResolver.cycleVkVariant(song.id, artist, song.title)
                                } else {
                                    HybridStreamResolver.resetVkVariant(song.id)
                                    HybridStreamResolver.invalidateTrack(song.id, artist, song.title)
                                }
                                if (pref == TrackSourcePreference.VK && playbackResource?.clientName == "yandex") {
                                    LocalCensorshipAdvisor.rememberCensoredArtist(artist)
                                }
                                configStore.setTrackSourceOverride(
                                    videoId = song.id,
                                    artist = artist,
                                    title = song.title,
                                    preference = pref,
                                )
                                showSourcePickerSheet = false
                                playerConnection?.reloadCurrentSongForSourceSwitch()
                                Toast.makeText(
                                    context,
                                    if (alreadyOnVk) "Пробую другую версию из VK…" else "Переключаю на ${pref.label}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            label = { Text(pref.label) },
                        )
                    }
                }
                Text(
                    text = "Повторный тап по VK пробует другую версию трека (помогает обойти цензуру).",
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
            )
        }
    }
}

@Composable
private fun ActiveExternalContextPill(
    label: String,
    onResetToWave: () -> Unit,
) {
    Surface(
        onClick = onResetToWave,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).widthIn(max = 320.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
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
private fun AmbientWaveButton(
    isPlaying: Boolean,
    isGenerating: Boolean,
    bassEnergy: Float,
    beatKick: Float,
    size: Dp,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer

    val pulse = bassEnergy.coerceIn(0f, 1f)
    val kick = beatKick.coerceIn(0f, 1f)

    val infiniteTransition = rememberInfiniteTransition(label = "waveAmbient")
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "idleBreath",
    )

    val centerScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f + pulse * 0.06f + kick * 0.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "centerScale",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxR = this.size.minDimension / 2f
            val energy = if (isPlaying) pulse else idleBreath * 0.28f

            val outerR = maxR * (0.78f + energy * 0.18f + kick * 0.06f)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                primaryColor.copy(alpha = (0.34f + energy * 0.28f + kick * 0.12f).coerceIn(0f, 0.72f)),
                                tertiaryColor.copy(alpha = (0.14f + energy * 0.16f).coerceIn(0f, 0.42f)),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = outerR,
                    ),
                radius = outerR,
                center = center,
            )

            val innerR = maxR * (0.50f + energy * 0.12f)
            drawCircle(
                brush =
                    Brush.radialGradient(
                        colors =
                            listOf(
                                primaryColor.copy(alpha = (0.28f + energy * 0.22f).coerceIn(0f, 0.60f)),
                                Color.Transparent,
                            ),
                        center = center,
                        radius = innerR,
                    ),
                radius = innerR,
                center = center,
            )
        }

        Surface(
            onClick = onClick,
            enabled = !isGenerating,
            shape = CircleShape,
            color = containerColor.copy(alpha = 0.92f),
            contentColor = onContainerColor,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f)),
            modifier = Modifier.size(112.dp).scale(centerScale),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        color = onContainerColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) MetrolistIcons.Filled.Pause else MetrolistIcons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Запустить волну",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
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
    statusOrPhrase: String?,
    activeModel: String?,
    onToggleLike: (SongItem) -> Unit,
    onDislikeClick: (SongItem) -> Unit,
    onNextClick: () -> Unit,
    onOpenFullPlayer: () -> Unit,
) {
    if (!statusOrPhrase.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = statusOrPhrase,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    AnimatedVisibility(
        visible = currentSong != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val song = currentSong ?: return@AnimatedVisibility
        val artistLine = song.artists.joinToString(", ") { it.name }.trim()
        val hdCover = ResolverDiagnostics.hdCoverFor(song.id) ?: song.thumbnail

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(18.dp))
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
                    Spacer(modifier = Modifier.width(12.dp))
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
                    IconButton(onClick = { onDislikeClick(song) }) {
                        DislikeBanIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onToggleLike(song) }) {
                        Icon(
                            imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                            contentDescription = "Лайк",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onNextClick) {
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

    if (!activeModel.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "ИИ: $activeModel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
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

        Button(
            onClick = onApplyAndWave,
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by configStore.config.collectAsState()
    val activeModel by geminiClient.activeModel.collectAsState()
    val resolverLogs by ResolverDiagnostics.logs.collectAsState()
    val cacheStats by CustomMediaCacheManager.stats.collectAsState()
    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)

    var yandexInput by remember(cfg.yandexToken) { mutableStateOf(cfg.yandexToken) }
    var vkInput by remember(cfg.vkToken) { mutableStateOf(cfg.vkToken) }
    var lfmKeyInput by remember(cfg.lastFmApiKey) { mutableStateOf(cfg.lastFmApiKey) }
    var lfmSecretInput by remember(cfg.lastFmSecret) { mutableStateOf(cfg.lastFmSecret) }
    var lfmUserInput by remember(cfg.lastFmUsername) { mutableStateOf(cfg.lastFmUsername) }
    var geminiKeyInput by remember(cfg.geminiApiKey) { mutableStateOf(cfg.geminiApiKey) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingApis by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

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

                        val toRestore = imported.likedTracksBackup.filter { it.title.isNotBlank() && it.id !in SongLikeState.likedSongIds }
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
                                            SongLikeState.toggleLike(found)
                                            if (imported.cacheWaveAndDownloadLikes) {
                                                CustomMediaCacheManager.downloadLikedTrack(found)
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

        SettingSwitchRow(
            title = "Гибридный резолвер (Яндекс / ВК / YTM)",
            checked = cfg.hybridResolverEnabled,
            onCheckedChange = { v -> configStore.update { it.copy(hybridResolverEnabled = v) } },
        )
        SettingSwitchRow(
            title = "Антицензура (Explicit и РФ-рэп → VK, 0 запросов ИИ)",
            checked = cfg.preferVkForExplicit,
            onCheckedChange = { v -> configStore.update { it.copy(preferVkForExplicit = v) } },
        )
        SettingSwitchRow(
            title = "Кэш волны (до 2 ГБ) и автоскачивание лайков",
            checked = cfg.cacheWaveAndDownloadLikes,
            onCheckedChange = { v -> configStore.update { it.copy(cacheWaveAndDownloadLikes = v) } },
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

        FilterGroupTitle("Кэш волны (лимит 2 ГБ) и скачанные лайки")
        Text(
            text =
                "Кэш волны: ${CustomMediaCacheManager.formatSize(cacheStats.waveCacheBytes)} / 2 ГБ (${cacheStats.waveCacheCount} треков)\n" +
                    "Скачанные лайки: ${CustomMediaCacheManager.formatSize(cacheStats.likedDownloadsBytes)} (${cacheStats.likedDownloadsCount} треков)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    CustomMediaCacheManager.clearWaveCache()
                    statusMessage = "Кэш волны очищен"
                },
            ) {
                Text("Очистить кэш")
            }
            OutlinedButton(
                onClick = {
                    CustomMediaCacheManager.clearLikedDownloads()
                    statusMessage = "Скачанные лайки очищены"
                },
            ) {
                Text("Очистить скачанное")
            }
        }

        HorizontalDivider()

        val mutedCount = cfg.artistReputation.values.count { it.isAutoMuted }
        val overridesCount = cfg.trackSourceOverrides.keys.count { !it.startsWith("id::") }
        val savedLikesCount = maxOf(cfg.likedTracksBackup.size, likedIds.size)
        FilterGroupTitle("Память волны и источников")
        Text(
            text = "Лайков: $savedLikesCount • Дизлайков: ${cfg.dislikedTracks.size} • Скрытых артистов: $mutedCount • Источников: $overridesCount",
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

        FilterGroupTitle("Бэкап в файл .json (ключи, лайки, дизлайки, источники)")
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
