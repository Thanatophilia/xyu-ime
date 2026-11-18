package org.thanatophilia.xyuime

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.thanatophilia.xyuime.ime.XyuImeService

class MainActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private var trialSession by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF426C65), onPrimary = Color.White,
                background = Color(0xFFF5F7F4), surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF233C36), onSurface = Color(0xFF233C36),
            )) {
                SetupScreen(enabled, selected, trialSession,
                    onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onPick = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val id = ComponentName(this, XyuImeService::class.java).flattenToShortString()
        val manager = getSystemService(InputMethodManager::class.java)
        enabled = manager.enabledInputMethodList.any { it.id == id }
        selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id
    }

    override fun onStop() {
        // Trial contents are never saved; a new session also clears them on return.
        trialSession++
        super.onStop()
    }
}

@Composable
private fun SetupScreen(enabled: Boolean, selected: Boolean, trialSession: Int, onEnable: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("XYU / 离线中英文输入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("絮雨输入法", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text("从每一次落键开始。", style = MaterialTheme.typography.bodyLarge)
        Text("离线简体全拼 · 英文直输 · 基础编辑", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6C7E77))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (selected) "已选用絮雨，可以开始试打" else if (enabled) "已启用，请选择絮雨" else "先在系统中启用絮雨", fontWeight = FontWeight.Medium)
                Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (enabled) "1 · 管理系统输入法" else "1 · 启用絮雨输入法")
                }
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("2 · 选择絮雨输入法") }
            }
        }
        androidx.compose.runtime.key(trialSession) { TrialEditor() }
        HorizontalDivider(color = Color(0xFFDCE4DF))
        Text("当前能力", style = MaterialTheme.typography.titleMedium)
        Text("• 小地球点按切换中英文，长按选择系统输入法\n• 离线简体全拼、独立悬浮编码、滑动候选和完整候选页\n• 顶部“编辑”提供光标、选区、全选、复制、剪切、粘贴\n• 密码禁止复制剪切；仅主动粘贴时读取剪贴板\n• 完整 emoji 删除、独立 Shift 与自动大写、特殊输入框适配", style = MaterialTheme.typography.bodyMedium)
        Text("本版试打更新", style = MaterialTheme.typography.titleMedium)
        Text("新增中文长句、发送、下一项/上一项和双输入框试打。动作只在本地计数，不发送消息；切换类型或离开应用后清空试打内容。", style = MaterialTheme.typography.bodyMedium)
        Text("请在系统“键盘位置”分别选择标准、抬高，检查底栏和系统切换入口是否完整。", style = MaterialTheme.typography.bodyMedium)
        Text("键盘离线运行，无网络权限，不记录输入内容。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${config.screenWidthDp}dp × ${config.screenHeightDp}dp · ${config.densityDpi}dpi\n版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun TrialEditor() {
    var value by remember { mutableStateOf(TextFieldValue()) }
    var mode by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var actionCount by remember { mutableStateOf(0) }
    var trialRevision by remember { mutableStateOf(0) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? androidx.lifecycle.LifecycleOwner
    androidx.compose.runtime.DisposableEffect(hostActivity) {
        if (hostActivity == null) return@DisposableEffect onDispose { }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                focusManager.clearFocus(force = true)
                value = TextFieldValue(); result = ""; actionCount = 0; trialRevision++
            }
        }
        hostActivity.lifecycle.addObserver(observer)
        onDispose { hostActivity.lifecycle.removeObserver(observer) }
    }
    val labels = listOf("文本", "密码", "数字", "搜索", "邮箱", "网址", "电话", "小数", "负数", "数字密码", "句首大写", "词首大写", "全大写", "中文长句", "发送", "下一项/上一项", "双输入框")
    fun recordAction(label: String) { actionCount++; result = "${label}动作已收到 · 本地第 ${actionCount} 次" }
    fun select(index: Int) {
        focusManager.clearFocus(force = true)
        mode = index; value = TextFieldValue(); result = ""; actionCount = 0; trialRevision++
    }
    Column(Modifier.semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("试打区", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.take(4).forEachIndexed { index, label ->
                TextButton(onClick = { select(index) }) {
                    Text(label, fontWeight = if (mode == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起测试类型" else "更多测试类型") }
        if (expanded) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.drop(4).forEachIndexed { index, label ->
                TextButton(onClick = { select(index + 4) }) {
                    Text(label, fontWeight = if (mode == index + 4) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (mode == 8) {
            androidx.compose.runtime.key(trialRevision) { SignedNumberTrial(onResult = { recordAction("完成") }) }
        } else if (mode == 15 || mode == 16) {
            androidx.compose.runtime.key(trialRevision) { PairedTrialEditors(navigation = mode == 15, onAction = ::recordAction) }
        } else androidx.compose.runtime.key(mode, trialRevision) {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().testTag("trial_editor"),
                label = { Text(if (mode == 0) "点击这里输入中文或英文" else "${labels[mode]}输入测试") },
                singleLine = mode in 1..9 || mode == 14,
                minLines = if (mode == 0 || mode in 10..13) 3 else 1,
                visualTransformation = if (mode == 1 || mode == 9) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (mode) {
                        1 -> KeyboardType.Password; 2 -> KeyboardType.Number
                        4 -> KeyboardType.Email; 5 -> KeyboardType.Uri; 6 -> KeyboardType.Phone
                        7 -> KeyboardType.Decimal; 9 -> KeyboardType.NumberPassword; else -> KeyboardType.Text
                    },
                    capitalization = when (mode) { 10 -> KeyboardCapitalization.Sentences; 11 -> KeyboardCapitalization.Words; 12 -> KeyboardCapitalization.Characters; else -> KeyboardCapitalization.None },
                    imeAction = when (mode) { 0, 10, 11, 12, 13 -> ImeAction.Default; 3 -> ImeAction.Search; 5 -> ImeAction.Go; 14 -> ImeAction.Send; else -> ImeAction.Done },
                ),
                keyboardActions = KeyboardActions(onSearch = { recordAction("搜索") }, onDone = { recordAction("完成") }, onGo = { recordAction("前往") }, onSend = { recordAction("发送") }),
            )
        }
        if (mode == 0) TextButton(onClick = {
            val sample = "A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣e\u0301Z"
            value = TextFieldValue(sample, selection = TextRange(sample.length))
        }) { Text("填入 emoji 样例") }
        if (mode == 13) {
            Text("点按小地球切到中文，试输 nihaozhongguo，逐词选择后继续补拼音。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val sample = "今天用絮雨练习中文输入。\n在这里移动光标、选择文字，再继续输入。"
                value = TextFieldValue(sample, selection = TextRange(sample.length))
            }) { Text("填入中文样例") }
        }
        if (mode == 14) Text("发送仅本地计数，不发送消息。有拼音时第一次回车只提交拼音，第二次才计数。", style = MaterialTheme.typography.bodySmall)
        if (result.isNotEmpty()) Text(result, modifier = Modifier.testTag("trial_action_result"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PairedTrialEditors(navigation: Boolean, onAction: (String) -> Unit) {
    var first by remember { mutableStateOf(TextFieldValue()) }
    var second by remember { mutableStateOf(TextFieldValue()) }
    val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val secondFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    Text(if (navigation) "回车在两框间移动；动作只在本地计数。" else "在两框间点按切换，检查未提交拼音不会串到另一框。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = first, onValueChange = { first = it },
        modifier = Modifier.fillMaxWidth().focusRequester(firstFocus).testTag("trial_first"),
        label = { Text("输入框 A") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (navigation) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onNext = { onAction("下一项"); secondFocus.requestFocus() }, onDone = { onAction("A 完成") }),
    )
    OutlinedTextField(
        value = second, onValueChange = { second = it },
        modifier = Modifier.fillMaxWidth().focusRequester(secondFocus).testTag("trial_second"),
        label = { Text("输入框 B") }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = if (navigation) ImeAction.Previous else ImeAction.Done),
        keyboardActions = KeyboardActions(onPrevious = { onAction("上一项"); firstFocus.requestFocus() }, onDone = { onAction("B 完成") }),
    )
}

@Composable
private fun SignedNumberTrial(onResult: () -> Unit) {
    AndroidView(modifier = Modifier.fillMaxWidth().height(64.dp).testTag("trial_editor"), factory = { context ->
        EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "可输入负数与小数，例如 -12.5"
            setSingleLine(true)
            setSelectAllOnFocus(false)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { onResult(); true } else false
            }
        }
    })
}
