package eu.akoos.photos.domain.entity

data class LocalAlbum(
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
    val items: List<LocalMediaItem>,
    val backedUpCount: Int = 0,
    /** True when the user created this album from inside the app (vs auto-discovered bucket). */
    val isManual: Boolean = false,
    /**
     * True when this album has NO real MediaStore bucket files — every member is a virtual
     * reference recorded in [eu.akoos.photos.data.preferences.SettingsKeys.LOCAL_ALBUM_VIRTUAL_MEMBERSHIP].
     * Pure-virtual albums are safe to rename and delete (DataStore-only mutations). Bucket-
     * derived albums (Camera, Screenshots, etc.) are device folders we won't touch.
     */
    val isVirtualOnly: Boolean = false,
) {
    val isFullyBackedUp: Boolean get() = itemCount > 0 && backedUpCount >= itemCount
    val hasAnyBackedUp: Boolean get() = backedUpCount > 0
}
