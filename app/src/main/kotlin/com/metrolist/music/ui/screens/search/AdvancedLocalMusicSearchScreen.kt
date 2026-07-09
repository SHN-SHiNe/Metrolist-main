package com.metrolist.music.ui.screens.search

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_HEADER
import com.metrolist.music.constants.CONTENT_TYPE_SONG
import com.metrolist.music.db.entities.LocalMusicEntity
import com.metrolist.music.db.entities.LocalSong
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.localmusic.AdvancedEmotionMetric
import com.metrolist.music.localmusic.AdvancedKeyFilter
import com.metrolist.music.localmusic.AdvancedLocalMusicSearch
import com.metrolist.music.localmusic.AdvancedLocalMusicSearchCandidate
import com.metrolist.music.localmusic.AdvancedLocalMusicSearchCriteria
import com.metrolist.music.localmusic.AdvancedLocalMusicSearchResult
import com.metrolist.music.localmusic.AdvancedNumericFilter
import com.metrolist.music.localmusic.advancedKeyDisplayName
import com.metrolist.music.localmusic.advancedKeyToCamelot
import com.metrolist.music.localmusic.advancedNormalizeCamelotNumber
import com.metrolist.music.localmusic.canonicalMusicKey
import com.metrolist.music.localmusic.normalizeLocalMusicMetric
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.flow.drop
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AdvancedLocalMusicSearchScreen(
    query: String,
    navController: NavController,
    pureBlack: Boolean,
) {
    val database = LocalDatabase.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val localSongs by database.localSongs().collectAsStateWithLifecycle(initialValue = emptyList())
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val queueTitle = "高级搜索"

    var bpmEnabled by rememberSaveable { mutableStateOf(false) }
    var bpmValue by rememberSaveable { mutableFloatStateOf(120f) }
    var bpmTolerance by rememberSaveable { mutableIntStateOf(5) }
    var keyEnabled by rememberSaveable { mutableStateOf(false) }
    var keyNumber by rememberSaveable { mutableIntStateOf(8) }
    var keyMode by rememberSaveable { mutableStateOf("B") }
    var keyTolerance by rememberSaveable { mutableIntStateOf(0) }
    var emotionTolerance by rememberSaveable { mutableFloatStateOf(0.05f) }
    val emotionFilters =
        remember {
            mutableStateMapOf<AdvancedEmotionMetric, AdvancedNumericFilter>().apply {
                AdvancedEmotionMetric.entries.forEach {
                    put(it, AdvancedNumericFilter(target = 0.5f, tolerance = 0.05f))
                }
            }
        }
    fun normalizedEmotionFilters() =
        AdvancedLocalMusicSearch.withEmotionTolerance(emotionFilters.toMap(), emotionTolerance)

    fun draftCriteria() =
        AdvancedLocalMusicSearchCriteria(
            text = query,
            bpm = AdvancedNumericFilter(enabled = bpmEnabled, target = bpmValue, tolerance = bpmTolerance.toFloat()),
            key = AdvancedKeyFilter(enabled = keyEnabled, number = keyNumber, mode = keyMode, tolerance = keyTolerance),
            emotions = normalizedEmotionFilters(),
        )
    var appliedCriteria by remember { mutableStateOf(draftCriteria()) }
    var hasAppliedCriteria by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { keyboardController?.hide() }
    }

    val pendingCriteria = draftCriteria()
    val hasPendingCriteria = pendingCriteria != appliedCriteria
    val candidates =
        remember(localSongs) {
            localSongs.map { it.toAdvancedSearchCandidate() }
        }
    val rankedItems =
        remember(localSongs, candidates, appliedCriteria, hasAppliedCriteria) {
            if (!hasAppliedCriteria) return@remember emptyList()
            val byId = localSongs.associateBy { it.localMusic.songId }
            AdvancedLocalMusicSearch.rank(candidates, appliedCriteria).mapNotNull { result ->
                byId[result.songId]?.let { AdvancedLocalMusicSearchItem(localSong = it, result = result) }
            }
        }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (pureBlack) Color.Black else MaterialTheme.colorScheme.background),
        contentPadding =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues(),
    ) {
        item(key = "advanced_filters", contentType = CONTENT_TYPE_HEADER) {
            SearchControlPanel(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                AdvancedFilterPanel(
                    resultCount = rankedItems.size,
                    hasAppliedCriteria = hasAppliedCriteria,
                    hasPendingCriteria = hasPendingCriteria,
                    bpmEnabled = bpmEnabled,
                    bpmValue = bpmValue,
                    bpmTolerance = bpmTolerance,
                    onBpmEnabledChange = { bpmEnabled = it },
                    onBpmValueChange = { bpmValue = it },
                    onBpmToleranceChange = { bpmTolerance = it },
                    keyEnabled = keyEnabled,
                    keyNumber = keyNumber,
                    keyMode = keyMode,
                    keyTolerance = keyTolerance,
                    onKeyEnabledChange = { keyEnabled = it },
                    onKeyChange = { number, mode ->
                        keyNumber = number
                        keyMode = mode
                        keyEnabled = true
                    },
                    onKeyToleranceChange = { keyTolerance = it },
                    emotionTolerance = emotionTolerance,
                    onEmotionToleranceChange = { tolerance ->
                        val normalizedTolerance = tolerance.coerceIn(0f, 1f)
                        emotionTolerance = normalizedTolerance
                        AdvancedEmotionMetric.entries.forEach { metric ->
                            val filter = emotionFilters.getValue(metric)
                            emotionFilters[metric] = filter.copy(tolerance = normalizedTolerance)
                        }
                    },
                    emotionFilters = emotionFilters,
                    onEmotionFilterChange = { metric, filter ->
                        emotionFilters[metric] = filter.copy(tolerance = emotionTolerance)
                    },
                    onApplyFilters = {
                        keyboardController?.hide()
                        appliedCriteria = pendingCriteria
                        hasAppliedCriteria = true
                    },
                )
            }
        }

        if (!hasAppliedCriteria) {
            item(key = "advanced_not_applied", contentType = CONTENT_TYPE_HEADER) {
                EmptyPlaceholder(
                    icon = R.drawable.advanced_search,
                    text = "设置高级筛选后执行搜索",
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        } else if (rankedItems.isEmpty()) {
            item(key = "advanced_empty", contentType = CONTENT_TYPE_HEADER) {
                EmptyPlaceholder(
                    icon = R.drawable.advanced_search,
                    text = "没有匹配的本地音乐",
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
        }

        items(
            items = rankedItems,
            key = { it.localSong.localMusic.songId },
            contentType = { CONTENT_TYPE_SONG },
        ) { item ->
            val localSong = item.localSong
            AdvancedLocalMusicItem(
                item = item,
                criteria = appliedCriteria,
                isActive = localSong.song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                onClick = {
                    if (localSong.song.id == mediaMetadata?.id) {
                        playerConnection.togglePlayPause()
                    } else {
                        val items = rankedItems.map { it.localSong.song.toMediaItem(it.localSong.localMusic.contentUri) }
                        playerConnection.playQueue(
                            ListQueue(
                                title = queueTitle,
                                items = items,
                                startIndex = items.indexOfFirst { it.mediaId == localSong.song.id },
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AdvancedFilterPanel(
    resultCount: Int,
    hasAppliedCriteria: Boolean,
    hasPendingCriteria: Boolean,
    bpmEnabled: Boolean,
    bpmValue: Float,
    bpmTolerance: Int,
    onBpmEnabledChange: (Boolean) -> Unit,
    onBpmValueChange: (Float) -> Unit,
    onBpmToleranceChange: (Int) -> Unit,
    keyEnabled: Boolean,
    keyNumber: Int,
    keyMode: String,
    keyTolerance: Int,
    onKeyEnabledChange: (Boolean) -> Unit,
    onKeyChange: (Int, String) -> Unit,
    onKeyToleranceChange: (Int) -> Unit,
    emotionTolerance: Float,
    onEmotionToleranceChange: (Float) -> Unit,
    emotionFilters: Map<AdvancedEmotionMetric, AdvancedNumericFilter>,
    onEmotionFilterChange: (AdvancedEmotionMetric, AdvancedNumericFilter) -> Unit,
    onApplyFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var emotionInputMode by rememberSaveable { mutableStateOf(EmotionInputMode.SLIDER) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter = painterResource(R.drawable.advanced_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("高级筛选", style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        buildString {
                            append("${activeFilterCount(bpmEnabled, keyEnabled, emotionFilters)} 个条件")
                            if (hasAppliedCriteria) {
                                append(" · $resultCount 首匹配")
                                if (hasPendingCriteria) append(" · 未应用")
                            } else {
                                append(" · 待执行")
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (!hasAppliedCriteria || hasPendingCriteria) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        FuzzySliderFilter(
            title = "BPM",
            enabled = bpmEnabled,
            value = bpmValue,
            valueRange = 40f..220f,
            tolerance = bpmTolerance.toFloat(),
            valueText = "${bpmValue.toInt()}",
            toleranceText = "±$bpmTolerance",
            toleranceStep = 1f,
            maxTolerance = 60f,
            onEnabledChange = onBpmEnabledChange,
            onValueChange = onBpmValueChange,
            onToleranceChange = { onBpmToleranceChange(it.toInt()) },
            modifier = Modifier.padding(top = 14.dp),
        )

        KeyWheelFilter(
            enabled = keyEnabled,
            number = keyNumber,
            mode = keyMode,
            tolerance = keyTolerance,
            onEnabledChange = onKeyEnabledChange,
            onKeyChange = onKeyChange,
            onToleranceChange = onKeyToleranceChange,
            modifier = Modifier.padding(top = 18.dp),
        )

        EmotionInputModePicker(
            mode = emotionInputMode,
            onModeChange = { emotionInputMode = it },
            modifier = Modifier.padding(top = 18.dp),
        )

        EmotionToleranceSlider(
            value = emotionTolerance,
            onValueChange = onEmotionToleranceChange,
            modifier = Modifier.padding(top = 14.dp),
        )

        when (emotionInputMode) {
            EmotionInputMode.SLIDER -> {
                AdvancedEmotionMetric.entries.forEach { metric ->
                    val filter = emotionFilters.getValue(metric)
                    FuzzySliderFilter(
                        title = metric.label,
                        enabled = filter.enabled,
                        value = filter.target,
                        valueRange = 0f..1f,
                        tolerance = emotionTolerance,
                        valueText = String.format(Locale.US, "%.3f", filter.target),
                        onEnabledChange = { onEmotionFilterChange(metric, filter.copy(enabled = it)) },
                        onValueChange = { onEmotionFilterChange(metric, filter.copy(enabled = true, target = it)) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            EmotionInputMode.RADAR -> {
                EmotionRadarFilter(
                    emotionFilters = emotionFilters,
                    onEmotionFilterChange = onEmotionFilterChange,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        Button(
            onClick = onApplyFilters,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
        ) {
            Text(
                when {
                    !hasAppliedCriteria -> "执行筛选"
                    hasPendingCriteria -> "应用筛选"
                    else -> "重新筛选"
                },
            )
        }
    }
}

private enum class EmotionInputMode {
    SLIDER,
    RADAR,
}

internal enum class RadarGestureOwner {
    PageScroll,
    RadarPoint,
}

internal fun resolveRadarGestureOwner(startedOnPoint: Boolean): RadarGestureOwner =
    if (startedOnPoint) RadarGestureOwner.RadarPoint else RadarGestureOwner.PageScroll

internal fun emotionGlowRadiusDp(tolerance: Float): Float =
    18f + tolerance.coerceIn(0f, 1f) * 90f

@Composable
private fun EmotionInputModePicker(
    mode: EmotionInputMode,
    onModeChange: (EmotionInputMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
    ) {
        EmotionInputModeButton(
            text = "滑条",
            selected = mode == EmotionInputMode.SLIDER,
            onClick = { onModeChange(EmotionInputMode.SLIDER) },
            modifier = Modifier.weight(1f),
        )
        EmotionInputModeButton(
            text = "雷达",
            selected = mode == EmotionInputMode.RADAR,
            onClick = { onModeChange(EmotionInputMode.RADAR) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmotionInputModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun EmotionRadarFilter(
    emotionFilters: Map<AdvancedEmotionMetric, AdvancedNumericFilter>,
    onEmotionFilterChange: (AdvancedEmotionMetric, AdvancedNumericFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = AdvancedEmotionMetric.entries
    val primary = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val density = LocalDensity.current

    fun axisAngle(index: Int): Float =
        (-PI / 2.0 + index * 2.0 * PI / metrics.size).toFloat()

    fun chartGeometry(
        width: Float,
        height: Float,
    ): Pair<Offset, Float> {
        val center = Offset(width / 2f, height / 2f)
        return center to min(width, height) * 0.45f
    }

    fun pointFor(
        metric: AdvancedEmotionMetric,
        width: Float,
        height: Float,
    ): Offset {
        val (center, radius) = chartGeometry(width, height)
        val index = metrics.indexOf(metric)
        val value = emotionFilters.getValue(metric).target.coerceIn(0f, 1f)
        val angle = axisAngle(index)
        return Offset(
            x = center.x + cos(angle) * radius * value,
            y = center.y + sin(angle) * radius * value,
        )
    }

    fun hitMetric(
        position: Offset,
        width: Float,
        height: Float,
        hitRadius: Float,
    ): AdvancedEmotionMetric? =
        metrics
            .map { metric -> metric to (pointFor(metric, width, height) - position).getDistance() }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= hitRadius }
            ?.first

    fun valueForPosition(
        metric: AdvancedEmotionMetric,
        position: Offset,
        width: Float,
        height: Float,
    ): Float {
        val (center, radius) = chartGeometry(width, height)
        val angle = axisAngle(metrics.indexOf(metric))
        val unitX = cos(angle)
        val unitY = sin(angle)
        val projected = ((position.x - center.x) * unitX + (position.y - center.y) * unitY) / radius
        return projected.coerceIn(0f, 1f)
    }

    fun updateMetricFromPosition(
        metric: AdvancedEmotionMetric,
        position: Offset,
        width: Float,
        height: Float,
    ) {
        val filter = emotionFilters.getValue(metric)
        onEmotionFilterChange(metric, filter.copy(enabled = true, target = valueForPosition(metric, position, width, height)))
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(400.dp)
                .pointerInput(emotionFilters) {
                    val hitRadius = with(density) { 30.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val metric = hitMetric(start, size.width.toFloat(), size.height.toFloat(), hitRadius)
                        if (resolveRadarGestureOwner(startedOnPoint = metric != null) == RadarGestureOwner.PageScroll) {
                            return@awaitEachGesture
                        }
                        val touchedMetric = metric ?: return@awaitEachGesture
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        var draggingPoint = false
                        var longPressed = false
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            if (longPressed) {
                                change.consume()
                                continue
                            }

                            val delta = change.position - start
                            when {
                                draggingPoint -> {
                                    updateMetricFromPosition(touchedMetric, change.position, size.width.toFloat(), size.height.toFloat())
                                    change.consume()
                                }

                                change.uptimeMillis - down.uptimeMillis >= longPressTimeout -> {
                                    val filter = emotionFilters.getValue(touchedMetric)
                                    onEmotionFilterChange(touchedMetric, filter.copy(enabled = !filter.enabled))
                                    longPressed = true
                                    change.consume()
                                }

                                else -> {
                                    if (delta.getDistance() > touchSlop) {
                                        draggingPoint = true
                                        updateMetricFromPosition(touchedMetric, change.position, size.width.toFloat(), size.height.toFloat())
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val (center, radius) = chartGeometry(size.width, size.height)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

            fun radarPoint(
                index: Int,
                value: Float,
            ): Offset {
                val angle = axisAngle(index)
                return Offset(
                    center.x + cos(angle) * radius * value,
                    center.y + sin(angle) * radius * value,
                )
            }

            for (ring in 1..5) {
                val path = Path()
                metrics.forEachIndexed { index, _ ->
                    val point = radarPoint(index, ring / 5f)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, color = gridColor, style = Stroke(width = 0.8.dp.toPx()))
            }

            metrics.forEachIndexed { index, _ ->
                drawLine(
                    color = gridColor,
                    start = center,
                    end = radarPoint(index, 1f),
                    strokeWidth = 0.8.dp.toPx(),
                )
            }

            val activeValues = metrics.map { metric ->
                val filter = emotionFilters.getValue(metric)
                if (filter.enabled) filter.target.coerceIn(0f, 1f) else 0f
            }
            if (activeValues.any { it > 0f }) {
                val path = Path()
                activeValues.forEachIndexed { index, value ->
                    val point = radarPoint(index, value)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, color = primary.copy(alpha = 0.18f))
                drawPath(path, color = primary.copy(alpha = 0.72f), style = Stroke(width = 1.4.dp.toPx()))
            }

            metrics.forEachIndexed { index, metric ->
                val filter = emotionFilters.getValue(metric)
                val value = filter.target.coerceIn(0f, 1f)
                val point = radarPoint(index, value)
                val angle = axisAngle(index)
                val labelAnchor = radarPoint(index, (value + 0.16f).coerceAtMost(1.16f))
                val align =
                    when {
                        labelAnchor.x > center.x + 12.dp.toPx() -> Paint.Align.LEFT
                        labelAnchor.x < center.x - 12.dp.toPx() -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                val pointColor = if (filter.enabled) primary else muted.copy(alpha = 0.62f)
                val pointRadius = 8.dp.toPx()
                if (filter.enabled) {
                    val glowRadius = emotionGlowRadiusDp(filter.tolerance).dp.toPx()
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colorStops =
                                    arrayOf(
                                        0f to pointColor.copy(alpha = 0.32f),
                                        0.36f to pointColor.copy(alpha = 0.18f),
                                        1f to pointColor.copy(alpha = 0f),
                                    ),
                                center = point,
                                radius = glowRadius,
                            ),
                        radius = glowRadius,
                        center = point,
                    )
                }
                drawCircle(
                    color = if (filter.enabled) pointColor else Color.Transparent,
                    radius = pointRadius,
                    center = point,
                )
                drawCircle(
                    color = pointColor,
                    radius = pointRadius,
                    center = point,
                    style = Stroke(width = 2.dp.toPx()),
                )

                paint.textAlign = align
                paint.color = if (filter.enabled) textColor.toArgb() else muted.toArgb()
                paint.textSize = 9.sp.toPx()
                val labelBaseline = labelAnchor.y - 4.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(metric.label, labelAnchor.x, labelBaseline, paint)
                paint.textSize = 8.sp.toPx()
                val valueText = if (filter.enabled) String.format(Locale.US, "%.2f", value) else "任意"
                val valueOffset = if (sin(angle) > 0.72f) 8.dp.toPx() else 7.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(valueText, labelAnchor.x, labelAnchor.y + valueOffset, paint)
            }
        }
    }
}

@Composable
private fun EmotionToleranceSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalized = value.coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("情绪误差范围", style = MaterialTheme.typography.labelLarge)
                Text(
                    "统一影响七项情绪筛选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "±${String.format(Locale.US, "%.3f", normalized)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = normalized,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            steps = 99,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FuzzySliderFilter(
    title: String,
    enabled: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    tolerance: Float,
    valueText: String,
    toleranceText: String? = null,
    toleranceStep: Float = 0f,
    maxTolerance: Float = Float.POSITIVE_INFINITY,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    onToleranceChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = if (enabled) valueText else "任意",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (toleranceText != null) {
                ToleranceStepper(
                    text = toleranceText,
                    enabled = enabled,
                    onDecrease = { onToleranceChange((tolerance - toleranceStep).coerceAtLeast(0f)) },
                    onIncrease = { onToleranceChange((tolerance + toleranceStep).coerceAtMost(maxTolerance)) },
                )
            }
        }
        FuzzyValueSlider(
            enabled = enabled,
            value = value,
            valueRange = valueRange,
            tolerance = tolerance,
            onEnabledChange = onEnabledChange,
            onValueChange = onValueChange,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuzzyValueSlider(
    enabled: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    tolerance: Float,
    onEnabledChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            primary
                        },
                    )
                    .clickable { onEnabledChange(false) }
                    .padding(horizontal = 10.dp),
        ) {
            Text(
                text = "任意",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(42.dp),
        ) {
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = {
                    if (!enabled) {
                        onEnabledChange(true)
                    }
                    onValueChange(it)
                },
                valueRange = valueRange,
                interactionSource = interactionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        modifier = Modifier.alpha(if (enabled) 1f else 0f),
                    )
                },
                track = { sliderState ->
                    FuzzyRangeSliderTrack(
                        sliderState = sliderState,
                        tolerance = tolerance,
                        highlighted = enabled,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuzzyRangeSliderTrack(
    sliderState: SliderState,
    tolerance: Float,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val neutralTrack = MaterialTheme.colorScheme.surfaceVariant
    val thumbTrackGapSize = 6.dp
    val colors =
        SliderDefaults.colors(
            activeTrackColor = neutralTrack,
            inactiveTrackColor = neutralTrack,
            activeTickColor = neutralTrack,
            inactiveTickColor = neutralTrack,
        )
    SliderDefaults.Track(
        sliderState = sliderState,
        colors = colors,
        modifier =
            modifier.drawWithContent {
                drawContent()
                val valueRange = sliderState.valueRange
                val span = valueRange.endInclusive - valueRange.start
                if (!highlighted || span <= 0f) return@drawWithContent
                val lower = (sliderState.value - tolerance).coerceIn(valueRange.start, valueRange.endInclusive)
                val upper = (sliderState.value + tolerance).coerceIn(valueRange.start, valueRange.endInclusive)
                val left = ((lower - valueRange.start) / span).coerceIn(0f, 1f) * size.width
                val right = ((upper - valueRange.start) / span).coerceIn(0f, 1f) * size.width
                val thumbCenter = ((sliderState.value - valueRange.start) / span).coerceIn(0f, 1f) * size.width
                val centerY = size.height / 2f
                val strokeWidth = size.height
                val gap = thumbTrackGapSize.toPx()

                fun drawHighlightedSegment(
                    startX: Float,
                    endX: Float,
                    roundFarStart: Boolean,
                ) {
                    if (endX <= startX) return
                    val segmentStart = startX.coerceIn(0f, size.width)
                    val segmentEnd = endX.coerceIn(segmentStart, size.width)
                    val segmentWidth = segmentEnd - segmentStart
                    if (segmentWidth <= 0f) return

                    val brush =
                        Brush.horizontalGradient(
                            colorStops =
                                if (roundFarStart) {
                                    arrayOf(
                                        0f to primary.copy(alpha = 0f),
                                        0.42f to primary.copy(alpha = 0.16f),
                                        1f to primary.copy(alpha = 0.48f),
                                    )
                                } else {
                                    arrayOf(
                                        0f to primary.copy(alpha = 0.48f),
                                        0.58f to primary.copy(alpha = 0.16f),
                                        1f to primary.copy(alpha = 0f),
                                    )
                                },
                            startX = segmentStart,
                            endX = segmentEnd,
                        )

                    val radius = minOf(strokeWidth / 2f, segmentWidth / 2f)
                    val top = centerY - strokeWidth / 2f
                    val bottom = centerY + strokeWidth / 2f
                    val path =
                        Path().apply {
                            if (roundFarStart) {
                                moveTo(segmentEnd, top)
                                lineTo(segmentStart + radius, top)
                                quadraticTo(segmentStart, top, segmentStart, centerY)
                                quadraticTo(segmentStart, bottom, segmentStart + radius, bottom)
                                lineTo(segmentEnd, bottom)
                            } else {
                                moveTo(segmentStart, top)
                                lineTo(segmentEnd - radius, top)
                                quadraticTo(segmentEnd, top, segmentEnd, centerY)
                                quadraticTo(segmentEnd, bottom, segmentEnd - radius, bottom)
                                lineTo(segmentStart, bottom)
                            }
                            close()
                        }

                    drawPath(path, brush)
                }

                var leftStart = left
                var leftEnd = minOf(right, thumbCenter - gap)

                var rightStart = maxOf(left, thumbCenter + gap)
                var rightEnd = right

                drawHighlightedSegment(leftStart, leftEnd, roundFarStart = true)
                drawHighlightedSegment(rightStart, rightEnd, roundFarStart = false)
            },
        thumbTrackGapSize = thumbTrackGapSize,
    )
}

@Composable
private fun ToleranceStepper(
    text: String,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDecrease, enabled = enabled, modifier = Modifier.size(28.dp)) {
            Icon(painterResource(R.drawable.remove), contentDescription = null, modifier = Modifier.size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 2.dp))
        IconButton(onClick = onIncrease, enabled = enabled, modifier = Modifier.size(28.dp)) {
            Icon(painterResource(R.drawable.add), contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun KeyWheelFilter(
    enabled: Boolean,
    number: Int,
    mode: String,
    tolerance: Int,
    onEnabledChange: (Boolean) -> Unit,
    onKeyChange: (Int, String) -> Unit,
    onToleranceChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KEY", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                text = if (enabled) AdvancedLocalMusicSearch.keyLabel(number, mode) else "任意",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            ToleranceStepper(
                text = "±$tolerance",
                enabled = enabled,
                onDecrease = { onToleranceChange((tolerance - 1).coerceAtLeast(0)) },
                onIncrease = { onToleranceChange((tolerance + 1).coerceAtMost(5)) },
            )
        }
        CamelotWheel(
            enabled = enabled,
            number = number,
            mode = mode,
            tolerance = tolerance,
            onCenterClick = { onEnabledChange(!enabled) },
            onKeyChange = onKeyChange,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(340.dp),
        )
    }
}

private data class CamelotWheelTouch(
    val number: Int? = null,
    val mode: String? = null,
    val isCenter: Boolean = false,
)

private fun resolveCamelotWheelTouch(
    offset: Offset,
    width: Int,
    height: Int,
    density: Density,
): CamelotWheelTouch? {
    val center = Offset(width / 2f, height / 2f)
    val dx = offset.x - center.x
    val dy = offset.y - center.y
    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
    val centerRadius = with(density) { 30.dp.toPx() }
    val minorOuterRadius = with(density) { 112.dp.toPx() }
    val outerRadius = with(density) { 164.dp.toPx() }

    if (distance <= centerRadius) {
        return CamelotWheelTouch(isCenter = true)
    }
    if (distance > outerRadius) {
        return null
    }

    val touchedMode = if (distance <= minorOuterRadius) "A" else "B"
    val degrees = ((atan2(dy, dx) * 180f / PI.toFloat()) + 90f + 360f) % 360f
    val touchedNumber = advancedNormalizeCamelotNumber(floor((degrees + 15f) / 30f).toInt())
    return CamelotWheelTouch(number = touchedNumber, mode = touchedMode)
}

@Composable
private fun CamelotWheel(
    enabled: Boolean,
    number: Int,
    mode: String,
    tolerance: Int,
    onCenterClick: () -> Unit,
    onKeyChange: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val ringDividerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val selected = AdvancedLocalMusicSearch.matchingKeyNumbers(AdvancedKeyFilter(enabled, number, mode, tolerance))
    val density = LocalDensity.current
    Box(
        modifier =
            modifier
                .pointerInput(enabled, number, mode, tolerance) {
                    fun handleTouch(
                        offset: Offset,
                        centerCanToggle: Boolean,
                    ) {
                        val touch = resolveCamelotWheelTouch(offset, size.width, size.height, density) ?: return
                        if (touch.isCenter) {
                            if (centerCanToggle) {
                                onCenterClick()
                            }
                            return
                        }
                        val touchedNumber = touch.number ?: return
                        val touchedMode = touch.mode ?: return
                        if (touchedNumber != number || touchedMode != mode.uppercase() || !enabled) {
                            onKeyChange(touchedNumber, touchedMode)
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val touchSlop = viewConfiguration.touchSlop
                        var pastTouchSlop = false
                        var selectingKey = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!pastTouchSlop) {
                                    handleTouch(start, centerCanToggle = true)
                                    change.consume()
                                }
                                break
                            }

                            val delta = change.position - start
                            if (!pastTouchSlop && delta.getDistance() > touchSlop) {
                                pastTouchSlop = true
                                val verticalScrollIntent = abs(delta.y) > abs(delta.x) * 1.15f
                                if (!verticalScrollIntent) {
                                    selectingKey = true
                                    handleTouch(change.position, centerCanToggle = false)
                                    change.consume()
                                }
                            } else if (selectingKey) {
                                handleTouch(change.position, centerCanToggle = false)
                                change.consume()
                            }
                        }
                    }
                },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val centerRadius = 30.dp.toPx()
            val minorOuterRadius = 112.dp.toPx()
            val outerRadius = 164.dp.toPx()
            val ringDividerRadius = minorOuterRadius
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = 10.sp.toPx()
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

            fun annularSectorPath(
                innerRadius: Float,
                outerRadius: Float,
                startAngle: Float,
                sweepAngle: Float,
            ): Path =
                Path().apply {
                    arcTo(
                        Rect(center.x - outerRadius, center.y - outerRadius, center.x + outerRadius, center.y + outerRadius),
                        startAngle,
                        sweepAngle,
                        forceMoveTo = true,
                    )
                    arcTo(
                        Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius),
                        startAngle + sweepAngle,
                        -sweepAngle,
                        forceMoveTo = false,
                    )
                    close()
                }

            fun segmentColor(
                index: Int,
                ringMode: String,
            ): Color {
                val isSelected = enabled && (index to ringMode) == (number to mode.uppercase())
                val isNearby = enabled && (index to ringMode) in selected && !isSelected
                return when {
                    isSelected -> primary.copy(alpha = 0.92f)
                    isNearby -> primary.copy(alpha = 0.32f)
                    else -> muted
                }
            }

            fun drawWheelRing(
                innerRadius: Float,
                outerRadius: Float,
                ringMode: String,
            ) {
                fun polarPoint(
                    radius: Float,
                    angleRadians: Double,
                ): Offset =
                    Offset(
                        center.x + cos(angleRadians).toFloat() * radius,
                        center.y + sin(angleRadians).toFloat() * radius,
                    )

                fun drawLabelText(
                    text: String,
                    point: Offset,
                    textSizeSp: Float,
                    align: Paint.Align = Paint.Align.CENTER,
                ) {
                    paint.textAlign = align
                    paint.textSize = textSizeSp.sp.toPx()
                    val metrics = paint.fontMetrics
                    val baseline = point.y - (metrics.ascent + metrics.descent) / 2f
                    drawContext.canvas.nativeCanvas.drawText(text, point.x, baseline, paint)
                }

                for (i in 1..12) {
                    val wheelPosition = i % 12
                    val startAngle = -105f + wheelPosition * 30f
                    val sector = annularSectorPath(innerRadius, outerRadius, startAngle, 30f)
                    drawPath(sector, segmentColor(i, ringMode))

                    val labelAngle = Math.toRadians((-90.0 + wheelPosition * 30.0))
                    val bandWidth = outerRadius - innerRadius
                    val codeRadius =
                        if (ringMode == "A") {
                            innerRadius + bandWidth * 0.22f
                        } else {
                            innerRadius + bandWidth * 0.72f
                        }
                    val noteRadius =
                        if (ringMode == "A") {
                            innerRadius + bandWidth * 0.63f
                        } else {
                            innerRadius + bandWidth * 0.38f
                        }
                    val codePoint = polarPoint(codeRadius, labelAngle)
                    val notePoint = polarPoint(noteRadius, labelAngle)
                    val isSelected = enabled && (i to ringMode) == (number to mode.uppercase())
                    val noteLabel = AdvancedLocalMusicSearch.keyShortLabel(i, ringMode).substringAfter('\n')
                    paint.color = textColor.copy(alpha = if (isSelected) 1f else 0.68f).toArgb()
                    drawLabelText("$i$ringMode", codePoint, 12f)
                    drawLabelText(noteLabel, notePoint, 10f)
                }
                paint.textAlign = Paint.Align.CENTER
            }

            drawWheelRing(centerRadius, minorOuterRadius, "A")
            drawWheelRing(minorOuterRadius, outerRadius, "B")
            drawCircle(
                color = ringDividerColor,
                radius = ringDividerRadius,
                center = center,
                style = Stroke(width = 2.4.dp.toPx()),
            )
            drawCircle(
                color = ringDividerColor,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.6.dp.toPx()),
            )
            for (i in 0 until 12) {
                val angle = (-105f + i * 30f) * PI.toFloat() / 180f
                val start = Offset(
                    center.x + cos(angle) * centerRadius,
                    center.y + sin(angle) * centerRadius,
                )
                val end = Offset(
                    center.x + cos(angle) * outerRadius,
                    center.y + sin(angle) * outerRadius,
                )
                drawLine(
                    color = ringDividerColor,
                    start = start,
                    end = end,
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
            drawCircle(
                color = if (enabled) primary.copy(alpha = 0.94f) else muted,
                radius = centerRadius,
                center = center,
            )
            paint.color = textColor.toArgb()
            val centerLabel = if (enabled) AdvancedLocalMusicSearch.keyShortLabel(number, mode) else "ANY"
            val centerTitle = centerLabel.substringBefore('\n')
            val centerSubtitle = centerLabel.substringAfter('\n', "")

            fun drawCenteredText(
                text: String,
                yCenter: Float,
                textSizeSp: Float,
            ) {
                if (text.isBlank()) return
                paint.textSize = textSizeSp.sp.toPx()
                val metrics = paint.fontMetrics
                val baseline = yCenter - (metrics.ascent + metrics.descent) / 2f
                drawContext.canvas.nativeCanvas.drawText(text, center.x, baseline, paint)
            }

            if (centerSubtitle.isBlank()) {
                drawCenteredText(centerTitle, center.y, 12f)
            } else {
                val lineOffset = 7.dp.toPx()
                drawCenteredText(centerTitle, center.y - lineOffset, 12f)
                drawCenteredText(centerSubtitle, center.y + lineOffset, 10f)
            }
        }
    }
}

@Composable
private fun AdvancedLocalMusicItem(
    item: AdvancedLocalMusicSearchItem,
    criteria: AdvancedLocalMusicSearchCriteria,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localSong = item.localSong
    val chipColor = MaterialTheme.colorScheme.secondaryContainer
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            modifier
                .height(84.dp)
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = localSong.song.song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
            Text(
                text = localSong.song.song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localSong.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                AdvancedChip("${item.result.similarityPercent}%", chipColor)
                localSong.localMusic.bpm?.let { AdvancedChip(it.advancedBpmChipLabel(criteria), chipColor) }
                advancedKeyDisplayName(localSong.localMusic.keyName)?.let { AdvancedChip(it, chipColor) }
                localSong.localMusic.keyName?.advancedCamelotChipLabel(criteria)?.let { AdvancedChip(it, chipColor) }
            }
        }
        AdvancedEmotionRadar(
            localMusic = localSong.localMusic,
            criteria = criteria,
            modifier = Modifier.size(64.dp),
        )
    }
}

@Composable
private fun AdvancedEmotionRadar(
    localMusic: LocalMusicEntity,
    criteria: AdvancedLocalMusicSearchCriteria,
    modifier: Modifier = Modifier,
) {
    val metrics =
        listOf(
            localMusic.valence,
            localMusic.energy,
            localMusic.danceability,
            localMusic.acousticness,
            localMusic.instrumentalness,
            localMusic.liveness,
            localMusic.speechiness,
        )
    val hasMetrics = metrics.any { it != null }
    val values = metrics.map { it?.let(::normalizeLocalMusicMetric) ?: 0f }
    val referenceValues =
        AdvancedEmotionMetric.entries.map { metric ->
            criteria.emotions[metric]?.takeIf { it.enabled }?.target?.coerceIn(0f, 1f) ?: 0f
        }
    val hasReferenceMetrics = referenceValues.any { it > 0f }
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
    val shapeColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.tertiary

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sides = values.size
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) * 0.39f
            fun point(index: Int, value: Float): Offset {
                val angle = (-PI / 2.0 + index * 2.0 * PI / sides).toFloat()
                return Offset(center.x + cos(angle) * radius * value, center.y + sin(angle) * radius * value)
            }
            for (ring in 1..3) {
                val path = Path()
                repeat(sides) { index ->
                    val p = point(index, ring / 3f)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color = lineColor, style = Stroke(width = 0.55.dp.toPx()))
            }
            if (hasReferenceMetrics) {
                val referencePath = Path()
                referenceValues.forEachIndexed { index, value ->
                    val p = point(index, value)
                    if (index == 0) referencePath.moveTo(p.x, p.y) else referencePath.lineTo(p.x, p.y)
                }
                referencePath.close()
                drawPath(referencePath, color = referenceColor.copy(alpha = 0.42f))
            }
            if (hasMetrics) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val p = point(index, value)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color = shapeColor, style = Stroke(width = 1.15.dp.toPx()))
                values.forEachIndexed { index, value ->
                    drawCircle(color = shapeColor, radius = 1.45.dp.toPx(), center = point(index, value))
                }
            }
        }
        if (!hasMetrics) {
            Text("待分析", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdvancedChip(text: String, color: Color? = null) {
    val chipColor = color ?: MaterialTheme.colorScheme.secondaryContainer
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(chipColor.copy(alpha = 0.15f))
                .padding(horizontal = 3.dp),
    )
}

private data class AdvancedLocalMusicSearchItem(
    val localSong: LocalSong,
    val result: AdvancedLocalMusicSearchResult,
)

private fun activeFilterCount(
    bpmEnabled: Boolean,
    keyEnabled: Boolean,
    emotionFilters: Map<AdvancedEmotionMetric, AdvancedNumericFilter>,
): Int =
    (if (bpmEnabled) 1 else 0) +
        (if (keyEnabled) 1 else 0) +
        emotionFilters.values.count { it.enabled }

private fun LocalSong.toAdvancedSearchCandidate(): AdvancedLocalMusicSearchCandidate =
    AdvancedLocalMusicSearchCandidate(
        songId = localMusic.songId,
        title = song.song.title,
        artists = song.orderedArtists.joinToString { it.name },
        album = song.album?.title,
        displayName = localMusic.displayName,
        bpm = localMusic.bpm,
        keyName = localMusic.keyName,
        valence = localMusic.valence,
        energy = localMusic.energy,
        danceability = localMusic.danceability,
        acousticness = localMusic.acousticness,
        instrumentalness = localMusic.instrumentalness,
        liveness = localMusic.liveness,
        speechiness = localMusic.speechiness,
    )

private fun LocalSong.subtitle(): String =
    joinByBullet(
        song.orderedArtists.joinToString { it.name },
        makeTimeString(song.song.duration * 1000L).takeIf { song.song.duration > 0 },
    )

private fun Float.advancedBpmChipLabel(criteria: AdvancedLocalMusicSearchCriteria): String {
    val roundedBpm = roundToInt()
    val delta = criteria.bpm.takeIf { it.enabled }?.let { (this - it.target).roundToInt() }
    return if (delta != null) "$roundedBpm BPM ${delta.signedDeltaText()}" else "$roundedBpm BPM"
}

private fun String.advancedCamelotChipLabel(criteria: AdvancedLocalMusicSearchCriteria): String? {
    val candidateCamelot = canonicalMusicKey()?.let(::advancedKeyToCamelot) ?: return null
    val label = "${candidateCamelot.first}${candidateCamelot.second}"
    val reference = criteria.key.takeIf { it.enabled }?.let { it.number to it.mode.uppercase() }
    val delta = reference?.let { candidateCamelot.first.circularDeltaFrom(it.first) }
    return if (delta != null) "$label ${delta.signedDeltaText()}" else label
}

private fun Int.signedDeltaText(): String =
    when {
        this > 0 -> "+$this"
        this < 0 -> toString()
        else -> "±0"
    }

private fun Int.circularDeltaFrom(reference: Int): Int {
    var delta = this - reference
    if (delta > 6) delta -= 12
    if (delta < -6) delta += 12
    return delta
}

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
