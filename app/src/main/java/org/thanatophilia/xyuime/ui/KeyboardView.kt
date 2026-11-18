package org.thanatophilia.xyuime.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.selected
import kotlin.math.ceil
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The three key fields exposed by the keyboard's fixed bottom controls. */
enum class KeyboardPage {
    Letters,
    Numbers,
    Symbols,
}

/**
 * State rendered by [XyuKeyboard]. Text insertion and state transitions belong to the IME
 * controller; this object only describes what the view should display.
 */
data class KeyboardUiState(
    val page: KeyboardPage = KeyboardPage.Letters,
    val uppercase: Boolean = false,
    val capsLock: Boolean = false,
    val actionLabel: String = "回车",
    val editing: Boolean = false,
    val selecting: Boolean = false,
    val sensitive: Boolean = false,
    val hasSelection: Boolean = false,
    val inputHint: String = "English",
    val comma: String = "，",
    val period: String = "。",
    val numberKind: String = "number",
    val chinese: Boolean = false,
    val preedit: String = "",
    val candidates: List<String> = emptyList(),
    val candidateComments: List<String> = emptyList(),
    val candidateSession: Int = -1,
    val candidateRevision: Long = 0,
    val highlightedCandidate: Int = 0,
    val hasMoreCandidates: Boolean = false,
    val loadingCandidates: Boolean = false,
    val candidatesExpanded: Boolean = false,
)

/** Events emitted by [XyuKeyboard] for the IME controller to interpret. */
sealed interface KeyboardAction {
    data class Text(val value: String) : KeyboardAction

    data object Shift : KeyboardAction

    data object CapsLock : KeyboardAction

    data object Backspace : KeyboardAction

    data object Space : KeyboardAction

    data object Enter : KeyboardAction

    data object ToggleNumbers : KeyboardAction

    data object ToggleSymbols : KeyboardAction

    data class SelectCandidate(val revision: Long, val index: Int) : KeyboardAction
    data class LoadMoreCandidates(val revision: Long) : KeyboardAction
    data class SetChinese(val enabled: Boolean) : KeyboardAction
    // Candidate-page actions are scoped to the input session; repeating delete never falls
    // through to deleting committed editor text once the composition has ended.
    data class CandidateBackspace(val session: Int) : KeyboardAction
    data class CandidateCommit(val session: Int) : KeyboardAction
    data object ToggleCandidates : KeyboardAction
    /** Idempotent gesture dismissal; a late drag must never reopen another composition. */
    data class CollapseCandidates(val revision: Long) : KeyboardAction
    data object NextLanguage : KeyboardAction

    data object PickInputMethod : KeyboardAction
    data object ToggleEditing : KeyboardAction
    data object ToggleSelection : KeyboardAction
    data object SelectAll : KeyboardAction
    data object Copy : KeyboardAction
    data object Cut : KeyboardAction
    data object Paste : KeyboardAction
    data class MoveCursor(val direction: CursorDirection) : KeyboardAction
}

enum class CursorDirection { Left, Right, Up, Down, Home, End }

private const val BACKSPACE_REPEAT_DELAY_MILLIS = 58L


private val XyuLightColors = lightColorScheme(
    primary = Color(0xFF536C66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E5E0),
    onPrimaryContainer = Color(0xFF17332D),
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF1A211F),
    surface = Color(0xFFF6F8F6),
    onSurface = Color(0xFF1A211F),
    surfaceVariant = Color(0xFFE5EBE8),
    onSurfaceVariant = Color(0xFF4E5A56),
    outline = Color(0xFF7B8984),
)

private val XyuDarkColors = darkColorScheme(
    primary = Color(0xFFB4CEC5),
    onPrimary = Color(0xFF1E3831),
    primaryContainer = Color(0xFF355149),
    onPrimaryContainer = Color(0xFFD6EDE4),
    background = Color(0xFF171B19),
    onBackground = Color(0xFFE1E8E4),
    surface = Color(0xFF171B19),
    onSurface = Color(0xFFE1E8E4),
    surfaceVariant = Color(0xFF303936),
    onSurfaceVariant = Color(0xFFC0CDC7),
    outline = Color(0xFF89968F),
)

private val LETTER_ROW_ONE = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val LETTER_ROW_TWO = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val LETTER_ROW_THREE = listOf("z", "x", "c", "v", "b", "n", "m")
private val NUMBER_ROW_ONE = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val NUMBER_ROW_TWO = listOf("-", "+", "×", "÷", "=", "(", ")", "/", "%")
private val NUMBER_ROW_THREE = listOf(",", ".", "(", ")", "%")
private val SYMBOL_ROW_ONE = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
private val SYMBOL_ROW_TWO = listOf("-", "_", "+", "=", "{", "}", "[", "]", "|", "\\")
private val SYMBOL_ROW_THREE = listOf(";", ":", "'", "\"", "?")

/**
 * Compose-only view for 絮雨. The host supplies state and handles all resulting actions.
 */
