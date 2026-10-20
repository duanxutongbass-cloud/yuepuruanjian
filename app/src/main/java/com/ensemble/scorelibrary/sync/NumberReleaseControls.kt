package com.ensemble.scorelibrary.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NumberInventoryDialog(state:EnsembleState,close:()->Unit,preview:(Set<String>)->Unit,
    confirm:(NumberReleasePreview)->Unit,dismiss:()->Unit) {
    val release=state.numberRelease
    var selected by remember(state.key,release?.revision) { mutableStateOf(emptySet<String>()) }
    val confirmation=state.numberReleasePreview
    if(confirmation!=null) {
        var consent by remember(confirmation.request) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={if(!state.busy)dismiss()},title={Text("确认释放编号")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("释放 ${confirmation.sequences.size} 个编号：${confirmation.sequences.sorted().joinToString("、")}")
            Text("这些曲目的全部分谱均已彻底删除。将先完整备份NAS曲库，再释放编号；其余曲目原号保留。")
            Text("旧曲谱、批注和删除记录不会恢复。重新使用编号的曲目会建立独立身份，旧版阅读端需要更新。")
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(consent,{consent=it},enabled=!state.busy,modifier=Modifier.testTag("number-release-consent"))
                Text("确认仅释放上述已彻底删除曲目的编号")
            }
            if(state.busy)Text("正在备份并释放，请稍候…")
            else if(state.message.isNotBlank())Text(state.message)
        }},confirmButton={TextButton(onClick={confirm(confirmation)},enabled=consent && !state.busy,
            modifier=Modifier.testTag("number-release-confirm")){Text("备份并释放")}},
            dismissButton={TextButton(onClick=dismiss,enabled=!state.busy){Text("取消")}})
        return
    }
    AlertDialog(onDismissRequest={if(!state.busy)close()},title={Text("编号检查")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        val inventory=state.numberInventory
        if(state.busy)Text("正在核对总库、回收站与排队上传…")
        else if(inventory==null)Text(state.message.ifBlank{"尚未取得编号清单，请重试"})
        else {
            Text("已占用或保留 ${inventory.occupied.size} 个编号，可用空号 ${inventory.availableCount} 个")
            Text("下一首新曲：${inventory.firstFree?.toString()?.padStart(3,'0') ?: "当前容量已满"}",Modifier.testTag("number-next-free"))
            Text("未完成上传 ${inventory.pendingUploads} 份，其编号暂时保留")
            Text("已有曲目不改号；新曲优先补空号。仍在曲库、回收站或待传队列中的编号不能释放。")
            if(release!=null) {
                Text("已彻底删除整首曲目、可释放的编号：${release.eligible.size} 个")
                if(release.eligible.isEmpty())Text("暂无可释放编号")
                else {
                    Row {
                        TextButton(onClick={selected=release.eligible.take(200).map{it.sequence}.toSet()},enabled=!state.busy,
                            modifier=Modifier.testTag("number-release-select-all")){Text(if(release.eligible.size>200)"选择前200个" else "全选可释放编号")}
                        TextButton(onClick={selected=emptySet()},enabled=selected.isNotEmpty() && !state.busy){Text("取消选择")}
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max=200.dp)) {
                        items(release.eligible,key={it.sequence}) { number -> Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(number.sequence in selected,{checked->selected=if(checked)selected+number.sequence else selected-number.sequence},
                                enabled=!state.busy && (number.sequence in selected || selected.size<200),
                                modifier=Modifier.testTag("number-release-select-${number.sequence}"))
                            Text("${number.sequence} · 已删除 ${number.parts} 份历史分谱")
                        } }
                    }
                    TextButton(onClick={preview(selected)},enabled=selected.isNotEmpty() && !state.busy,
                        modifier=Modifier.testTag("number-release-preview")){Text("预览释放所选编号（${selected.size}）")}
                }
            } else Text("尚未取得可释放清单，请重新检查编号")
        }
    }},confirmButton={TextButton(onClick=close,enabled=!state.busy){Text("知道了")}})
}
