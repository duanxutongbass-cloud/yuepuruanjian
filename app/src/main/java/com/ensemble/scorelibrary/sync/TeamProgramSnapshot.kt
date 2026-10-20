package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.annotation.AnnotationRepository
import com.ensemble.scorelibrary.annotation.InkCodec
import com.ensemble.scorelibrary.core.database.entity.AnnotationConcertSnapshotEntity
import com.ensemble.scorelibrary.core.database.entity.PiecePartAssignmentEntity
import com.ensemble.scorelibrary.domain.LibrarySnapshot
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_TEAM_SNAPSHOT = 32 * 1024 * 1024
private const val PREFIX="team-freeze-v1|"
private fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
private fun uuid(value:String) { require(UUID.fromString(value).toString()==value) }

/** Only a validated, authorized projection can become a local immutable snapshot. */
data class TeamProgramSnapshot(val program:TeamProgram,val sourceHash:String,val payload:String,val metadataHash:String) {
    fun entity(data:LibrarySnapshot,member:String):AnnotationConcertSnapshotEntity {
        check(program.freezeId!=null)
        val projected=restrict(data,program.concert,payload,member)
        return AnnotationConcertSnapshotEntity(id=teamId("freeze/${program.freezeId}/$sourceHash"),concertId=program.concert,
            payload=payload,manifest="$PREFIX${program.freezeId}|$sourceHash|${sha256(payload)}|$metadataHash|${AnnotationRepository.manifest(projected,program.concert)}",
            createdBy=member)
    }
    companion object {
        fun parse(reply:JSONObject,program:TeamProgram,catalog:TeamCatalog):TeamProgramSnapshot {
            check(program.freezeId!=null)
            require(reply.get("protocolVersion") in setOf(1,2) && reply.getString("programId")==program.id && reply.getString("freezeId")==program.freezeId)
            val raw=reply.getString("payload")
            require(raw.toByteArray(Charsets.UTF_8).size<=MAX_TEAM_SNAPSHOT)
            val hash=reply.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}")) && sha256(raw)==it) }
            val body=JSONObject(raw)
            require(body.get("protocolVersion")==reply.get("protocolVersion") && body.getString("programId")==program.id && body.getString("freezeId")==program.freezeId)
            require(body.getLong("programRevision")>0 && body.getString("title")==program.title)
            val keys=body.getJSONArray("pieceKeys")
            require((0 until keys.length()).map { keys.getString(it) }==program.pieceKeys)
            val rows=body.getJSONArray("artifacts");require(rows.length()==program.frozenArtifacts.size)
            val expected=catalog.artifacts.filter { it.id in program.frozenArtifacts }.associateBy { it.id }
            require(expected.size==program.frozenArtifacts.size && expected.values.sumOf { it.pages.toLong() } in 1..10000)
            val seen=mutableSetOf<String>()
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val artifact=expected[row.getString("artifactId")] ?: error("冻结快照含未授权分谱")
                require(seen.add(artifact.id) && row.getString("hash")==artifact.hash && row.getLong("size")==artifact.size &&
                    row.getInt("pageCount")==artifact.pages && row.getString("pieceKey")==artifact.pieceKey && row.getString("title")==artifact.title &&
                    row.getString("partName")==artifact.partName && row.getString("scoreNumber")==artifact.number && row.getString("category")==artifact.category)
            }
            val ink=JSONObject()
            expected.values.forEach { ink.put("${it.version}:0","[]") }
            val pages=body.getJSONArray("pages");require(pages.length()<=10000)
            val unique=mutableSetOf<String>()
            for(i in 0 until pages.length()) {
                val row=pages.getJSONObject(i);val artifact=expected[row.getString("artifactId")] ?: error("冻结批注含未授权分谱")
                val page=row.getInt("pageIndex");require(page in 0 until artifact.pages && row.getLong("revision")>0)
                val key="${artifact.version}:$page";require(unique.add(key))
                ink.put(key,InkCodec.encode(InkCodec.decode(row.getJSONArray("marks").toString())))
            }
            val payload=ink.toString();require(payload.toByteArray(Charsets.UTF_8).size<=MAX_TEAM_SNAPSHOT)
            return TeamProgramSnapshot(program,hash,payload,metadataHash(program,catalog))
        }
        private fun metadataHash(program:TeamProgram,catalog:TeamCatalog):String = sha256((
            listOf(program.id,program.freezeId.orEmpty(),program.title,program.pieceKeys.size.toString())+program.pieceKeys+
                catalog.artifacts.filter { it.id in program.frozenArtifacts }.sortedBy { it.id }.flatMap {
                    listOf(it.id,it.hash,it.size.toString(),it.pages.toString(),it.title,it.partName,it.number,it.category,it.pieceKey)
                }).joinToString("\u0000"))
        fun isTeam(snapshot:AnnotationConcertSnapshotEntity)=snapshot.manifest.startsWith(PREFIX)
        private fun header(snapshot:AnnotationConcertSnapshotEntity):List<String> {
            require(snapshot.payload.toByteArray(Charsets.UTF_8).size<=MAX_TEAM_SNAPSHOT)
            val fields=snapshot.manifest.split('|',limit=6)
            require(fields.size==6 && fields[0]=="team-freeze-v1")
            uuid(fields[1]);require(fields[2].matches(Regex("[a-f0-9]{64}")) && fields[3]==sha256(snapshot.payload))
            require(snapshot.id==teamId("freeze/${fields[1]}/${fields[2]}"))
            require(fields[4].matches(Regex("[a-f0-9]{64}")))
            return fields
        }
        fun matches(snapshot:AnnotationConcertSnapshotEntity,program:TeamProgram,catalog:TeamCatalog,member:String):Boolean = runCatching {
            val fields=header(snapshot)
            require(snapshot.concertId==program.concert && snapshot.createdBy==member && fields[1]==program.freezeId)
            require(fields[4]==metadataHash(program,catalog))
            val expected=catalog.artifacts.filter { it.id in program.frozenArtifacts }.associateBy { it.version }
            val body=JSONObject(snapshot.payload)
            val versions=body.keys().asSequence().map { it.substringBefore(':') }.toSet()
            require(expected.isNotEmpty() && expected.size==program.frozenArtifacts.size && versions==expected.keys)
            body.keys().forEach { key ->
                require(key.matches(Regex("[a-f0-9-]{36}:[0-9]{1,5}")))
                require(key.substringAfter(':').toInt() in 0 until expected.getValue(key.substringBefore(':')).pages)
                InkCodec.decode(body.getString(key))
            }
            expected.keys.forEach { require(body.has("$it:0")) }
        }.isSuccess
        fun validate(snapshot:AnnotationConcertSnapshotEntity,data:LibrarySnapshot,member:String) {
            val fields=header(snapshot);require(snapshot.createdBy==member)
            require(fields[5]==AnnotationRepository.manifest(restrict(data,snapshot.concertId,snapshot.payload,member),snapshot.concertId)) {
                "本场冻结分谱或节目顺序已变更，请重新同步"
            }
        }
        fun project(data:LibrarySnapshot,snapshot:AnnotationConcertSnapshotEntity,member:String):LibrarySnapshot {
            validate(snapshot,data,member)
            return restrict(data,snapshot.concertId,snapshot.payload,member)
        }
        private fun restrict(data:LibrarySnapshot,concert:String,payload:String,member:String):LibrarySnapshot {
            val body=JSONObject(payload);require(body.length()<=10000)
            val versions=body.keys().asSequence().map { key ->
                require(key.matches(Regex("[a-f0-9-]{36}:[0-9]{1,5}")));key.substringBefore(':').also(::uuid)
            }.toSet()
            val pieces=data.items.filter { it.concertId==concert && !it.isArchived && it.isEnabled }.map { it.pieceId }.toSet()
            val versionIds=data.pdfs.filter { it.activeVersionId in versions && !it.isArchived }.map { it.id }.toSet()
            val frozenParts=data.bindings.filter { !it.isArchived && it.scorePdfId in versionIds }.map { it.piecePartId }.toSet()
            val parts=data.parts.filter { it.pieceId !in pieces || it.id in frozenParts }
            val partIds=parts.map { it.id }.toSet()
            val projected=data.copy(parts=parts,bindings=data.bindings.filter { it.piecePartId in partIds &&
                (data.parts.none { p -> p.id==it.piecePartId && p.pieceId in pieces } || it.scorePdfId in versionIds) })
            // Match the ordinary single-authorized-part default without granting another voice.
            if(data.members.singleOrNull { it.id==member }?.defaultPartDefinitionId!=null) return projected
            val extra=pieces.mapNotNull { piece ->
                if(data.assignments.any { a -> a.memberId==member && !a.isArchived && data.parts.any { it.id==a.piecePartId && it.pieceId==piece } }) null
                else projected.parts.filter { p -> p.pieceId==piece && !p.isArchived && data.extraPermissions.any { it.memberId==member && it.piecePartId==p.id && !it.isArchived } }
                    .singleOrNull()?.let { PiecePartAssignmentEntity(teamId("frozen-assignment/$concert/$member/$piece"),member,it.id) }
            }
            return projected.copy(assignments=projected.assignments+extra)
        }
    }
}
