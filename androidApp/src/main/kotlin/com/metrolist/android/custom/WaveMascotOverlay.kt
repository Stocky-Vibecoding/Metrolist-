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

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val playbackResource by playerConnection?.currentPlaybackResource?.collectAsState(initial = null) ?: remember { mutableStateOf(null) }

    val likedIds by SongLikeState.likedSongIdsForDisplay.collectAsState(initial = SongLikeState.likedSongIds)
    val isCurrentLiked = currentSong?.id?.let { it in likedIds } == true

    var bassEnergy by remember { mutableFloatStateOf(0f) }
    var beatKick by remember { mutableFloatStateOf(0f) }
    var mascotPhrase by remember { mutableStateOf<String?>(null) }
    var showWaveSettingsSheet by remember { mutableStateOf(false) }
    var showEngineSettingsSheet by remember { mutableStateOf(false) }

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

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = statusBarTop + 8.dp, bottom = bottomInset + 12.dp, start = 16.dp, end = 16.dp),
    ) {
        val isWideLayout = maxWidth >= 600.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Верхняя панель в родном стиле Metrolist
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
                    NativeSourceBadge(clientName = playbackResource?.clientName)
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
                        WaveSettingsSlider(
                            config = cfg,
                            onOpenFullSettings = { showWaveSettingsSheet = true },
                            onSelectMood = { mood ->
                                configStore.update { it.copy(waveMood = mood) }
                                refreshWave()
                            },
                        )

                        WaveBottomStatusAndPlayer(
                            currentSong = currentSong,
                            isLiked = isCurrentLiked,
                            statusOrPhrase = waveStatus ?: mascotPhrase,
                            activeModel = activeModel,
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
                    // Центр: кнопка волны / слот маскота с амбиент-свечением (тап = Play/Pause)
                    AmbientWaveButton(
                        isPlaying = isPlaying,
                        isGenerating = isGenerating,
                        bassEnergy = bassEnergy,
                        beatKick = beatKick,
                        size = 248.dp,
                        onClick = ::startOrToggleWave,
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Слайдер быстрой настройки волны под центром
                    WaveSettingsSlider(
                        config = cfg,
                        onOpenFullSettings = { showWaveSettingsSheet = true },
                        onSelectMood = { mood ->
                            configStore.update { it.copy(waveMood = mood) }
                            refreshWave()
                        },
                    )

                    // Ниже: статус и карточка активного плеера
                    WaveBottomStatusAndPlayer(
                        currentSong = currentSong,
                        isLiked = isCurrentLiked,
                        statusOrPhrase = waveStatus ?: mascotPhrase,
                        activeModel = activeModel,
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
        // Мягкое амбиент-свечение в цветах текущей темы / обложки
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

        // Интерактивный центр (тап = Play/Pause волны; на Этапе 3 здесь встанет встроенный маскот)
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
private fun WaveSettingsSlider(
    config: CustomAppConfig,
    onOpenFullSettings: () -> Unit,
    onSelectMood: (WaveMood) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = false,
            onClick = onOpenFullSettings,
            leadingIcon = {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = {
                Text(
                    text = "${config.waveCharacter.label} ·${config.waveLanguage.label}",
                    maxLines = 1,
                )
            },
        )

        WaveMood.entries.forEach { mood ->
            FilterChip(
                selected = config.waveMood == mood,
                onClick = { onSelectMood(mood) },
                label = { Text(mood.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun WaveBottomStatusAndPlayer(
    currentSong: SongItem?,
    isLiked: Boolean,
    statusOrPhrase: String?,
    activeModel: String?,
    onToggleLike: (SongItem) -> Unit,
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

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
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
                        url = song.thumbnail,
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
private fun NativeSourceBadge(clientName: String?) {
    val label =
        when (clientName) {
            "yandex" -> "Я.Музыка"
            "vk" -> "VK"
            null, "" -> return
            else -> "YTM"
        }
    Surface(
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
            title = "Приоритет ВК для Explicit-треков",
            checked = cfg.preferVkForExplicit,
            onCheckedChange = { v -> configStore.update { it.copy(preferVkForExplicit = v) } },
        )
        SettingSwitchRow(
            title = "Открывать вкладку «Волна» при старте",
            checked = cfg.useWaveAsHome,
            onCheckedChange = { v -> configStore.update { it.copy(useWaveAsHome = v) } },
        )

        FilterGroupTitle("Треков в подборках ИИ: ${cfg.aiPlaylistSize}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 20, 25, 35).forEach { size ->
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            FilterChip(
                selected = cfg.geminiModelMode == CustomAppConfig.MODEL_MODE_AUTO,
                onClick = { configStore.update { it.copy(geminiModelMode = CustomAppConfig.MODEL_MODE_AUTO) } },
                label = { Text("AUTO (каскад)", maxLines = 1) },
            )
            GeminiClient.DEFAULT_MODELS.forEach { modelName ->
                FilterChip(
                    selected = cfg.geminiModelMode == modelName,
                    onClick = { configStore.update { it.copy(geminiModelMode = modelName) } },
                    label = { Text(modelName.removePrefix("gemini-"), maxLines = 1) },
                )
            }
        }
        if (!activeModel.isNullOrBlank()) {
            Text(
                text = "Последняя рабочая модель: $activeModel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сохранить ключи")
        }

        HorizontalDivider()

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
                color = MaterialTheme.colorScheme.primary,
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
