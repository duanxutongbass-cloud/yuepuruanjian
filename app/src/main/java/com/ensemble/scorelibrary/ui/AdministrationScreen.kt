package com.ensemble.scorelibrary.ui

import com.ensemble.scorelibrary.domain.ScoreNumber

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.data.ImportScoreRequest
import com.ensemble.scorelibrary.domain.Dashboard
import com.ensemble.scorelibrary.domain.OrganizerRules
import com.ensemble.scorelibrary.domain.parseOrganizerFilename

val LocalAdministrationViewModel = staticCompositionLocalOf<AdministrationViewModel?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdministrationScreen(dashboard: Dashboard, vm: AdministrationViewModel, initialTab: Int = 0,
    initialId: String? = null, createRecord: Boolean = false, contextOnly: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rulesPreferences = remember(context) { context.getSharedPreferences("organizer-naming", android.content.Context.MODE_PRIVATE) }
    var namingRules by remember(context) { mutableStateOf(OrganizerRules.read(
        rulesPreferences.getString("rules", null) ?: context.assets.open("score-organizer-rules.json").bufferedReader().use { it.readText() })) }
    var name by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var part by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("1") }
    var end by rememberSaveable { mutableStateOf("") }
    var concert by rememberSaveable { mutableStateOf("") }
    var recipient by rememberSaveable { mutableStateOf<String?>(null) }
    var grantAdministrator by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val actor = dashboard.profile?.id
    val canManage = dashboard.profile?.canManage == true
    val management=LocalManagement.current
    val managementState=management?.state?.collectAsStateWithLifecycle()?.value
    var tab by rememberSaveable(actor) { mutableIntStateOf(initialTab) }
    var initialEntry by rememberSaveable(actor) { mutableStateOf(true) }
    LaunchedEffect(actor,canManage){management?.bind(actor.takeIf{canManage})}
    val target = dashboard.members.firstOrNull { it.id == recipient }
        ?: dashboard.members.firstOrNull { it.id == actor }
    // Choosing a file does not require completed metadata or publish an asset.
    // Both the request and selection belong to the current administrator.
    var pendingActor by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var selectedUri by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable(actor, canManage) { mutableStateOf("") }
    var automaticTitle by rememberSaveable(actor, canManage) { mutableStateOf("") }
    var pickerMessage by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var namingMessage by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    var categoryCode by rememberSaveable(actor) { mutableStateOf("") }
    var categoryName by rememberSaveable(actor) { mutableStateOf("") }
    var sequence by rememberSaveable(actor) { mutableStateOf("") }
    var appendToNumberedPiece by rememberSaveable(actor) { mutableStateOf(false) }
    var automaticPart by rememberSaveable(actor) { mutableStateOf("") }
    var automaticCategoryCode by rememberSaveable(actor) { mutableStateOf("") }
    var automaticCategoryName by rememberSaveable(actor) { mutableStateOf("") }
    var automaticSequence by rememberSaveable(actor) { mutableStateOf("") }
    fun suggestFromFilename(name: String) {
        val parsed = parseOrganizerFilename(name, namingRules)
        val suggestedTitle = (if (parsed.error == null) parsed.title else null)
            ?: name.replace(Regex("(?i)\\.pdf$"), "").take(200)
        if (title.isBlank() || title == automaticTitle) title = suggestedTitle
        automaticTitle = suggestedTitle
        val valid = parsed.error == null
        val newPart = if (valid) parsed.instrumentName.orEmpty() else ""
        val newCode = if (valid) parsed.categoryCode.orEmpty() else ""
        val newCategory = if (valid) parsed.categoryName.orEmpty() else ""
        val newSequence = if (valid) parsed.sequence.orEmpty() else ""
        if (part.isBlank() || part == automaticPart) part = newPart
        if (categoryCode.isBlank() || categoryCode == automaticCategoryCode) categoryCode = newCode
        if (categoryName.isBlank() || categoryName == automaticCategoryName) categoryName = newCategory
        if (sequence.isBlank() || sequence == automaticSequence) sequence = newSequence
        automaticPart = newPart; automaticCategoryCode = newCode; automaticCategoryName = newCategory; automaticSequence = newSequence
        appendToNumberedPiece = false
        namingMessage = parsed.error ?: "已按电脑端规则识别，请核对曲名、乐器、分类和编号后确认导入。"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingActor
        if (uri != null && pending != null && pending == actor && canManage) {
            selectedUri = uri.toString()
            selectedName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "已选择 PDF" }
            suggestFromFilename(selectedName)
            pickerMessage = null
        }
        pendingActor = null
    }
    var rulesActor by rememberSaveable(actor, canManage) { mutableStateOf<String?>(null) }
    val rulesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && rulesActor == actor && canManage) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(64 * 1024) }
                    ?: error("无法读取字典文件")
                val text = bytes.toString(Charsets.UTF_8)
                val loaded = OrganizerRules.read(text)
                check(rulesPreferences.edit().putString("rules", text).commit()) { "字典保存失败" }
                namingRules = loaded
                if (selectedUri != null) suggestFromFilename(selectedName)
                "电脑端字典已加载，只影响之后的文件识别。"
            }.onSuccess { pickerMessage = it }.onFailure { pickerMessage = "字典未更改：${it.message}" }
        }
        rulesActor = null
    }
    val importHint = when {
        selectedUri == null -> "请先选择 PDF，再填写或确认曲目和声部信息。"
        title.isBlank() -> "请填写曲目名称。"
        title.trim().length > 200 -> "曲目名称最多 200 字。"
        part.isBlank() -> "请填写声部名称。"
        part.trim().length > 100 -> "声部名称最多 100 字。"
        target == null -> "请选择授权成员。"
        (start.toIntOrNull() ?: 0) <= 0 -> "起始页须为大于 0 的整数。"
        end.isNotBlank() && (end.toIntOrNull() ?: 0) < (start.toIntOrNull() ?: 1) -> "结束页须为空，或为不小于起始页的整数。"
        concert.trim().length > 200 -> "节目单名称最多 200 字。"
        (categoryCode.isNotBlank() || categoryName.isNotBlank()) &&
            (!categoryCode.matches(Regex("[0-9]{2}")) || categoryName.isBlank() || categoryName.length > 100) -> "分类须填写两位编号及名称。"
        sequence.isNotBlank() && runCatching { ScoreNumber.sequence(sequence) }.isFailure -> "整理器编号须为规范十进制整数；无编号的旧文件可以留空。"
        sequence.isNotBlank() && categoryCode.isBlank() -> "有整理器编号的曲目须确认分类。"
        appendToNumberedPiece && sequence.isBlank() -> "补入已有曲目须填写其编号。"
        else -> null
    }
    val bootstrap = dashboard.members.isEmpty()
    Column(Modifier.fillMaxSize()) {
    if(canManage && management!=null) {
        if (!contextOnly) PrimaryScrollableTabRow(tab) { managementTabs.forEachIndexed{index,label->Tab(tab==index,{tab=index;initialEntry=false},
            enabled=!state.busy && managementState?.busy!=true,text={Text(label)},modifier=Modifier.testTag("manage-tab-$index"))} }
        if(tab>0) { ManagementConsole(requireNotNull(actor),tab,management, initialId.takeIf { initialEntry && tab == initialTab }, createRecord && initialEntry && tab == initialTab); return@Column }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!contextOnly || bootstrap) Text(if (bootstrap) "创建本地管理员" else "管理", style = MaterialTheme.typography.headlineSmall)
        if (!bootstrap && !canManage) {
            Text("请在“我的”中选择管理员")
            return@Column
        }
        if (!contextOnly || bootstrap) {
        OutlinedTextField(name, { name = it }, label = { Text("成员姓名") }, singleLine = true,
            enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.createMember(actor, name) }, enabled = !state.busy && name.isNotBlank()) {
            Icon(Icons.Outlined.PersonAdd, null)
            Text(if (bootstrap) "创建管理员" else "添加成员")
        }
        }
        if (canManage) {
            HorizontalDivider()
            Text("导入曲谱", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                pendingActor = actor
                pickerMessage = null
                try { picker.launch(arrayOf("application/pdf")) }
                catch (_: ActivityNotFoundException) {
                    pendingActor = null
                    pickerMessage = "无法打开系统文件选择器，请确认设备有可用的文件管理应用。"
                }
            }, enabled = !state.busy && pendingActor == null, modifier = Modifier.testTag("import-select-pdf")) {
                Icon(Icons.Outlined.FileOpen, null); Text(if (selectedUri == null) "选择 PDF" else "重新选择 PDF")
            }
            if (selectedUri != null) Text("已选择：$selectedName", modifier = Modifier.testTag("import-selected-file"))
            TextButton(onClick = {
                rulesActor = actor
                try { rulesPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                catch (_: ActivityNotFoundException) { rulesActor = null; pickerMessage = "无法打开系统文件选择器" }
            }, enabled = !state.busy && rulesActor == null && pendingActor == null) { Text("加载电脑端字典 JSON") }
            pickerMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            namingMessage?.let { Text(it, modifier = Modifier.testTag("import-naming-result")) }
            OutlinedTextField(title, { title = it }, label = { Text("新曲目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            OutlinedTextField(part, { part = it }, label = { Text("声部名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            AdminChoice("分类", categoryCode.ifBlank { null }, listOf(null to "不分类") + namingRules.categories.map { it.key to "${it.key} · ${it.value}" },
                enabled = !state.busy, tag = "import-category") { code -> categoryCode = code.orEmpty(); categoryName = namingRules.categories[code].orEmpty() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(categoryCode, { categoryCode = it }, label = { Text("分类编号（可选）") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
                OutlinedTextField(categoryName, { categoryName = it }, label = { Text("分类名称（可选）") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
            }
            OutlinedTextField(sequence, { sequence = it }, label = { Text("整理器曲目编号（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            if (sequence.isNotBlank()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(appendToNumberedPiece, { appendToNumberedPiece = it }, enabled = !state.busy)
                Text("仅补入已有编号；同编号、曲名和分类一致时会自动归集声部")
            }
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !state.busy) { Text("授权成员：${target?.name ?: "未选择"}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("关闭菜单") }, onClick = { menu = false })
                    dashboard.members.forEach { member -> DropdownMenuItem(text = { Text(member.name) },
                        onClick = { recipient = member.id; menu = false }) }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(grantAdministrator, { grantAdministrator = it }, enabled = !state.busy)
                Text("同时授权当前管理员")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("起始页") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
                OutlinedTextField(end, { end = it }, label = { Text("结束页（空为末页）") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !state.busy)
            }
            OutlinedTextField(concert, { concert = it }, label = { Text("节目单名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
            importHint?.let { Text(it, modifier = Modifier.testTag("import-required-fields")) }
            Button(onClick = {
                vm.importScore(requireNotNull(actor), Uri.parse(requireNotNull(selectedUri)),
                    ImportScoreRequest(title, part, requireNotNull(target).id, requireNotNull(start.toIntOrNull()),
                        end.toIntOrNull(), concert, grantAdministrator, categoryCode.ifBlank { null }, categoryName.ifBlank { null },
                        sequence.ifBlank { null }, appendToNumberedPiece))
            }, enabled = !state.busy && pendingActor == null && importHint == null,
                modifier = Modifier.testTag("import-confirm")) {
                Text("确认导入")
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) return result.toByteArray()
        require(result.size() + size <= limit) { "字典文件超过 64 KiB" }
        result.write(buffer, 0, size)
    }
}
