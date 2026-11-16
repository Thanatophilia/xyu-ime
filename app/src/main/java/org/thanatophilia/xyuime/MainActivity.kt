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
        Text("XYU / PHASE 02", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("絮雨输入法", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text("从每一次落键开始。", style = MaterialTheme.typography.bodyLarge)
        Text("基础编辑测试版 · Compose", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6C7E77))
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
        Text("本次验收", style = MaterialTheme.typography.titleMedium)
        Text("• 顶部“编辑”提供光标、选区、全选、复制、剪切、粘贴\n• 密码禁止复制剪切；粘贴只在主动点击时读取剪贴板\n• 完整 emoji 删除，自动大写与手动 Shift 独立\n• 邮箱、网址、电话、小数等输入框适配\n• 原有梯形字母区与七键底栏保留，当前仍为 English", style = MaterialTheme.typography.bodyMedium)
        Text("请在系统“键盘位置”分别选择标准、抬高，检查底栏和系统切换入口是否完整。", style = MaterialTheme.typography.bodyMedium)
        Text("键盘离线运行，无网络权限，不记录输入内容。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${config.screenWidthDp}dp × ${config.screenHeightDp}dp · ${config.densityDpi}dpi\n版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TrialEditor() {
    var value by remember { mutableStateOf(TextFieldValue()) }
    var mode by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val labels = listOf("文本", "密码", "数字", "搜索", "邮箱", "网址", "电话", "小数", "负数", "数字密码", "句首大写", "词首大写", "全大写")
    fun select(index: Int) { mode = index; value = TextFieldValue(); result = "" }
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
            SignedNumberTrial(onResult = { result = "完成动作已收到" })
        } else androidx.compose.runtime.key(mode) {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth().testTag("trial_editor"),
                label = { Text(if (mode == 0) "点击这里输入英文" else "${labels[mode]}输入测试") },
                singleLine = mode != 0 && mode < 10,
                minLines = if (mode == 0 || mode >= 10) 3 else 1,
                visualTransformation = if (mode == 1 || mode == 9) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (mode) {
                        1 -> KeyboardType.Password; 2 -> KeyboardType.Number
                        4 -> KeyboardType.Email; 5 -> KeyboardType.Uri; 6 -> KeyboardType.Phone
                        7 -> KeyboardType.Decimal; 9 -> KeyboardType.NumberPassword; else -> KeyboardType.Text
                    },
                    capitalization = when (mode) { 10 -> KeyboardCapitalization.Sentences; 11 -> KeyboardCapitalization.Words; 12 -> KeyboardCapitalization.Characters; else -> KeyboardCapitalization.None },
                    imeAction = when (mode) { 0, 10, 11, 12 -> ImeAction.Default; 3 -> ImeAction.Search; 5 -> ImeAction.Go; else -> ImeAction.Done },
                ),
                keyboardActions = KeyboardActions(onSearch = { result = "搜索动作已收到" }, onDone = { result = "完成动作已收到" }, onGo = { result = "前往动作已收到" }),
            )
        }
        if (mode == 0) TextButton(onClick = {
            val sample = "A👨‍👩‍👧‍👦👍🏽🇨🇳1️⃣e\u0301Z"
            value = TextFieldValue(sample, selection = TextRange(sample.length))
        }) { Text("填入 emoji 样例") }
        if (result.isNotEmpty()) Text(result, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
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
