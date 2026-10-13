package com.ensemble.scorelibrary.core.pedal

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.core.model.PedalAction

val LocalPedal = staticCompositionLocalOf<PedalViewModel?> { null }

@Composable
fun PedalSettingsScreen(model: PedalViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(model) { model.settings(true); onDispose { model.settings(false) } }
    var menu by remember { mutableStateOf(false) }
    var debounce by remember(state.configuration?.debounceMs) { mutableFloatStateOf((state.configuration?.debounceMs ?: 150).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("脚踏设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(state.configuration?.enabled == true, model::enable, enabled = !state.busy && state.configuration != null,
                modifier = Modifier.testTag("pedal-enabled"))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    .onFailure { model.feedback("无法打开系统蓝牙设置") }
            }) { Icon(Icons.Outlined.Bluetooth, null); Text("系统蓝牙设置") }
            Spacer(Modifier.width(16.dp))
            Text("${state.devices.size} 个外接输入设备")
        }
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = !state.busy) {
                Icon(Icons.Outlined.Keyboard, null)
                Text(if (state.selected == "default") "默认映射" else state.devices.firstOrNull { it.profile == state.selected }?.name ?: "已保存的设备")
            }
            DropdownMenu(menu, { menu = false }) {
                val choices = (listOf("default") + state.devices.map { it.profile } + state.configuration?.profiles?.keys.orEmpty()).distinct()
                choices.forEachIndexed { index, profile -> DropdownMenuItem(text = { Text(if (profile == "default") "默认映射"
                    else state.devices.firstOrNull { it.profile == profile }?.name ?: "已保存的设备 $index") },
                    onClick = { model.select(profile); menu = false }) }
            }
        }
        listOf(PedalAction.NEXT_PAGE, PedalAction.PREVIOUS_PAGE, PedalAction.TOGGLE_PLAY_MODE).forEach { action ->
            val keys = state.configuration?.mapping(state.selected)?.filterValues { it == action }?.keys.orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(actionLabel(action))
                    Text(keys.joinToString { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }.ifEmpty { "未映射" },
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { model.learn(action) }, enabled = !state.busy,
                    modifier = Modifier.testTag("pedal-learn-${action.name}")) {
                    Icon(Icons.Outlined.Input, null); Text(if (state.learning == action) "等待按键" else "学习")
                }
                IconButton(onClick = { model.clear(action) }, enabled = !state.busy) { Icon(Icons.Outlined.Clear, "清除${actionLabel(action)}映射") }
            }
        }
        Text("防抖 ${debounce.toInt()} ms", style = MaterialTheme.typography.labelMedium)
        Slider(debounce, { debounce = it }, valueRange = 50f..600f, steps = 10, enabled = !state.busy,
            onValueChangeFinished = { model.debounce(debounce.toLong()) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.testing, onClick = { model.test(!state.testing) }, label = { Text("按键测试") },
                leadingIcon = { Icon(Icons.Outlined.Keyboard, null) }, modifier = Modifier.testTag("pedal-test"))
            IconButton(onClick = model::reset, enabled = !state.busy) { Icon(Icons.Outlined.RestartAlt, "恢复默认映射") }
            if (state.selected != "default") IconButton(onClick = model::removeProfile, enabled = !state.busy) { Icon(Icons.Outlined.Delete, "删除设备配置") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pedal-feedback")) }
    }
}
