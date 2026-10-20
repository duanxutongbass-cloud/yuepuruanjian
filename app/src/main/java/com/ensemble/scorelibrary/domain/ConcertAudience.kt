package com.ensemble.scorelibrary.domain

import com.ensemble.scorelibrary.core.database.entity.ConcertEntity
import com.ensemble.scorelibrary.core.database.entity.MemberEntity
import org.json.JSONArray

fun audienceIds(payload: String?): Set<String> {
    if (payload == null) return emptySet()
    require(payload.length <= 64 * 1024)
    val array = JSONArray(payload)
    require(array.length() <= 1000)
    return (0 until array.length()).map { index ->
        val id = array.get(index)
        require(id is String && id.isNotBlank() && id.length <= 200)
        id
    }.toSet()
}

fun concertAudienceAllows(concert: ConcertEntity, member: MemberEntity): Boolean {
    if (concert.audienceMembers == null && concert.audienceParts == null) return true
    return member.id in audienceIds(concert.audienceMembers) ||
        member.defaultPartDefinitionId?.let { it in audienceIds(concert.audienceParts) } == true
}