@Composable
fun XyuKeyboard(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    modifier: Modifier = Modifier,
    onKeyboardBounds: (android.graphics.Rect) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Reserve the candidate strip in the fixed keyboard height. Showing a composition only
    // changes the strip's contents, never the letter area or bottom row geometry.
    // Compact keys have 48dp accessibility/touch expansion even when drawn at 34dp.
    // Reserve its vertical extent inside the IME content, before the system footer inset.
    val preferredHeight = if (compactLandscape) 199.dp else 286.dp
    val maximumHeight = if (compactLandscape) 211.dp else 299.dp

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) XyuDarkColors else XyuLightColors,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                // Android 15/16 IME navigation controls may overlay the decor content.
                // Consume the real remaining system inset once, outside the key heights.
                // systemBars includes the IME caption/footer bar; union uses max, not sum.
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .heightIn(max = maximumHeight),
        ) {
            // The IME host can measure its first pass with an unbounded height. A fixed preferred
            // height keeps that pass finite while still yielding to a smaller available height.
            val keyboardHeight = if (maxHeight == Dp.Infinity) {
                preferredHeight
            } else {
                maxHeight.coerceAtMost(preferredHeight)
            }
            KeyboardSurface(
                state = state,
                onAction = onAction,
                compactLandscape = compactLandscape,
                keyboardHeight = keyboardHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(keyboardHeight)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        onKeyboardBounds(android.graphics.Rect(bounds.left.roundToInt(),
                            bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt()))
                    }
                    .semantics {
                        testTagsAsResourceId = true
                        testTag = "keyboard_surface"
                    },
            )
        }
    }
}

@Composable
private fun KeyboardSurface(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    compactLandscape: Boolean,
    keyboardHeight: Dp,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val outerPadding = if (compactLandscape) 4.dp else 5.dp
    val verticalPadding = if (compactLandscape) 8.dp else outerPadding
    val rowGap = if (compactLandscape) 3.dp else 5.dp
    // Always reserved, so pinyin composition cannot push or squeeze the letter keys.
    val stripHeight = if (compactLandscape) 34.dp else 46.dp
    // Measure the real header width, but capture its continuation only at the initial
    // leftmost position. Horizontal browsing must never replace this composition's origin.
    var stripWidthPx by remember { mutableStateOf(0) }
    val stripScroll = remember(state.candidateRevision, stripWidthPx, density.density, fontScale) { LazyListState() }
    val candidateCount by rememberUpdatedState(state.candidates.size)
    var continuationStart by remember(stripScroll) { mutableStateOf<Int?>(null) }
    LaunchedEffect(stripScroll) {
        snapshotFlow {
            val layout = stripScroll.layoutInfo
            if (layout.totalItemsCount != candidateCount) null else initialCandidateContinuationStart(
                layout.visibleItemsInfo.map { StripCandidateBounds(it.index, it.offset, it.size) },
                layout.viewportStartOffset, layout.viewportEndOffset, candidateCount,
                stripScroll.firstVisibleItemIndex, stripScroll.firstVisibleItemScrollOffset)
        }.collect { initialStart ->
            if (continuationStart == null && initialStart != null) continuationStart = initialStart
        }
    }
    val drawerStart = continuationStart
    // Lives outside the expanded branch. Header drags still close the drawer, but neither
    // moving the header nor reopening the same composition changes its vertical anchor.
    val drawerScroll = remember(state.candidateRevision, stripScroll) { LazyGridState() }
    val syllableScroll = remember(state.candidateRevision) { LazyListState() }
    var selectedSyllable by remember(state.candidateRevision) { mutableStateOf<String?>(null) }
    var wordFilter by remember(state.candidateRevision) { mutableStateOf(CandidateWordFilter.All) }
    var syllableSelectionRequest by remember(state.candidateRevision) { mutableStateOf(0L) }
    LaunchedEffect(selectedSyllable, wordFilter, drawerScroll, syllableSelectionRequest, drawerStart) {
        drawerScroll.scrollToItem(0)
    }
    val reveal by animateFloatAsState(if (state.candidatesExpanded) 1f else 0f,
        animationSpec = tween(180), label = "candidateDrawer")
    val bottomHeight = if (compactLandscape) 34.dp else 47.dp
    val panelHeight = (
        keyboardHeight - (verticalPadding * 2) - stripHeight - bottomHeight - (rowGap * 2)
    ).coerceAtLeast(1.dp)
    val keyShape = RoundedCornerShape(if (compactLandscape) 7.dp else 9.dp)

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = outerPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        if (state.preedit.isNotEmpty()) CandidateStrip(state, onAction, stripScroll,
            Modifier.fillMaxWidth().height(stripHeight).onSizeChanged { stripWidthPx = it.width },
            onToggle = {
                if (org.thanatophilia.xyuime.BuildConfig.DEBUG) android.util.Log.d("XyuLayout",
                    "toggle ready=${continuationStart != null} expanded=${state.candidatesExpanded} first=${stripScroll.firstVisibleItemIndex} offset=${stripScroll.firstVisibleItemScrollOffset} count=${stripScroll.layoutInfo.totalItemsCount}/${state.candidates.size} width=$stripWidthPx")
                if (continuationStart != null) onAction(KeyboardAction.ToggleCandidates)
            }) else InfoStrip(
            state = state,
            onAction = onAction,
            fontScale = fontScale,
            compactLandscape = compactLandscape,
            modifier = Modifier
                .fillMaxWidth()
                .height(stripHeight),
        )

        if (state.preedit.isNotEmpty() && (state.candidatesExpanded || reveal > 0f)) {
            // Ordinary keys are absent during both opening and closing: no touch or
            // accessibility targets can leak through the animated candidate surface.
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                key(state.candidateRevision, drawerStart) {
                CandidateDrawer(state, onAction, drawerScroll, syllableScroll,
                    selectedSyllable, onSelectSyllable = {
                        selectedSyllable = it
                        syllableSelectionRequest++
                    }, wordFilter = wordFilter, onWordFilter = { wordFilter = it },
                    scrollResetKey = syllableSelectionRequest, continuationStart = drawerStart,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationY = -(1f - reveal) * size.height
                        alpha = reveal
                    })
                }
            }
        } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelHeight),
        ) {
            if (state.editing) {
                EditingPanel(state, onAction, fontScale, keyShape, rowGap, Modifier.fillMaxSize())
            } else when (state.page) {
                KeyboardPage.Letters -> LettersPanel(
                    state = state,
                    onAction = onAction,
                    fontScale = fontScale,
                    compactLandscape = compactLandscape,
                    keyShape = keyShape,
                    rowGap = rowGap,
                    modifier = Modifier.fillMaxSize(),
                )

                KeyboardPage.Numbers -> NumbersPanel(
                    state = state,
                    onAction = onAction,
                    fontScale = fontScale,
                    compactLandscape = compactLandscape,
                    keyShape = keyShape,
                    rowGap = rowGap,
                    modifier = Modifier.fillMaxSize(),
                )

                KeyboardPage.Symbols -> SymbolsPanel(
                    onAction = onAction,
                    fontScale = fontScale,
                    compactLandscape = compactLandscape,
                    keyShape = keyShape,
                    rowGap = rowGap,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        BottomBar(
            state = state,
            onAction = onAction,
            fontScale = fontScale,
            compactLandscape = compactLandscape,
            keyShape = keyShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(bottomHeight),
        )
        }
    }
}

