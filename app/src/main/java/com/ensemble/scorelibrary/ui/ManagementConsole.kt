package com.ensemble.scorelibrary.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.data.*
import com.ensemble.scorelibrary.domain.*
import java.util.UUID

val LocalManagement=staticCompositionLocalOf<ManagementViewModel?>{null}
val managementTabs=listOf("导入","曲目","成员","标准声部","节目单","PDF 版本")

@Composable
fun ManagementConsole(actor: String, tab: Int, vm: ManagementViewModel, initialId: String? = null, createRecord: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog=state.catalog?.takeIf{state.actor==actor}
    var archives by rememberSaveable(actor,tab){mutableStateOf(false)}
    var pieceQuery by rememberSaveable(actor) { mutableStateOf("") }
    var pieceCategory by rememberSaveable(actor) { mutableStateOf<String?>(null) }
    var pieceDefinition by rememberSaveable(actor) { mutableStateOf<String?>(null) }
    var piecePdf by rememberSaveable(actor) { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(actor,tab){mutableStateOf(initialId)}
    var openedInitial by rememberSaveable(actor, tab) { mutableStateOf(false) }
    var editKind by rememberSaveable(actor,tab){mutableStateOf<String?>(null)}
    var editId by rememberSaveable(actor,tab){mutableStateOf("")}
    var editParent by rememberSaveable(actor,tab){mutableStateOf("")}
    var editToken by rememberSaveable(actor,tab){mutableStateOf("")}
    var openingRevision by rememberSaveable(actor,tab){mutableLongStateOf(0)}
    var versionPdf by rememberSaveable(actor,tab){mutableStateOf<String?>(null)}
    var versionId by rememberSaveable(actor,tab){mutableStateOf<String?>(null)}
    var upload by rememberSaveable(actor,tab){mutableStateOf(false)}
    var uploadTitle by rememberSaveable(actor,tab){mutableStateOf("")}
    var pendingActor by rememberSaveable{mutableStateOf<String?>(null)}
    var pendingToken by rememberSaveable{mutableStateOf("")}
    var pendingPdf by rememberSaveable{mutableStateOf<String?>(null)}
    var pendingTitle by rememberSaveable{mutableStateOf("")}
    LaunchedEffect(actor){if(pendingActor!=actor)pendingActor=null}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        val requestActor=pendingActor
        if(uri!=null && requestActor==actor) vm.importPdf(actor,pendingToken,uri,pendingTitle,pendingPdf)
        pendingActor=null
    }
    fun open(kind: String,id: String=UUID.randomUUID().toString(),parent: String="") {
        vm.clearMessage();editKind=kind;editId=id;editParent=parent;editToken=state.token;openingRevision=state.revision
    }
    LaunchedEffect(state.revision){if(state.revision>openingRevision){editKind=null;upload=false;versionPdf=null;versionId=null}}
    if(catalog==null) {
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally){
            if(state.message==null) CircularProgressIndicator() else {Text(state.message!!);AdminIcon("刷新目录",Icons.Outlined.Refresh){vm.refresh()}}
        }; return
    }
    val d=catalog.data
    val pieceRows = remember(d, archives) { managedPieceRows(d, archives) }
    val rows=when(tab){1->filterManagedPieces(pieceRows,pieceQuery,pieceCategory,pieceDefinition,piecePdf).map{it.piece.id to (it.heading+if(it.piece.isArchived)"（归档）" else "")}
        2->d.members.filter{archives || !it.isArchived}.map{it.id to (it.displayName+if(it.isArchived)"（归档）" else "")}
        3->d.definitions.filter{archives || !it.isArchived}.map{it.id to (it.name+if(it.isArchived)"（归档）" else "")}
        4->d.concerts.filter{archives || !it.isArchived}.map{it.id to (it.title+if(it.id in catalog.lockedConcerts)" · 锁定" else if(it.isArchived)"（归档）" else "")}
        else->d.pdfs.filter{archives || !it.isArchived}.map{it.id to (it.title+if(it.isArchived)"（归档）" else "")}}
        .sortedBy{it.second}
    LaunchedEffect(rows.map{it.first}) {if(selection!=null && rows.none{it.first==selection}) selection=null}
    val kind=when(tab){1->"piece";2->"member";3->"definition";4->"concert";else->"pdf"}
    LaunchedEffect(createRecord, catalog) { if (createRecord && !openedInitial) { openedInitial = true; open(kind) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(managementTabs[tab],style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            Checkbox(archives,{archives=it},enabled=!state.busy);Text("含归档")
            AdminIcon("刷新目录",Icons.Outlined.Refresh,!state.busy){vm.refresh()}
            AdminIcon(if(tab==5)"导入新 PDF" else "新增",Icons.Outlined.Add,!state.busy,"new") {
                if(tab==5){vm.clearMessage();uploadTitle="";versionPdf=null;upload=true;editToken=state.token;openingRevision=state.revision}
                else open(kind)
            }
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tab == 1) {
            OutlinedTextField(pieceQuery, { pieceQuery=it }, label={Text("搜索曲名、编号、分类、乐器、作曲／编曲")},
                singleLine=true,modifier=Modifier.fillMaxWidth().testTag("admin-piece-search"))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AdminChoice("分类",pieceCategory,listOf(null to "全部分类")+
                    pieceRows.map{it.piece.categoryName ?: "未分类"}.distinct().sorted().map{it to it},tag="admin-category",change={pieceCategory=it}) }
                Box(Modifier.weight(1f)) { AdminChoice("乐器／声部",pieceDefinition,listOf(null to "全部声部")+
                    d.definitions.filterNot{it.isArchived}.map{it.id to it.name},tag="admin-instrument",change={pieceDefinition=it}) }
                Box(Modifier.weight(1f)) { AdminChoice("配谱状态",piecePdf,listOf(null to "全部状态","ready" to "已有乐谱","missing" to "待补乐谱"),
                    tag="admin-pdf-state",change={piecePdf=it}) }
            }
        }
        state.message?.let{Text(it,Modifier.padding(vertical=8.dp).testTag("management-message"),style=MaterialTheme.typography.bodySmall)}
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide=maxWidth>=840.dp
            @Composable fun master(modifier: Modifier) {
                LazyColumn(modifier.testTag("management-list")) {
                    if(rows.isEmpty()) item{Text("暂无记录",Modifier.padding(16.dp))}
                    items(rows,key={it.first}){(id,label)->
                        ListItem(headlineContent={Text(label,maxLines=2)},supportingContent={if(tab==1) {
                            val piece=d.pieces.first{it.id==id}
                            val instruments=d.parts.filter{it.pieceId==id && !it.isArchived}.mapNotNull { part ->
                                part.displayName ?: d.definitions.firstOrNull{it.id==part.partDefinitionId && !it.isArchived}?.name
                            }.distinct()
                            Text((listOfNotNull(piece.categoryName)+instruments).joinToString(" · "),style=MaterialTheme.typography.bodySmall)
                        }},modifier=Modifier.clickable{selection=id}.testTag("managed-$id"),
                            trailingContent={AdminIcon("编辑",Icons.Outlined.Edit,!state.busy,"edit-$id"){open(kind,id)}})
                        HorizontalDivider()
                    }
                }
            }
            @Composable fun detail(modifier: Modifier) {
                val selected=selection
                Column(modifier.testTag("management-detail")) {
                    if(selected==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Inventory2,"未选择记录")}
                    else {
                        Row(verticalAlignment=Alignment.CenterVertically){
                            if(!wide) AdminIcon("返回列表",Icons.AutoMirrored.Outlined.ArrowBack,!state.busy,"list-back"){selection=null}
                            Text(rows.firstOrNull{it.first==selected}?.second.orEmpty(),maxLines=2,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                            AdminIcon("编辑",Icons.Outlined.Edit,!state.busy,"detail-edit"){open(kind,selected)}
                        }
                        when(tab) {
                            1->PieceManagement(selected,catalog,state,archives,::open)
                            2->MemberManagement(actor,selected,catalog,state,vm)
                            3->Text(d.definitions.firstOrNull{it.id==selected}?.family.orEmpty(),Modifier.padding(16.dp))
                            4->ConcertManagement(actor,selected,catalog,state,archives,vm,::open)
                            5->{
                                val pdf=d.pdfs.first{it.id==selected}
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text("历史版本",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                                    AdminIcon("替换 PDF",Icons.Outlined.FileOpen,!state.busy && !pdf.isArchived,"replace"){vm.clearMessage();versionPdf=pdf.id;uploadTitle=pdf.title;upload=true;editToken=state.token;openingRevision=state.revision}
                                }
                                LazyColumn {
                                    items(d.versions.filter{it.scorePdfId==selected}.sortedByDescending{it.versionNumber},key={it.id}){version->
                                        ListItem(headlineContent={Text("v${version.versionNumber}"+if(pdf.activeVersionId==version.id)" · 当前" else "")},
                                            supportingContent={Text("${version.pageCount} 页 · ${version.fileSizeBytes / 1024} KB")},
                                            trailingContent={AdminIcon("切换此版本",Icons.Outlined.Restore,!state.busy && pdf.activeVersionId!=version.id && !pdf.isArchived,"activate-${version.id}"){
                                                vm.clearMessage();versionPdf=pdf.id;versionId=version.id;editToken=state.token;openingRevision=state.revision
                                            }})
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(wide) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                master(Modifier.weight(.35f).fillMaxHeight());VerticalDivider();detail(Modifier.weight(.65f).fillMaxHeight())
            } else if(selection==null) master(Modifier.fillMaxSize()) else detail(Modifier.fillMaxSize())
        }
    }
    editKind?.let { edit -> key(actor,editId){ManagementEditor(EditDescriptor(edit,editId,editParent),catalog,state,editToken,
        close={editKind=null},submit={vm.edit(actor,editToken,it)})} }
    if(upload || versionId!=null) AlertDialog(onDismissRequest={if(!state.busy){upload=false;versionId=null}},
        title={Text(if(upload)if(versionPdf==null)"导入 PDF" else "替换 PDF" else "切换当前版本")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(upload) OutlinedTextField(uploadTitle,{uploadTitle=it},label={Text("PDF 名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)
            Text("各版本批注独立。旧版本与批注保留，不会自动迁移到新版本。")
            if(editToken!=state.token) Text("目录已更新，请关闭后重新操作",color=MaterialTheme.colorScheme.error)
            state.message?.let{Text(it)}
        }},
        confirmButton={AdminIcon(if(upload)"选择 PDF" else "确认切换",if(upload)Icons.Outlined.FileOpen else Icons.Outlined.Check,
            !state.busy && editToken==state.token && (!upload || uploadTitle.isNotBlank()) && pendingActor==null,"version-confirm") {
            if(upload){pendingActor=actor;pendingToken=editToken;pendingPdf=versionPdf;pendingTitle=uploadTitle;upload=false;picker.launch(arrayOf("application/pdf"))}
            else vm.activate(actor,editToken,versionPdf!!,versionId!!)
        }},dismissButton={AdminIcon("取消",Icons.Outlined.Close,!state.busy,"version-cancel"){upload=false;versionId=null}})
}

@Composable
private fun PieceManagement(id: String,catalog: ManagementCatalog,state: ManagementState,archives: Boolean,open: (String,String,String)->Unit) {
    val d=catalog.data; val piece=d.pieces.first{it.id==id}
    val editable=!state.busy && !piece.isArchived
    LazyColumn(Modifier.fillMaxSize()) {
        item{Row(verticalAlignment=Alignment.CenterVertically){Text("乐章",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            AdminIcon("新增乐章",Icons.Outlined.Add,editable,"new-movement"){open("movement",UUID.randomUUID().toString(),id)}}}
        items(d.movements.filter{it.pieceId==id && (archives || !it.isArchived)}.sortedBy{it.sortOrder},key={it.id}){movement->
            ListItem(headlineContent={Text(movement.title+if(movement.isArchived)"（归档）" else "")},supportingContent={Text("排序 ${movement.sortOrder}")},
                trailingContent={AdminIcon("编辑乐章",Icons.Outlined.Edit,!state.busy,"edit-${movement.id}"){open("movement",movement.id,id)}})
        }
        item{HorizontalDivider();Row(verticalAlignment=Alignment.CenterVertically){Text("曲目声部与谱段",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            AdminIcon("新增声部",Icons.Outlined.Add,editable,"new-part"){open("part",UUID.randomUUID().toString(),id)}}}
        for(part in d.parts.filter{it.pieceId==id && (archives || !it.isArchived)}) {
            item(key="part-${part.id}") { Row(verticalAlignment=Alignment.CenterVertically) {
                Text((part.displayName ?: d.definitions.firstOrNull{it.id==part.partDefinitionId}?.name.orEmpty())+if(part.isArchived)"（归档）" else "",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                AdminIcon("编辑声部",Icons.Outlined.Edit,!state.busy,"edit-${part.id}"){open("part",part.id,id)}
                AdminIcon("新增谱段",Icons.Outlined.Add,editable && !part.isArchived,"new-binding-${part.id}"){open("binding",UUID.randomUUID().toString(),part.id)}
            } }
            items(d.bindings.filter{it.piecePartId==part.id && (archives || !it.isArchived)},key={it.id}){binding->
                val pdf=d.pdfs.firstOrNull{it.id==binding.scorePdfId}
                ListItem(headlineContent={Text(if(binding.movementId==null)"整曲" else d.movements.firstOrNull{it.id==binding.movementId}?.title ?: "乐章失效")},
                    supportingContent={Text("${pdf?.title ?: "PDF 失效"} · ${binding.startPage}–${binding.endPage} 页"+if(binding.isArchived)"（归档）" else "")},
                    trailingContent={AdminIcon("编辑谱段",Icons.Outlined.Edit,!state.busy,"edit-${binding.id}"){open("binding",binding.id,part.id)}})
            }
        }
    }
}

@Composable
private fun MemberManagement(actor: String,id: String,catalog: ManagementCatalog,state: ManagementState,vm: ManagementViewModel) {
    val d=catalog.data; val member=d.members.first{it.id==id}
    var piece by rememberSaveable(id){mutableStateOf<String?>(null)}
    val key=piece?.let{MemberPieceKey(id,it)}
    val score=key?.let{catalog.scores[it]}
    var assigned by rememberSaveable(id,piece,state.token){mutableStateOf(d.assignments.filter{it.memberId==id && !it.isArchived && d.parts.any{p->p.id==it.piecePartId && p.pieceId==piece}}.singleOrNull()?.piecePartId)}
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(if(member.role==com.ensemble.scorelibrary.core.model.MemberRole.ADMIN)"管理员" else "成员",Modifier.padding(vertical=12.dp))
            Text("按声部授权 · 全部曲目",style=MaterialTheme.typography.titleSmall) }
        items(d.definitions.filterNot{it.isArchived},key={it.id}){definition->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(d.permissions.any{it.memberId==id && it.partDefinitionId==definition.id && !it.isArchived},
                    {vm.edit(actor,state.token,ManagementCommand.GlobalGrant(id,definition.id,it))},enabled=!state.busy && !member.isArchived,
                    modifier=Modifier.testTag("global-${definition.id}"));Text(definition.name,maxLines=2)
            }
        }
        item{HorizontalDivider();AdminChoice("单曲",piece,d.pieces.filterNot{it.isArchived}.map{it.id to it.title},!state.busy,tag="member-piece",change={piece=it})}
        if(piece!=null) {
            items(d.parts.filter{it.pieceId==piece && !it.isArchived},key={"permission-${it.id}"}){part->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(d.extraPermissions.any{it.memberId==id && it.piecePartId==part.id && !it.isArchived},
                        {vm.edit(actor,state.token,ManagementCommand.ScopedGrant(id,part.id,it))},enabled=!state.busy && !member.isArchived,
                        modifier=Modifier.testTag("scoped-${part.id}"))
                    Text(part.displayName ?: d.definitions.first{it.id==part.partDefinitionId}.name,maxLines=2)
                }
            }
            item {
                AdminChoice("实际演奏分配",assigned,listOf(null to "使用默认声部")+catalog.allowedParts[key].orEmpty().map{it.id to it.name},!state.busy && !member.isArchived,tag="assignment",change={assigned=it})
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(when(score){is DomainResult.Success->"当前：${score.value.part.name}";is DomainResult.Failure->errorText(score.error.code);else->"暂无解析结果"},Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    AdminIcon("保存分配",Icons.Outlined.Save,!state.busy && !member.isArchived,"assign"){vm.edit(actor,state.token,ManagementCommand.Assignment(id,piece!!,assigned))}
                }
            }
        }
    }
}

@Composable
private fun ConcertManagement(actor: String,id: String,catalog: ManagementCatalog,state: ManagementState,archives: Boolean,
    vm: ManagementViewModel,open: (String,String,String)->Unit) {
    val d=catalog.data;val concert=d.concerts.first{it.id==id};val all=d.items.filter{it.concertId==id}.sortedBy{it.sortOrder}
    val active=all.filterNot{it.isArchived};val editable=!state.busy && !concert.isArchived && id !in catalog.lockedConcerts
    LazyColumn(Modifier.fillMaxSize()) {
        item{Row(verticalAlignment=Alignment.CenterVertically){Text(if(id in catalog.lockedConcerts)"节目项已锁定" else "节目项",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            AdminIcon("追加曲目",Icons.Outlined.Add,editable,"new-item"){open("item",UUID.randomUUID().toString(),id)}}}
        items(all.filter{archives || !it.isArchived},key={it.id}){item->
            val index=active.indexOfFirst{it.id==item.id}
            fun move(delta: Int){val ids=active.map{it.id}.toMutableList();val other=index+delta;val old=ids[index];ids[index]=ids[other];ids[other]=old
                vm.edit(actor,state.token,ManagementCommand.Reorder(id,ids))}
            ListItem(headlineContent={Text(d.pieces.firstOrNull{it.id==item.pieceId}?.title ?: "曲目失效",maxLines=2)},
                supportingContent={Text("排序 ${item.sortOrder} · "+if(item.isArchived)"归档" else if(item.isEnabled)"启用" else "停用")},
                trailingContent={Row{
                    AdminIcon("上移",Icons.Outlined.ArrowUpward,editable && index>0,"up-${item.id}"){move(-1)}
                    AdminIcon("下移",Icons.Outlined.ArrowDownward,editable && index>=0 && index<active.lastIndex,"down-${item.id}"){move(1)}
                    AdminIcon("编辑节目项",Icons.Outlined.Edit,editable,"edit-${item.id}"){open("item",item.id,id)}
                }})
            HorizontalDivider()
        }
    }
}
