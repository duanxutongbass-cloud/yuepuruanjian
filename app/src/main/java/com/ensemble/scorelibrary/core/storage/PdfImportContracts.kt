package com.ensemble.scorelibrary.core.storage

import android.net.Uri
import com.ensemble.scorelibrary.core.database.entity.ScorePdfVersionEntity

data class ManagedPdfImportRequest(
    val scorePdfId: String,
    val versionNumber: Int,
    val sourceUri: Uri,
    val createdByMemberId: String? = null
)

data class ManagedPdfFile(
    val managedFilePath: String,
    val originalUri: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val pageCount: Int
)

interface PdfImportStorage {
    suspend fun copyFromSafToManagedStorage(request: ManagedPdfImportRequest): ManagedPdfFile

    suspend fun createVersionEntity(
        request: ManagedPdfImportRequest,
        managedFile: ManagedPdfFile
    ): ScorePdfVersionEntity = ScorePdfVersionEntity(
        scorePdfId = request.scorePdfId,
        versionNumber = request.versionNumber,
        managedFilePath = managedFile.managedFilePath,
        originalUri = managedFile.originalUri,
        fileHash = managedFile.sha256,
        fileSizeBytes = managedFile.fileSizeBytes,
        pageCount = managedFile.pageCount,
        createdByMemberId = request.createdByMemberId
    )
}