@Composable
private fun CandidateStrip(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit,
    scroll: LazyListState, modifier: Modifier, onToggle: () -> Unit) {
    val current by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && current.candidatesExpanded) {
                action(KeyboardAction.CollapseCandidates(current.candidateRevision))
            }
        }
    }
    LaunchedEffect(scroll, state.candidates.size, state.loadingCandidates) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (last >= current.candidates.size - 8 && current.hasMoreCandidates && !current.loadingCandidates)
                    action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
            }
    }
    // Only candidates live here. Code/preedit is drawn in an IME-owned floating window.
    Row(modifier.semantics {
        testTagsAsResourceId = true
        testTag = "candidate_strip"
    }, verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f).fillMaxHeight().semantics {
                testTagsAsResourceId = true; testTag = "candidate_strip_items"
            }, state = scroll,
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(state.candidates, key = { index, _ -> index }) { index, candidate ->
                    CandidateCell(candidate, index, state, onAction, Modifier.fillMaxHeight())
                }
            }
            Box(Modifier.size(44.dp).clickable(onClick = onToggle)
                .semantics {
                    testTagsAsResourceId = true
                    testTag = "candidate_toggle"
                    contentDescription = if (state.candidatesExpanded) "收回候选" else "展开候选"
                    stateDescription = if (state.candidatesExpanded) "已展开" else "已收起"
                    role = Role.Button
                }, contentAlignment = Alignment.Center) {
                CandidateChevron(state.candidatesExpanded, MaterialTheme.colorScheme.primary,
                    Modifier.size(26.dp).clearAndSetSemantics {})
            }
    }
}

