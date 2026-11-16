package org.thanatophilia.xyuime.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    data object ToggleCandidates : KeyboardAction
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
) {
    val configuration = LocalConfiguration.current
    val compactLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Reserve the candidate strip in the fixed keyboard height. Showing a composition only
    // changes the strip's contents, never the letter area or bottom row geometry.
    val preferredHeight = if (compactLandscape) 191.dp else 286.dp
    val maximumHeight = if (compactLandscape) 203.dp else 299.dp

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
                    .height(keyboardHeight),
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
    val rowGap = if (compactLandscape) 3.dp else 5.dp
    // Always reserved, so pinyin composition cannot push or squeeze the letter keys.
    val stripHeight = if (compactLandscape) 34.dp else 46.dp
    val stripScroll = remember(state.candidateRevision) { LazyListState() }
    val drawerScroll = remember(state.candidateRevision) { LazyGridState() }
    val reveal by animateFloatAsState(if (state.candidatesExpanded) 1f else 0f,
        animationSpec = tween(180), label = "candidateDrawer")
    val bottomHeight = if (compactLandscape) 34.dp else 47.dp
    val panelHeight = (
        keyboardHeight - (outerPadding * 2) - stripHeight - bottomHeight - (rowGap * 2)
    ).coerceAtLeast(1.dp)
    val keyShape = RoundedCornerShape(if (compactLandscape) 7.dp else 9.dp)

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = outerPadding, vertical = outerPadding),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        if (state.preedit.isNotEmpty()) CandidateStrip(state, onAction, stripScroll,
            Modifier.fillMaxWidth().height(stripHeight)) else InfoStrip(
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
                CandidateDrawer(state, onAction, drawerScroll,
                    Modifier.fillMaxSize().graphicsLayer {
                        translationY = -(1f - reveal) * size.height
                        alpha = reveal
                    })
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
    scroll: LazyListState, modifier: Modifier) {
    val current by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(scroll, state.candidates.size, state.loadingCandidates) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (last >= current.candidates.size - 8 && current.hasMoreCandidates && !current.loadingCandidates)
                    action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
            }
    }
    Column(modifier) {
        Text(state.preedit, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "拼音预编辑" })
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f).fillMaxHeight(), state = scroll,
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(state.candidates, key = { index, _ -> index }) { index, candidate ->
                    CandidateCell(candidate, index, state, onAction, Modifier.fillMaxHeight())
                }
            }
            Box(Modifier.size(44.dp).clickable(onClick = { onAction(KeyboardAction.ToggleCandidates) })
                .semantics {
                    testTagsAsResourceId = true
                    testTag = "candidate_toggle"
                    contentDescription = if (state.candidatesExpanded) "收回候选" else "展开候选"
                    stateDescription = if (state.candidatesExpanded) "已展开" else "已收起"
                    role = Role.Button
                }, contentAlignment = Alignment.Center) {
                Text(if (state.candidatesExpanded) "⌃" else "⌄", fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CandidateDrawer(state: KeyboardUiState, onAction: (KeyboardAction) -> Unit,
    scroll: LazyGridState, modifier: Modifier) {
    val current by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(scroll, state.candidates.size, state.loadingCandidates) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged().collect { last ->
                if (last >= current.candidates.size - 16 && current.hasMoreCandidates && !current.loadingCandidates)
                    action(KeyboardAction.LoadMoreCandidates(current.candidateRevision))
            }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(88.dp), state = scroll,
        modifier = modifier.semantics { testTagsAsResourceId = true; testTag = "candidate_drawer" }) {
        itemsIndexed(state.candidates, key = { index, _ -> index }) { index, candidate ->
            CandidateCell(candidate, index, state, onAction, Modifier.fillMaxWidth().heightIn(min = 44.dp))
        }
    }
}

@Composable
private fun CandidateCell(candidate: String, index: Int, state: KeyboardUiState,
    onAction: (KeyboardAction) -> Unit, modifier: Modifier) {
    Box(modifier.clickable { onAction(KeyboardAction.SelectCandidate(state.candidateRevision, index)) }
        .semantics {
            testTagsAsResourceId = true
            testTag = "candidate:$index"
            role = Role.Button
        }.padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(candidate, color = if (index == state.highlightedCandidate) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (index == state.highlightedCandidate) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 18.sp)
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
                    onLongClick = { onAction(KeyboardAction.CapsLock) }, icon = "shift", locked = state.capsLock)
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        KeyFace("！﹫#", "符号键盘", fontScale, keyShape, Modifier.weight(1.15f).fillMaxHeight(),
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
            onClick = { onAction(KeyboardAction.NextLanguage) }, onLongClick = { onAction(KeyboardAction.PickInputMethod) }, icon = "globe")
        KeyFace(state.actionLabel, "回车：${state.actionLabel}", fontScale, keyShape, Modifier.weight(1.45f).fillMaxHeight(),
            active = true, small = true, onClick = { onAction(KeyboardAction.Enter) })
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
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: String? = null, locked: Boolean = false) {
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
            testTagsAsResourceId = true
            testTag = "key:$description"
            if (icon == null) text = AnnotatedString(label)
            if (!enabled) disabled()
            onClick { if (available) { click(); true } else false }
            if (onLongClick != null && enabled) {
                this.onLongClick(label = "长按") { longClick?.invoke(); true }
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
