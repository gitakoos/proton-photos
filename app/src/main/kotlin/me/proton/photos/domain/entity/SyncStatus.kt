package me.proton.photos.domain.entity

enum class SyncStatus {
    LOCAL_ONLY,
    SYNCED,
    CLOUD_ONLY,
    LOCAL_MODIFIED,
    CONFLICT,
}
