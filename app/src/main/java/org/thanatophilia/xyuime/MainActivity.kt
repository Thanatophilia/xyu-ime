package org.thanatophilia.xyuime

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.thanatophilia.xyuime.ime.XyuImeService

class MainActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF426C65), onPrimary = Color.White,
                background = Color(0xFFF5F7F4), surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF233C36), onSurface = Color(0xFF233C36),
            )) {
                SetupScreen(enabled, selected,
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
}

@Composable
private fun SetupScreen(enabled: Boolean, selected: Boolean, onEnable: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("XYU / PHASE 01", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text("絮雨输入法", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text("从每一次落键开始。", style = MaterialTheme.typography.bodyLarge)
        Text("英文键盘测试版 · Compose", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6C7E77))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (selected) "已选用絮雨，可以开始试打" else if (enabled) "已启用，请选择絮雨" else "先在系统中启用絮雨", fontWeight = FontWeight.Medium)
                Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                    Text(if (enabled) "1 · 管理系统输入法" else "1 · 启用絮雨输入法")
                }
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("2 · 选择絮雨输入法") }
            }
        }
        TrialEditor()
        HorizontalDivider(color = Color(0xFFDCE4DF))
        Text("本次验收", style = MaterialTheme.typography.titleMedium)
        Text("• 字母区错列梯形，底部固定七键\n• Shift 切大小写，长按锁定大写\n• 删除键长按连续删除\n• 小地球点按切换内部语言，长按选择系统输入法\n• 当前仅 English，中文将在下一阶段接入 Rime", style = MaterialTheme.typography.bodyMedium)
        Text("请在系统“键盘位置”分别选择标准、抬高，检查底栏和系统切换入口是否完整。", style = MaterialTheme.typography.bodyMedium)
        Text("键盘离线运行，无网络权限，不记录输入内容。", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${config.screenWidthDp}dp × ${config.screenHeightDp}dp · ${config.densityDpi}dpi\n版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6C7E77))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TrialEditor() {
    var text by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(0) }
    var result by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("试打区", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("文本", "密码", "数字", "搜索").forEachIndexed { index, label ->
                TextButton(onClick = { mode = index; text = ""; result = "" }) {
                    Text(label, fontWeight = if (mode == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(listOf("点击这里输入英文", "密码输入测试", "数字输入测试", "搜索动作测试")[mode]) },
            singleLine = mode != 0,
            minLines = if (mode == 0) 3 else 1,
            visualTransformation = if (mode == 1) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (mode) { 1 -> KeyboardType.Password; 2 -> KeyboardType.Number; else -> KeyboardType.Text },
                imeAction = when (mode) { 0 -> ImeAction.Default; 3 -> ImeAction.Search; else -> ImeAction.Done },
            ),
            keyboardActions = KeyboardActions(onSearch = { result = "搜索动作已收到" }, onDone = { result = "完成动作已收到" }),
        )
        if (result.isNotEmpty()) Text(result, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}
