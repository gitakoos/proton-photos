package me.proton.photos.domain.entity

enum class SyncStatus {
    LOCAL_ONLY,
    SYNCED,
    CLOUD_ONLY,
    LOCAL_MODIFIED,
    CONFLICT,
    /**
     * The user moved this photo into the Hidden vault on the device. The local file lives in
     * app-private storage (not visible to MediaStore); the cloud copy stays put in Drive but
     * the gallery renders a crossed-out eye overlay so the user can see at a glance which
     * cloud rows have a hidden local twin.
     *
     * Critical: every reconcile / cleanup path must SKIP rows with this status — they must
     * not be demoted to CLOUD_ONLY, must not be re-uploaded, must not be trashed, and the
     * Hidden screen relies on them surviving across periodic refreshes.
     */
    HIDDEN,
}
