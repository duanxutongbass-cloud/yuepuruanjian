package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.*
import com.ensemble.scorelibrary.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminIcon(label: String, icon: ImageVector, enabled: Boolean = true, tag: String = label, action: () -> Unit) {
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip={ PlainTooltip { Text(label) } },state=rememberTooltipState()) {
        IconButton(action,enabled=enabled,modifier=Modifier.testTag("manage-$tag")) { Icon(icon,label) }
    }
}

@Composable
fun AdminChoice(label: String, value: String?, choices: List<Pair<String?,String>>, enabled: Boolean = true,
    tag: String = label, change: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({expanded=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("choice-$tag")) {
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                Text(label,style=MaterialTheme.typography.labelSmall)
                Text(choices.firstOrNull{it.first==value}?.second ?: "请选择",maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ArrowDropDown,null)
        }
        DropdownMenu(expanded,{expanded=false}) { choices.forEach { (id,name) ->
            DropdownMenuItem(text={Text(name,maxLines=2)},onClick={expanded=false;change(id)})
        } }
    }
}

data class EditDescriptor(val kind: String, val id: String, val parent: String = "")

@Composable
fun ManagementEditor(descriptor: EditDescriptor, catalog: ManagementCatalog, state: ManagementState,
    originalToken: String, close: () -> Unit, submit: (ManagementCommand) -> Unit) {
    val d=catalog.data; val id=descriptor.id; val kind=descriptor.kind
    val piece=d.pieces.firstOrNull{it.id==id}; val movement=d.movements.firstOrNull{it.id==id}
    val definition=d.definitions.firstOrNull{it.id==id}; val part=d.parts.firstOrNull{it.id==id}
    val binding=d.bindings.firstOrNull{it.id==id}; val member=d.members.firstOrNull{it.id==id}
    val concert=d.concerts.firstOrNull{it.id==id}; val item=d.items.firstOrNull{it.id==id}; val pdf=d.pdfs.firstOrNull{it.id==id}
    var name by rememberSaveable(id) { mutableStateOf(when(kind) {
        "piece"->piece?.title; "movement"->movement?.title; "definition"->definition?.name; "part"->part?.displayName
        "member"->member?.displayName; "concert"->concert?.title; "pdf"->pdf?.title; else->null }.orEmpty()) }
    var extra by rememberSaveable(id) { mutableStateOf(if(kind=="definition") definition?.family.orEmpty() else piece?.composer.orEmpty()) }
    var arranger by rememberSaveable(id) { mutableStateOf(piece?.arranger.orEmpty()) }
    var notes by rememberSaveable(id) { mutableStateOf(piece?.notes.orEmpty()) }
    var archived by rememberSaveable(id) { mutableStateOf(when(kind) {
        "piece"->piece?.isArchived; "movement"->movement?.isArchived; "definition"->definition?.isArchived
        "part"->part?.isArchived; "binding"->binding?.isArchived; "member"->member?.isArchived; "concert"->concert?.isArchived
        "item"->item?.isArchived; "pdf"->pdf?.isArchived; else->false } ?: false) }
    var chosenDefinition by rememberSaveable(id) { mutableStateOf(if(kind=="member") member?.defaultPartDefinitionId else part?.partDefinitionId) }
    var chosenPdf by rememberSaveable(id) { mutableStateOf(binding?.scorePdfId) }
    var chosenMovement by rememberSaveable(id) { mutableStateOf(binding?.movementId) }
    var chosenPiece by rememberSaveable(id) { mutableStateOf(item?.pieceId) }
    var start by rememberSaveable(id) { mutableStateOf((binding?.startPage ?: 1).toString()) }
    var end by rememberSaveable(id) { mutableStateOf(binding?.endPage?.toString().orEmpty()) }
    var order by rememberSaveable(id) { mutableStateOf((when(kind) {
        "movement"->movement?.sortOrder ?: ((d.movements.filter{it.pieceId==descriptor.parent}.maxOfOrNull{it.sortOrder}?.toLong() ?: -1)+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        "item"->item?.sortOrder ?: ((d.items.filter{it.concertId==descriptor.parent}.maxOfOrNull{it.sortOrder}?.toLong() ?: -1)+1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else->definition?.sortOrder ?: 0 }).toString()) }
    var role by rememberSaveable(id) { mutableStateOf((member?.role ?: MemberRole.MEMBER).name) }
    var status by rememberSaveable(id) { mutableStateOf((concert?.status ?: ConcertStatus.DRAFT).name) }
    var enabled by rememberSaveable(id) { mutableStateOf(item?.isEnabled ?: true) }
    val fresh=originalToken==state.token
    val number=order.toIntOrNull()
    val valid=when(kind) {
        "binding"->chosenPdf!=null && (start.toIntOrNull() ?: 0)>0 && (end.toIntOrNull() ?: 0)>=(start.toIntOrNull() ?: 1)
        "part"->chosenDefinition!=null
        "item"->chosenPiece!=null && number!=null && number>=0
        "movement","definition"->name.isNotBlank() && number!=null && number>=0
        else->name.isNotBlank()
    }
    val heading=when(kind){"piece"->"曲目";"movement"->"乐章";"definition"->"标准声部";"part"->"曲目声部"
        "binding"->"谱段";"member"->"成员";"concert"->"节目单";"item"->"节目项";else->"PDF 信息"}
    fun command(): ManagementCommand = when(kind) {
        "piece"->ManagementCommand.Piece((piece ?: PieceEntity(id=id,title=name)).copy(title=name,composer=extra,arranger=arranger,notes=notes,isArchived=archived))
        "movement"->ManagementCommand.Movement((movement ?: MovementEntity(id=id,pieceId=descriptor.parent,title=name,sortOrder=number!!)).copy(title=name,sortOrder=number!!,isArchived=archived))
        "definition"->ManagementCommand.Definition((definition ?: PartDefinitionEntity(id=id,name=name)).copy(name=name,family=extra,sortOrder=number!!,isArchived=archived))
        "part"->ManagementCommand.Part((part ?: PiecePartEntity(id=id,pieceId=descriptor.parent,partDefinitionId=chosenDefinition!!)).copy(displayName=name,isArchived=archived))
        "binding"->ManagementCommand.Binding((binding ?: PartScoreBindingEntity(id=id,piecePartId=descriptor.parent,scorePdfId=chosenPdf!!,startPage=1,endPage=1))
            .copy(movementId=chosenMovement,scorePdfId=chosenPdf!!,startPage=start.toInt(),endPage=end.toInt(),isArchived=archived))
        "member"->ManagementCommand.Member((member ?: MemberEntity(id=id,displayName=name)).copy(displayName=name,role=MemberRole.valueOf(role),defaultPartDefinitionId=chosenDefinition,isArchived=archived))
        "concert"->ManagementCommand.Concert((concert ?: ConcertEntity(id=id,title=name)).copy(title=name,status=ConcertStatus.valueOf(status),isArchived=archived))
        "item"->ManagementCommand.Item((item ?: ConcertItemEntity(id=id,concertId=descriptor.parent,pieceId=chosenPiece!!,sortOrder=number!!)).copy(sortOrder=number!!,isEnabled=enabled,isArchived=archived))
        else->ManagementCommand.Pdf(requireNotNull(pdf).copy(title=name,isArchived=archived))
    }
    AlertDialog(onDismissRequest={if(!state.busy)close()},title={Text(heading)},
        text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(kind !in listOf("binding","item")) OutlinedTextField(name,{name=it},label={Text(if(kind=="part")"声部别名（可空）" else "名称")},
                singleLine=true,enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("editor-name"))
            if(kind=="piece" || kind=="definition") OutlinedTextField(extra,{extra=it},label={Text(if(kind=="piece")"作曲" else "乐器组（可空）")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)
            if(kind=="piece") {
                OutlinedTextField(arranger,{arranger=it},label={Text("编曲（可空）")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.busy)
                OutlinedTextField(notes,{notes=it},label={Text("备注（可空）")},modifier=Modifier.fillMaxWidth(),enabled=!state.busy)
            }
            if(kind=="part" || kind=="member") AdminChoice(if(kind=="member")"默认声部" else "标准声部",chosenDefinition,
                (if(kind=="member")listOf(null to "无默认声部") else emptyList())+d.definitions.filter{!it.isArchived || it.id==chosenDefinition}.map{it.id to (it.name+if(it.isArchived)"（归档）" else "")},
                enabled=!state.busy && (kind=="member" || part==null),tag="definition",change={chosenDefinition=it})
            if(kind=="member") AdminChoice("角色",role,MemberRole.entries.map{it.name to if(it==MemberRole.ADMIN)"管理员" else "成员"},!state.busy,change={role=it!!})
            if(kind=="concert") AdminChoice("状态",status,ConcertStatus.entries.map{it.name to when(it){ConcertStatus.DRAFT->"草稿";ConcertStatus.READY->"就绪";ConcertStatus.PUBLISHED->"已发布";ConcertStatus.PERFORMING->"演出中";ConcertStatus.FINISHED->"已结束"}},!state.busy,change={status=it!!})
            if(kind=="item") {
                AdminChoice("曲目",chosenPiece,d.pieces.filter{!it.isArchived || it.id==chosenPiece}.map{it.id to it.title},!state.busy && item==null,change={chosenPiece=it})
                Row(verticalAlignment=Alignment.CenterVertically){Switch(enabled,{enabled=it},enabled=!state.busy);Text("启用")}
            }
            if(kind=="binding") {
                val p=d.parts.firstOrNull{it.id==(binding?.piecePartId ?: descriptor.parent)}
                AdminChoice("乐章",chosenMovement,listOf(null to "整曲")+d.movements.filter{it.pieceId==p?.pieceId && (!it.isArchived || it.id==chosenMovement)}.sortedBy{it.sortOrder}.map{it.id to it.title},!state.busy,change={chosenMovement=it})
                AdminChoice("PDF",chosenPdf,d.pdfs.filter{!it.isArchived || it.id==chosenPdf}.map{pdfItem->
                    val v=d.versions.firstOrNull{it.id==pdfItem.activeVersionId};pdfItem.id to "${pdfItem.title} · v${v?.versionNumber ?: "?"} · ${v?.pageCount ?: 0} 页"
                },!state.busy,tag="pdf",change={chosenPdf=it})
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start,{start=it},label={Text("起始页")},singleLine=true,modifier=Modifier.weight(1f).testTag("editor-start"),enabled=!state.busy)
                    OutlinedTextField(end,{end=it},label={Text("结束页")},singleLine=true,modifier=Modifier.weight(1f).testTag("editor-end"),enabled=!state.busy)
                }
            }
            if(kind in listOf("movement","definition","item")) OutlinedTextField(order,{order=it},label={Text("排序")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("editor-order"),enabled=!state.busy)
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(archived,{archived=it},enabled=!state.busy,modifier=Modifier.testTag("editor-archived"));Text("归档") }
            if(!fresh) Text("目录已更新，请关闭后重新编辑",color=MaterialTheme.colorScheme.error)
            state.message?.let { Text(it,modifier=Modifier.testTag("editor-message"),color=MaterialTheme.colorScheme.error) }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        confirmButton={AdminIcon("保存",Icons.Outlined.Save,!state.busy && fresh && valid,"save"){submit(command())}},
        dismissButton={AdminIcon("取消",Icons.Outlined.Close,!state.busy,"cancel",close)})
}
