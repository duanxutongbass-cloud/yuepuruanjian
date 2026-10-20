package com.ensemble.scorelibrary.ui

import com.ensemble.scorelibrary.domain.ScoreNumber

import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import com.ensemble.scorelibrary.domain.LibrarySnapshot
import java.text.Normalizer

data class ManagedPieceRow(val piece: PieceEntity, val parts: List<String>, val definitionIds: Set<String>,
    val withPdf: Boolean, val searchText: String) {
    val heading get() = listOfNotNull(piece.organizerSequence?.let { "[$it]" }, piece.title).joinToString(" ")
    val description get() = listOf(piece.categoryName ?: "未分类", "${parts.size} 个声部", parts.joinToString("、")).filter { it.isNotEmpty() }.joinToString(" · ")
}
private fun searchable(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
fun managedPieceRows(data: LibrarySnapshot, includeArchived: Boolean = false): List<ManagedPieceRow> {
    val definitions = data.definitions.filterNot { it.isArchived }.associateBy { it.id }
    val pdfs = data.pdfs.filterNot { it.isArchived }.associateBy { it.id }
    return data.pieces.filter { includeArchived || !it.isArchived }.map { piece ->
        val parts = data.parts.filter { it.pieceId == piece.id && !it.isArchived && it.partDefinitionId in definitions }
        val names = parts.map { it.displayName ?: definitions.getValue(it.partDefinitionId).name }.distinct()
        val withPdf = data.bindings.any { binding -> binding.piecePartId in parts.map { it.id } && !binding.isArchived &&
            pdfs[binding.scorePdfId]?.activeVersionId?.let { version -> data.versions.any { it.id == version && it.scorePdfId == binding.scorePdfId } } == true }
        ManagedPieceRow(piece, names, parts.map { it.partDefinitionId }.toSet(), withPdf,
            searchable((listOfNotNull(piece.title, piece.organizerSequence, piece.categoryCode, piece.categoryName, piece.composer,
                piece.arranger, piece.notes, piece.categoryCode?.let { it + piece.organizerSequence.orEmpty() }) +
                names + parts.mapNotNull { definitions[it.partDefinitionId]?.family }).joinToString(" ")))
    }.sortedWith(compareBy<ManagedPieceRow> { it.piece.organizerSequence==null }
        .thenBy { it.piece.organizerSequence?.let(ScoreNumber::sequence) ?: Long.MAX_VALUE }.thenBy { it.piece.title }.thenBy { it.piece.id })
}
fun filterManagedPieces(rows: List<ManagedPieceRow>, query: String, category: String? = null,
    definition: String? = null, pdfState: String? = null): List<ManagedPieceRow> {
    val words = searchable(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return rows.filter { row -> words.all { it in row.searchText } &&
        (category == null || (row.piece.categoryName ?: "未分类") == category) &&
        (definition == null || definition in row.definitionIds) &&
        (pdfState == null || row.withPdf == (pdfState == "ready")) }
}
