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
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PurpleNeonPrimary = Color(0xFF9D4EDD)
private val PurpleNeonGlow = Color(0xFF7B2CBF)
private val PurpleNeonSoft = Color(0xFFC77DFF)

@OptIn(ExperimentalMaterial3Api::class)
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
    var mascotPhrase by remember { mutableStateOf<String?>(null) }
    var showWaveSettingsSheet by remember { mutableStateOf(false) }
    var showEngineSettingsSheet by remember { mutableStateOf(false) }

    val accentColor = rememberNeonAccentColor(cfg.neonColorMode, currentSong?.thumbnail)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            while (isActive) {
                bassEnergy = (bassEnergy * 0.84f).coerceAtLeast(0f)
                if (bassEnergy < 0.01f) break
                delay(32L)
            }
            return@LaunchedEffect
        }
        com.metrolist.shared.equalizer.AudioSpectrumMeter.addConsumer()
        try {
            while (isActive) {
                val levels = com.metrolist.shared.equalizer.AudioSpectrumMeter.levels()
                val b0 = levels.getOrElse(0) { 0f }
                val b1 = levels.getOrElse(1) { 0f }
                val b2 = levels.getOrElse(2) { 0f }
                val rawBass = ((b0 * 0.45f) + (b1 * 0.35f) + (b2 * 0.20f)).coerceIn(0f, 1f)
                bassEnergy = if (rawBass > bassEnergy) rawBass else (bassEnergy * 0.78f + rawBass * 0.22f)
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
            // Компактная верхняя панель без лишнего шума
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Лента",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onSwitchToClassicHome)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    IconButton(onClick = { showEngineSettingsSheet = true }) {
                        Icon(
                            imageVector = MetrolistIcons.Outlined.Settings,
                            contentDescription = "Настройки движка",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
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
                        MascotWithBassNeon(
                            mascotEnabled = cfg.mascotEnabled,
                            folderUri = cfg.mascotFolderUri,
                            accentColor = accentColor,
                            bassEnergy = bassEnergy,
                            isPlaying = isPlaying,
                            size = 220.dp,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1.15f),
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
                    MascotWithBassNeon(
                        mascotEnabled = cfg.mascotEnabled,
                        folderUri = cfg.mascotFolderUri,
                        accentColor = accentColor,
                        bassEnergy = bassEnergy,
                        isPlaying = isPlaying,
                        size = 216.dp,
                    )

                    Spacer(modifier = Modifier.height(22.dp))

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

    // Главная кнопка Play / Pause
    Surface(
        onClick = onPlayPauseClick,
        enabled = !isGenerating,
        shape = CircleShape,
        color = accentColor,
        contentColor = Color.White,
        shadowElevation = 10.dp,
        modifier = Modifier.size(72.dp).scale(buttonScale),
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

    // Небольшая капсула настройки волны под кнопкой Play
    Surface(
        onClick = onOpenWaveSettings,
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFF1B1626),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.42f)),
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

    // Строка воспроизведения появляется при наличии активного трека
    AnimatedVisibility(visible = currentSong != null) {
        val song = currentSong ?: return@AnimatedVisibility
        val artistLine = song.artists.joinToString(", ") { it.name }.trim()

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF15121E),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
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
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
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
            color = Color.White.copy(alpha = 0.65f),
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
            color = PurpleNeonSoft.copy(alpha = 0.65f),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MascotWithBassNeon(
    mascotEnabled: Boolean,
    folderUri: String,
    accentColor: Color,
    bassEnergy: Float,
    isPlaying: Boolean,
    size: Dp,
) {
    val context = LocalContext.current
    val pulse = bassEnergy.coerceIn(0f, 1f)
    val glowAlpha = (0.28f + pulse * 0.55f).coerceIn(0f, 0.9f)
    val ringAlpha = (0.45f + pulse * 0.50f).coerceIn(0f, 1f)
    val scaleFactor = 1f + (pulse * 0.06f)

    val videos =
        remember(folderUri, mascotEnabled) {
            if (!mascotEnabled || folderUri.isBlank()) emptyList()
            else listVideoUrisInFolder(context, folderUri.toUri())
        }
    var videoIndex by remember(videos) { mutableStateOf(0) }

    val exoPlayer =
        remember(videos) {
            if (videos.isEmpty()) null
            else {
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

    LaunchedEffect(exoPlayer, isPlaying, videos) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (videos.isNotEmpty() && player.currentMediaItemIndex != videoIndex) {
            player.seekTo(videoIndex.coerceIn(0, videos.lastIndex), 0L)
        }
        player.playWhenReady = isPlaying
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size + 44.dp)
                .drawBehind {
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val baseRadius = (size.toPx() * 0.52f) * (1f + pulse * 0.14f)
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        accentColor.copy(alpha = glowAlpha),
                                        accentColor.copy(alpha = glowAlpha * 0.35f),
                                        Color.Transparent,
                                    ),
                                center = center,
                                radius = baseRadius * 1.38f,
                            ),
                        radius = baseRadius * 1.38f,
                        center = center,
                    )
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(size)
                    .scale(scaleFactor)
                    .clip(CircleShape)
                    .background(Color(0xFF0D0A14))
                    .border(
                        width = (1.5f + pulse * 2.5f).dp,
                        color = accentColor.copy(alpha = ringAlpha),
                        shape = CircleShape,
                    )
                    .clickable(enabled = videos.size > 1) {
                        videoIndex = (videoIndex + 1) % videos.size
                    },
        ) {
            if (exoPlayer != null && videos.isNotEmpty()) {
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
            } else {
                // Минималистичный басовый неон-символ, если папка маскота не задана
                Box(
                    modifier =
                        Modifier
                            .size(size * 0.42f)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.22f + pulse * 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(34.dp),
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

@OptIn(ExperimentalLayoutApi::class)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomEngineSettingsContent(
    configStore: CustomConfigStore,
    yandexProvider: YandexMusicProvider,
    vkProvider: VkMusicProvider,
    geminiClient: GeminiClient,
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
            title = "Показывать маскота с басовым неоном",
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Треков в подборках ИИ: ${cfg.aiPlaylistSize}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 35).forEach { size ->
                    NeonFilterChip(
                        selected = cfg.aiPlaylistSize == size,
                        label = "$size",
                        accentColor = PurpleNeonPrimary,
                        onClick = { configStore.update { it.copy(aiPlaylistSize = size) } },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(MetrolistIcons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (cfg.mascotFolderUri.isBlank()) "Выбрать папку с видео маскота" else "Сменить папку видео маскота",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    val ok = vkProvider.checkToken(vkInput.trim())
                    statusMessage = if (ok) "Токен ВК работает" else "Токен ВК не прошёл проверку"
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

        // JSON бэкап / импорт
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
                Icon(MetrolistIcons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
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
                if (artworkUrl.isNullOrBlank()) PurpleNeonPrimary
                else {
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
