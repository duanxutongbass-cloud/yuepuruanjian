package com.ensemble.scorelibrary.diagnostics

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.BuildConfig

@Composable
fun TestOperationStatus() {
    if(!BuildConfig.TEST_OPERATION_LOGS) return
    val operations=LocalTestOperations.current ?: return
    val state by operations.status.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    val summary=when {
        state.key==null -> "登录后回传"
        state.failed -> "本机暂存，等待重试"
        state.pending>0 -> "待回传 ${state.pending} 条"
        state.sentAt>0 -> "已回传 NAS"
        else -> "等待操作记录"
    }
    TextButton(onClick={show=true},modifier=Modifier.testTag("test-operation-status")) { Text("测试诊断：$summary") }
    if(show) AlertDialog(onDismissRequest={show=false},title={Text("测试期操作记录")},
        text={Text("帮助定位闪屏与上传问题。记录按钮操作、处理阶段、版本、耗时和错误类型；联网时近实时回传本团队 NAS，离线加密暂存后补传。不会记录密码、登录令牌、文件名、PDF 内容或批注笔画。NAS 仅管理员可查，最多保留 30 天；本机最多暂存 500 条。正式发布构建关闭该功能。")},
        confirmButton={TextButton(onClick={show=false}) { Text("知道了") }})
}