@Composable
private fun CandidateChevron(expanded: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val ends = if (expanded) 14f else 10f
        val tip = if (expanded) 10f else 14f
        val path = Path().apply {
            moveTo(4f * u, ends * u)
            lineTo(12f * u, tip * u)
            lineTo(20f * u, ends * u)
        }
        drawPath(path, color, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CandidateDrawer(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit,
    scroll: LazyGridState, syllableScroll: LazyListState, selectedSyllable: String?,
    onSelectSyllable: (String?) -> Unit, wordFilter: CandidateWordFilter,
    onWordFilter: (CandidateWordFilter) -> Unit, scrollResetKey: Long, continuationStart: Int?,
    modifier: Modifier) {
    val current by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    val fontScale = LocalDensity.current.fontScale
    val candidates = remember(state.candidates, state.candidateComments) {
        drawerCandidates(state.candidates, state.candidateComments)
    }
    val choices = remember(candidates) { syllableChoices(candidates) }
    val visible = remember(candidates, selectedSyllable, wordFilter, continuationStart) {
        if (continuationStart == null) emptyList() else
            filterCandidates(continuationCandidates(candidates, continuationStart), selectedSyllable, wordFilter)
    }
    // Reaching the last currently-known syllables must also discover later engine groups.
    LaunchedEffect(syllableScroll, choices.size, state.candidates.size, state.loadingCandidates) {
        snapshotFlow { syllableScroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (last >= choices.size && current.hasMoreCandidates && !current.loadingCandidates) {
                    action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
                }
            }
    }
    // The entire filtered prefix stays in one continuous grid. Both finger gestures and
    // the arrow controls act on this same LazyGridState; there is no fixed page index.
    var screenStep by remember(state.candidateRevision, selectedSyllable, wordFilter, scrollResetKey, continuationStart) { mutableStateOf(0L) }
    val currentVisible by rememberUpdatedState(visible)
    // A new finger drag cancels an in-flight icon animation or deferred end-of-list step.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) screenStep = 0L
        }
    }
    LaunchedEffect(scroll, visible.size, state.candidates.size, state.loadingCandidates,
        state.hasMoreCandidates, selectedSyllable, wordFilter, continuationStart) {
        if (continuationStart == null) return@LaunchedEffect
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if ((currentVisible.isEmpty() || last >= currentVisible.size - 8) &&
                    current.hasMoreCandidates && !current.loadingCandidates) {
                    action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
                }
            }
    }
    suspend fun scrollOneScreen(forward: Boolean) {
        val layout = scroll.layoutInfo
        val height = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(1)
        // A small overlap keeps a reference row visible even for different word widths.
        scroll.animateScrollBy(height * if (forward) 0.85f else -0.85f, tween(180))
    }
    LaunchedEffect(screenStep, state.candidateRevision, selectedSyllable, wordFilter, scrollResetKey, continuationStart) {
        if (screenStep != 0L) {
            val forward = screenStep > 0
            if (forward && !scroll.canScrollForward) {
                // Reach the loaded end without losing the down-arrow press. Sparse filters
                // may need several native prefixes before they obtain another visible row.
                withTimeoutOrNull(8_000L) {
                    while (!scroll.canScrollForward && (current.hasMoreCandidates || current.loadingCandidates)) {
                        val previousSize = current.candidates.size
                        val wasLoading = current.loadingCandidates
                        if (current.hasMoreCandidates && !current.loadingCandidates)
                            action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
                        snapshotFlow {
                            scroll.canScrollForward || current.candidates.size != previousSize ||
                                current.loadingCandidates != wasLoading || !current.hasMoreCandidates
                        }.first { it }
                        androidx.compose.runtime.withFrameNanos { }
                    }
                }
            }
            if (!forward || scroll.canScrollForward) scrollOneScreen(forward)
        }
    }
    BoxWithConstraints(modifier) {
    val sideKeyWidth = KeyboardGeometry.edgeKeyWidthDp(maxWidth.value).dp
    Row(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true; testTag = "candidate_drawer" }) {
        Column(Modifier.width(sideKeyWidth).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { testTagsAsResourceId = true; testTag = "candidate_syllables" }) {
            LazyColumn(state = syllableScroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
                item(key = "all") { SyllableChoice(null, selectedSyllable == null, onSelectSyllable) }
                itemsIndexed(choices, key = { _, value -> value }) { _, syllable ->
                    SyllableChoice(syllable, selectedSyllable == syllable, onSelectSyllable)
                }
            }
            // Pinned at the low left: cycles only between 全部 and 单字; 词语 is not offered here.
            val typeLabel = if (wordFilter == CandidateWordFilter.Single) "单字" else "全部"
            SyllableChoice(null, wordFilter == CandidateWordFilter.Single, {
                onWordFilter(if (wordFilter == CandidateWordFilter.Single) CandidateWordFilter.All
                else CandidateWordFilter.Single)
            }, tag = "candidate_type", label = typeLabel,
                spokenDescription = "切换候选字类",
                spokenState = if (wordFilter == CandidateWordFilter.Single) "仅单字候选" else "全部字词候选")
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val density = LocalDensity.current
            val singleOnly = wordFilter == CandidateWordFilter.Single
            val availablePx = constraints.maxWidth.coerceAtLeast(1)
            // Single mode retains exactly five equal cells. All mode uses one-pixel spans
            // as a lazy wrapping layout: a word's footprint follows its rendered width.
            val columns = if (singleOnly) KeyboardGeometry.CANDIDATE_COLUMNS else availablePx
            val measurer = rememberTextMeasurer()
            val candidateStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val gutterPx = with(density) { KeyboardGeometry.CANDIDATE_GUTTER_DP.dp.roundToPx() }
            val widths = remember(availablePx, gutterPx, measurer, candidateStyle) {
                mutableMapOf<String, Int>()
            }
            fun candidateSpan(candidate: DrawerCandidate): Int {
                if (singleOnly) return 1
                return widths.getOrPut(candidate.text) {
                    val measured = measurer.measure(candidate.text, candidateStyle,
                        softWrap = false, maxLines = 1).size.width
                    KeyboardGeometry.compactCandidateWidthPx(measured, availablePx, gutterPx)
                }
            }
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = scroll,
                modifier = Modifier.fillMaxSize().semantics {
                    testTagsAsResourceId = true
                    testTag = "candidate_grid"
                }) {
                itemsIndexed(visible, key = { _, candidate -> candidate.index },
                    span = { _, candidate -> GridItemSpan(candidateSpan(candidate)) }) { _, candidate ->
                    CandidateCell(candidate.text, candidate.index, state, onAction,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp), expanded = true)
                }
                if (continuationStart != null && visible.isEmpty() && !state.loadingCandidates && !state.hasMoreCandidates)
                    item(key = "empty", span = { GridItemSpan(columns) }) {
                        Text(if (selectedSyllable == null && wordFilter == CandidateWordFilter.All)
                            "其余候选已显示在上方" else "没有符合筛选的后续候选", Modifier.padding(12.dp).semantics {
                            testTagsAsResourceId = true; testTag = "candidate_empty"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                if (continuationStart == null || state.loadingCandidates || (visible.isEmpty() && state.hasMoreCandidates)) item(key = "loading", span = { GridItemSpan(columns) }) {
                    Text("正在加载…", Modifier.padding(12.dp).semantics {
                        testTagsAsResourceId = true; testTag = "candidate_loading"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp)
                }
            }
        }
        Column(Modifier.width(sideKeyWidth).fillMaxHeight().semantics {
            testTagsAsResourceId = true; testTag = "candidate_tools"
        }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val shape = RoundedCornerShape(6.dp)
            val fontScale = LocalDensity.current.fontScale
            KeyFace("删除", "删除当前拼音", fontScale, shape,
                Modifier.fillMaxWidth().weight(1f), small = true, repeat = true,
                customTag = "candidate_delete", icon = "delete",
                onClick = { onAction(KeyboardAction.CandidateBackspace(state.candidateSession)) })
            KeyFace("", "上一屏候选", fontScale, shape, Modifier.fillMaxWidth().weight(1f),
                small = true, customTag = "candidate_tools_page_up", icon = "page_up",
                enabled = scroll.canScrollBackward,
                onClick = { screenStep = -(abs(screenStep) + 1L) })
            KeyFace("", "下一屏候选", fontScale, shape, Modifier.fillMaxWidth().weight(1f),
                small = true, customTag = "candidate_tools_page_down", icon = "page_down",
                enabled = scroll.canScrollForward || state.hasMoreCandidates || state.loadingCandidates,
                onClick = { screenStep = abs(screenStep) + 1L })
            KeyFace("", "提交当前候选，不发送", fontScale, shape,
                Modifier.fillMaxWidth().weight(1f), small = true, active = true, customTag = "candidate_enter",
                icon = "enter", onClick = { onAction(KeyboardAction.CandidateCommit(state.candidateSession)) })
        }
    }
    }
}

