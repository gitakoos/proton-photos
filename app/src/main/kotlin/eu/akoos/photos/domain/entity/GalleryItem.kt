package eu.akoos.photos.domain.entity

sealed interface GalleryItem {
    val captureTimeMs: Long

    data class CloudOnly(val cloud: CloudPhoto) : GalleryItem {
        override val captureTimeMs get() = cloud.captureTime * 1000L
    }

    data class LocalOnly(val local: LocalMediaItem) : GalleryItem {
        override val captureTimeMs get() = local.dateTaken
    }

    data class Synced(val cloud: CloudPhoto, val local: LocalMediaItem) : GalleryItem {
        /**
         * Cloud captureTime is authoritative — it's set at upload from the original local
         * DATE_TAKEN and survives MediaStore quirks (some emulator builds + some Android
         * versions silently drop a DATE_TAKEN write on a newly-inserted row, leaving the
         * downloaded file dated "now"). Falling back to local.dateTaken only when the
         * cloud carries no captureTime — rare for anything uploaded by our own app.
         */
        override val captureTimeMs get() =
            if (cloud.captureTime > 0) cloud.captureTime * 1000L else local.dateTaken
    }
}
