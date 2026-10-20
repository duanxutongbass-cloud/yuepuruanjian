package com.ensemble.scorelibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ensemble.scorelibrary.domain.PieceSummary

internal data class LibraryCategory(val key:String,val label:String,val pieces:Set<String>)
internal fun libraryCategories(pieces:List<PieceSummary>):List<LibraryCategory> = pieces.groupBy { piece ->
    piece.categoryCode?.takeIf { it.matches(Regex("[0-9]{2}")) }?.let { "code:$it" }
        ?: piece.categoryName?.trim()?.takeIf { it.isNotEmpty() }?.let { "name:$it" } ?: "unclassified"
}.map { (key,rows) ->
    val name=rows.mapNotNull { it.categoryName?.trim()?.takeIf(String::isNotEmpty) }.distinct().singleOrNull()
    val code=key.removePrefix("code:").takeIf { key.startsWith("code:") }
    val label=if(code!=null) if(name!=null && (name==code || name.startsWith("$code ") || name.startsWith("$code·"))) name
        else listOfNotNull(code,name ?: "分类").joinToString(" · ") else name ?: "未分类"
    LibraryCategory(key,label,rows.map { it.id }.toSet())
}.sortedBy { it.key }

@Composable
internal fun LibraryCategoryFolders(categories:List<LibraryCategory>,selected:String?,count:Int,select:(String?)->Unit) {
    Column {
        Text("分类文件夹 · 自动分组",style=MaterialTheme.typography.labelMedium)
        LazyRow(Modifier.fillMaxWidth().testTag("library-category-folders"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item { CategoryFolder("全部曲目",count,selected==null,"category-all"){select(null)} }
            items(categories,key={it.key}) { category ->
                CategoryFolder(category.label,category.pieces.size,selected==category.key,"category-${category.key}"){select(category.key)}
            }
        }
    }
}

@Composable
private fun CategoryFolder(label:String,count:Int,selected:Boolean,tag:String,click:()->Unit) {
    OutlinedCard(onClick=click,modifier=Modifier.width(152.dp).testTag(tag),colors=CardDefaults.outlinedCardColors(
        containerColor=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(10.dp)) {
            Icon(Icons.Outlined.Folder,null,Modifier.size(22.dp))
            Text(label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
            Text("$count 首",style=MaterialTheme.typography.labelSmall)
        }
    }
}
