package com.ensemble.scorelibrary.core.model

enum class MemberRole {
    MEMBER,
    ADMIN
}

enum class ConcertStatus {
    DRAFT,
    READY,
    PUBLISHED,
    PERFORMING,
    FINISHED
}

enum class CacheStatus {
    NOT_CACHED,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    CACHED,
    OUTDATED,
    FAILED
}

enum class PedalAction {
    NEXT_PAGE,
    PREVIOUS_PAGE,
    TOGGLE_PLAY_MODE,
    NO_OP
}
