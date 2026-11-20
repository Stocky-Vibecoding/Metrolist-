@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.metrolist.android.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    val phraseBank = remember(context, geminiClient) { MascotPhraseBank(context.applicationContext, geminiClient) }

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

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

    fun startOrToggleWave() {
        if (currentSong != null && playerConnection != null) {
            playerConnection.togglePlayPause()
            return
        }
        scope.launch {
            aiWaveEngine
                .generateWaveSongs()
                .onSuccess { songs ->
                    playerConnection?.playQueue(songs, queueTitle = "Моя волна", finite = false)
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Не удалось запустить волну", Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun refreshWave() {
        scope.launch {
            aiWaveEngine
                .generateWaveSongs()
                .onSuccess { songs ->
                    playerConnection?.playQueue(songs, queueTitle = "Моя волна", finite = false)
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Не удалось обновить волну", Toast.LENGTH_SHORT).show()
                }
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val qualityBadge =
        ResolverDiagnostics.qualityLabelFor(
            videoId = currentSong?.id,
            clientName = playbackResource?.clientName,
            bitrate = playbackResource?.bitrate,
            mimeType = playbackResource?.mimeType,
        )

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

            Spacer(modifier = Modifier.height(8.dp))

            val playerBlock: @Composable () -> Unit = {
                WaveBottomStatusAndPlayer(
                    currentSong = currentSong,
                    isLiked = isCurrentLiked,
                    statusOrPhrase = waveStatus ?: mascotPhrase,
                    activeModel = activeModel,
                    onToggleLike = { song ->
                        val willLike = !isCurrentLiked
                        SongLikeState.toggleLike(song)
                        if (willLike) aiWaveEngine.recordLike(song)
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
                        WaveFilterPillButton(
                            label = cfg.waveFilterSummary(),
                            onClick = { showWaveSettingsSheet = true },
                        )
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

                    WaveFilterPillButton(
                        label = cfg.waveFilterSummary(),
                        onClick = { showWaveSettingsSheet = true },
                    )

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
                    refreshWave()
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
                    text = if (artist.isNotBlank()) "$artist —${song.title}" else song.title,
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
                                    "Переключаю на ${pref.label}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            label = { Text(pref.label) },
                        )
                    }
                }
                Text(
                    text = "Выбор сохранится именно для этого трека и применится сразу без сброса позиции.",
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
    val containerColor =