@Composable
private fun SyllableChoice(syllable: String?, active: Boolean, select: (String?) -> Unit,
    tag: String? = null, label: String? = null, spokenDescription: String? = null,
    spokenState: String? = null) {
    val shape = RoundedCornerShape(6.dp)
    // Keep the clickable surface at least 48dp tall inside its 2dp top/bottom inset.
    // Otherwise Compose expands accessibility/touch bounds past the pinned rail edge.
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 2.dp)
        .clip(shape).background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .clickable { select(syllable) }
        .semantics {
            testTagsAsResourceId = true
            testTag = tag ?: "syllable:${syllable ?: "all"}"
            selected = active
            role = Role.Tab
            contentDescription = spokenDescription ?: if (syllable == null) "全部候选" else "拼音 $syllable"
            spokenState?.let { stateDescription = it }
        }.padding(horizontal = 3.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label ?: syllable ?: "全部", fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateCell(candidate: String, index: Int, state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit, modifier: Modifier, expanded: Boolean = false) {
    Box(modifier.clickable { onAction(KeyboardAction.SelectCandidate(state.candidateRevision, index)) }
        .semantics {
            testTagsAsResourceId = true
            testTag = "candidate:$index"
            role = Role.Button
            // The whole tap target has a label even when its inset text is clipped by scrolling.
            text = AnnotatedString(candidate)
        }.padding(horizontal = if (expanded) (KeyboardGeometry.CANDIDATE_GUTTER_DP / 2).dp else 12.dp,
            vertical = if (expanded) 10.dp else 4.dp),
        contentAlignment = Alignment.Center) {
        Text(candidate, modifier = Modifier.clearAndSetSemantics {},
            color = if (index == state.highlightedCandidate) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (index == state.highlightedCandidate) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = if (expanded) 20.sp else 18.sp,
            maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoStrip(
    state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit,
    fontScale: Float,
    compactLandscape: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(if (compactLandscape) 6.dp else 8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceVariant.copy(alpha = 0.48f))
            .padding(horizontal = if (compactLandscape) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.selecting) "絮雨 · 选择文字" else "絮雨 · ${state.inputHint}",
            modifier = Modifier.weight(1f),
            color = colors.onSurfaceVariant,
            fontSize = adaptiveSp(12f, fontScale, 15f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Box(Modifier.fillMaxHeight().size(width = 62.dp, height = if (compactLandscape) 23.dp else 28.dp)
            .background(if (state.editing) colors.primaryContainer else Color.Transparent, shape)
            .pointerInput(state.editing) { detectTapGestures(onTap = { onAction(KeyboardAction.ToggleEditing) }) }
            .clearAndSetSemantics {
                testTagsAsResourceId = true
                testTag = "edit_toggle"
                contentDescription = "编辑工具"
                text = AnnotatedString(if (state.editing) "键盘" else "编辑")
                role = Role.Button
                onClick { onAction(KeyboardAction.ToggleEditing); true }
            },
            contentAlignment = Alignment.Center) {
            Text(if (state.editing) "键盘" else "编辑", color = colors.primary,
                fontSize = adaptiveSp(12f, fontScale, 15f), fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun adaptiveSp(base: Float, fontScale: Float, maximum: Float): TextUnit =
    (base.times(fontScale).coerceIn(base * 0.85f, maximum) / fontScale.coerceAtLeast(0.1f)).sp

@Composable
private fun LettersPanel(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit, fontScale: Float,
    compactLandscape: Boolean, keyShape: Shape, rowGap: Dp, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val cell = maxWidth / 10
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            CharacterRow(LETTER_ROW_ONE, state.uppercase, onAction, fontScale, keyShape,
                Modifier.fillMaxWidth().weight(1f))
            CharacterRow(LETTER_ROW_TWO, state.uppercase, onAction, fontScale, keyShape,
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = cell / 2))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                KeyFace("Shift", "切换大小写，长按锁定大写", fontScale, keyShape, Modifier.weight(1.5f).fillMaxHeight(),
                    active = state.uppercase, onClick = { onAction(KeyboardAction.Shift) },
                    onLongClick = { onAction(KeyboardAction.CapsLock) }, icon = "shift", locked = state.capsLock,
                    spokenState = when {
                        state.chinese -> "中文拼音"
                        state.capsLock -> "大写已锁定"
                        state.uppercase -> "大写"
                        else -> "小写"
                    }, longClickLabel = "切换大写锁定")
                LETTER_ROW_THREE.forEach { letter ->
                    val text = if (state.uppercase) letter.uppercase() else letter
                    KeyFace(text, text, fontScale, keyShape, Modifier.weight(1f).fillMaxHeight(),
                        onClick = { onAction(KeyboardAction.Text(text)) })
                }
                DeleteKey(onAction, fontScale, keyShape, Modifier.weight(1.5f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun CharacterRow(characters: List<String>, uppercase: Boolean, onAction: (KeyboardAction) -> Unit,
    fontScale: Float, keyShape: Shape, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        characters.forEach { value ->
            val text = if (uppercase) value.uppercase() else value
            KeyFace(text, text, fontScale, keyShape, Modifier.weight(1f).fillMaxHeight(),
                onClick = { onAction(KeyboardAction.Text(text)) })
        }
    }
}

@Composable
private fun NumbersPanel(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit, fontScale: Float, compactLandscape: Boolean,
    keyShape: Shape, rowGap: Dp, modifier: Modifier) {
    val shortcuts = when (state.numberKind) {
        "phone" -> listOf("+", "*", "#", ",", ";")
        "decimal" -> listOf(".", "-", "+", ",", "/")
        "signed" -> listOf("-", "+", ".", ",", "/")
        "datetime" -> listOf("/", "-", ":", ".", "+")
        else -> NUMBER_ROW_THREE
    }
    AuxiliaryPanel(NUMBER_ROW_ONE, NUMBER_ROW_TWO, shortcuts, onAction, fontScale, keyShape, rowGap,
        modifier, KeyboardAction.ToggleNumbers)
}

@Composable
private fun SymbolsPanel(onAction: (KeyboardAction) -> Unit, fontScale: Float, compactLandscape: Boolean,
    keyShape: Shape, rowGap: Dp, modifier: Modifier) {
    AuxiliaryPanel(SYMBOL_ROW_ONE, SYMBOL_ROW_TWO, SYMBOL_ROW_THREE, onAction, fontScale, keyShape, rowGap,
        modifier, KeyboardAction.ToggleSymbols)
}

@Composable
private fun AuxiliaryPanel(first: List<String>, second: List<String>, third: List<String>,
    onAction: (KeyboardAction) -> Unit, fontScale: Float, shape: Shape, gap: Dp, modifier: Modifier,
    returnAction: KeyboardAction) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        CharacterRow(first, false, onAction, fontScale, shape, Modifier.fillMaxWidth().weight(1f))
        CharacterRow(second, false, onAction, fontScale, shape, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            KeyFace("ABC", "返回字母键盘", fontScale, shape, Modifier.weight(1.5f).fillMaxHeight(),
                onClick = { onAction(returnAction) })
            third.forEach { text ->
                KeyFace(text, text, fontScale, shape, Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onAction(KeyboardAction.Text(text)) })
            }
            DeleteKey(onAction, fontScale, shape, Modifier.weight(1.5f).fillMaxHeight())
        }
    }
}

@Composable
private fun BottomBar(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit, fontScale: Float,
    compactLandscape: Boolean, keyShape: Shape, modifier: Modifier) {
    BoxWithConstraints(modifier) {
    val sideKeyWidth = KeyboardGeometry.edgeKeyWidthDp(maxWidth.value).dp
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(KeyboardGeometry.KEY_GAP_DP.dp)) {
        KeyFace("！﹫#", "符号键盘", fontScale, keyShape, Modifier.width(sideKeyWidth).fillMaxHeight(),
            active = state.page == KeyboardPage.Symbols, small = true, onClick = { onAction(KeyboardAction.ToggleSymbols) })
        KeyFace("123", "数字键盘", fontScale, keyShape, Modifier.weight(0.95f).fillMaxHeight(),
            active = state.page == KeyboardPage.Numbers, small = true, onClick = { onAction(KeyboardAction.ToggleNumbers) })
        KeyFace(state.comma, "逗号", fontScale, keyShape, Modifier.weight(0.70f).fillMaxHeight(),
            onClick = { onAction(KeyboardAction.Text(state.comma)) })
        KeyFace("空格", "空格", fontScale, keyShape, Modifier.weight(3.10f).fillMaxHeight(), small = true,
            onClick = { onAction(KeyboardAction.Space) })
        KeyFace(state.period, "句号", fontScale, keyShape, Modifier.weight(0.70f).fillMaxHeight(),
            onClick = { onAction(KeyboardAction.Text(state.period)) })
        KeyFace("", "切换语言，长按切换输入法", fontScale, keyShape, Modifier.weight(0.95f).fillMaxHeight(),
            onClick = { onAction(KeyboardAction.NextLanguage) }, onLongClick = { onAction(KeyboardAction.PickInputMethod) }, icon = "globe",
            spokenState = state.inputHint, longClickLabel = "选择系统输入法")
        KeyFace(state.actionLabel, "回车：${state.actionLabel}", fontScale, keyShape, Modifier.width(sideKeyWidth).fillMaxHeight(),
            active = true, small = true, onClick = { onAction(KeyboardAction.Enter) })
    }
    }
}

@Composable
private fun EditingPanel(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit, fontScale: Float,
    shape: Shape, gap: Dp, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("全选" to KeyboardAction.SelectAll, "复制" to KeyboardAction.Copy,
                "剪切" to KeyboardAction.Cut, "粘贴" to KeyboardAction.Paste).forEach { (title, action) ->
                val enabled = when (action) {
                    KeyboardAction.Copy, KeyboardAction.Cut -> !state.sensitive && state.hasSelection
                    else -> true
                }
                KeyFace(title, title, fontScale, shape, Modifier.weight(1f).fillMaxHeight(), small = true,
                    enabled = enabled, onClick = { onAction(action) })
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            KeyFace("行首", "光标到行首", fontScale, shape, Modifier.weight(1f).fillMaxHeight(), small = true,
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.Home)) })
            KeyFace("↑", "光标上移", fontScale, shape, Modifier.weight(1f).fillMaxHeight(),
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.Up)) })
            KeyFace("行尾", "光标到行尾", fontScale, shape, Modifier.weight(1f).fillMaxHeight(), small = true,
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.End)) })
            KeyFace("选择", "选择文字模式", fontScale, shape, Modifier.weight(1f).fillMaxHeight(), small = true,
                active = state.selecting, onClick = { onAction(KeyboardAction.ToggleSelection) })
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            KeyFace("←", "光标左移", fontScale, shape, Modifier.weight(1f).fillMaxHeight(),
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.Left)) })
            KeyFace("↓", "光标下移", fontScale, shape, Modifier.weight(1f).fillMaxHeight(),
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.Down)) })
            KeyFace("→", "光标右移", fontScale, shape, Modifier.weight(1f).fillMaxHeight(),
                onClick = { onAction(KeyboardAction.MoveCursor(CursorDirection.Right)) })
            DeleteKey(onAction, fontScale, shape, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun DeleteKey(onAction: (KeyboardAction) -> Unit, fontScale: Float, shape: Shape, modifier: Modifier) {
    KeyFace("", "删除，长按连续删除", fontScale, shape, modifier,
        onClick = { onAction(KeyboardAction.Backspace) }, repeat = true, icon = "delete")
}

@Composable
private fun KeyFace(label: String, description: String, fontScale: Float, shape: Shape,
    modifier: Modifier, active: Boolean = false, small: Boolean = false, repeat: Boolean = false, enabled: Boolean = true,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: String? = null, locked: Boolean = false,
    customTag: String? = null, spokenState: String? = null, longClickLabel: String = "长按") {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    val configuration = LocalViewConfiguration.current
    val click by rememberUpdatedState({ if (enabled) onClick() })
    val longClick by rememberUpdatedState(onLongClick)
    val available by rememberUpdatedState(enabled)
    var pressed by remember { mutableStateOf(false) }
    val background = when {
        pressed -> colors.primary.copy(alpha = 0.24f)
        active -> colors.primaryContainer
        small || icon != null -> colors.surfaceVariant
        else -> if (isSystemInDarkTheme()) Color(0xFF2C3431) else Color.White
    }
    val foreground = (if (active) colors.onPrimaryContainer else colors.onSurface).copy(alpha = if (enabled) 1f else 0.35f)
    val gesture = if (!enabled) Modifier else if (repeat) {
        Modifier.pointerInput(configuration.longPressTimeoutMillis) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    click()
                    val repetition = launch {
                        delay(configuration.longPressTimeoutMillis)
                        while (isActive) { click(); delay(BACKSPACE_REPEAT_DELAY_MILLIS) }
                    }
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { change.consume(); break }
                            if (change.isConsumed || change.position.x < 0 || change.position.y < 0 ||
                                change.position.x >= size.width || change.position.y >= size.height) break
                            change.consume()
                        }
                    } finally {
                        repetition.cancel()
                        pressed = false
                    }
                }
            }
        }
    } else {
        Modifier.pointerInput(label, description, onLongClick != null) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    try {
                        this.tryAwaitRelease()
                    } finally {
                        pressed = false
                    }
                },
                onTap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); click() },
                onLongPress = if (onLongClick == null) null else { _ ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    longClick?.invoke()
                },
            )
        }
    }
    Box(modifier.clip(shape).background(background).border(0.5.dp, colors.outline.copy(alpha = 0.14f), shape)
        .then(gesture).clearAndSetSemantics {
            role = Role.Button
            contentDescription = description
            spokenState?.let { stateDescription = it }
            testTagsAsResourceId = true
            testTag = customTag ?: "key:$description"
            if (icon == null) text = AnnotatedString(label)
            if (!enabled) disabled()
            onClick { if (available) { click(); true } else false }
            if (onLongClick != null && enabled) {
                this.onLongClick(label = longClickLabel) { longClick?.invoke(); true }
            }
        }, contentAlignment = Alignment.Center) {
        if (icon != null) {
            KeyIcon(icon, foreground, locked, Modifier.size(22.dp).clearAndSetSemantics {})
        } else {
            Text(label, color = foreground, modifier = Modifier.clearAndSetSemantics {},
                fontSize = adaptiveSp(if (small) 12f else 21f, fontScale, if (small) 14f else 25f),
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun KeyIcon(type: String, color: Color, locked: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(1.6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            "globe" -> {
                drawCircle(color, 9f * unit, center = p(12f, 12f), style = stroke)
                drawOval(color, p(7f, 3f), androidx.compose.ui.geometry.Size(10f * unit, 18f * unit), style = stroke)
                drawLine(color, p(3f, 12f), p(21f, 12f), strokeWidth = stroke.width)
                drawLine(color, p(5f, 7f), p(19f, 7f), strokeWidth = stroke.width)
                drawLine(color, p(5f, 17f), p(19f, 17f), strokeWidth = stroke.width)
            }
            "delete" -> {
                val path = Path().apply {
                    moveTo(3f * unit, 12f * unit); lineTo(8f * unit, 6f * unit)
                    lineTo(21f * unit, 6f * unit); lineTo(21f * unit, 18f * unit)
                    lineTo(8f * unit, 18f * unit); close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color, p(12f, 9f), p(17f, 15f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, p(17f, 9f), p(12f, 15f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "page_up", "page_down" -> {
                // Broad chevrons, consistent with the expand/collapse icon.
                val end = if (type == "page_up") 15f else 9f
                val tip = if (type == "page_up") 10f else 14f
                val path = Path().apply {
                    moveTo(4f * unit, end * unit)
                    lineTo(12f * unit, tip * unit)
                    lineTo(20f * unit, end * unit)
                }
                drawPath(path, color, style = stroke)
            }
            "enter" -> {
                val path = Path().apply {
                    moveTo(20f * unit, 5f * unit)
                    lineTo(20f * unit, 13f * unit)
                    lineTo(5f * unit, 13f * unit)
                    moveTo(10f * unit, 8f * unit)
                    lineTo(5f * unit, 13f * unit)
                    lineTo(10f * unit, 18f * unit)
                }
                drawPath(path, color, style = stroke)
            }
            "shift" -> {
                val path = Path().apply {
                    moveTo(12f * unit, 3f * unit); lineTo(21f * unit, 12f * unit)
                    lineTo(16f * unit, 12f * unit); lineTo(16f * unit, 19f * unit)
                    lineTo(8f * unit, 19f * unit); lineTo(8f * unit, 12f * unit)
                    lineTo(3f * unit, 12f * unit); close()
                }
                drawPath(path, color, style = stroke)
                if (locked) drawLine(color, p(8f, 22f), p(16f, 22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
