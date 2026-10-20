package com.ensemble.scorelibrary.annotation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun StylusSettings(state:AnnotationState,model:AnnotationViewModel,close:()->Unit) {
    val enabled=!state.gestureActive
    AlertDialog(onDismissRequest=close,title={Text("手写笔设置")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("压感粗细",Modifier.weight(1f))
                Switch(state.pressureEnabled,model::pressure,enabled=enabled,modifier=Modifier.testTag("stylus-pressure"))
            }
            Text("仅用于手写工具：轻写细、重写粗；荧光和符号保持固定粗细。只使用设备上报的笔压力，手指不使用压感。")
            Text("笔侧键",style=MaterialTheme.typography.titleSmall)
            InkSideButton.entries.forEach { option -> Row(verticalAlignment=Alignment.CenterVertically) {
                RadioButton(state.sideButton==option,{model.sideButton(option)},enabled=enabled,modifier=Modifier.testTag("stylus-side-${option.name}"))
                Text(option.label)
            } }
            Text("落笔前按住所选侧键，临时整笔擦除，仍可撤销；不改变当前工具。行笔中切换侧键会取消未完成笔画，请抬笔后继续。阅读模式或编辑锁定时不会擦除。")
            Text("需要设备把压力和侧键上报给应用。部分笔的双击或快捷手势由系统处理；没有上报时这些设置不会模拟硬件能力。旧笔迹保持原有粗细，设置只影响新笔画。",style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick=close) { Text("完成") }})
}
