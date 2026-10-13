package com.ensemble.scorelibrary.core.database.converter

import androidx.room.TypeConverter
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.core.model.ConcertStatus
import com.ensemble.scorelibrary.core.model.MemberRole

class EnumConverters {
    @TypeConverter
    fun memberRoleToString(value: MemberRole): String = value.name

    @TypeConverter
    fun stringToMemberRole(value: String): MemberRole = MemberRole.valueOf(value)

    @TypeConverter
    fun concertStatusToString(value: ConcertStatus): String = value.name

    @TypeConverter
    fun stringToConcertStatus(value: String): ConcertStatus = ConcertStatus.valueOf(value)

    @TypeConverter
    fun cacheStatusToString(value: CacheStatus): String = value.name

    @TypeConverter
    fun stringToCacheStatus(value: String): CacheStatus = CacheStatus.valueOf(value)
}
