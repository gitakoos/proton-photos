package eu.akoos.photos.widget

/** Three display modes for the home-screen photo widget. */
enum class WidgetMode {
    /** A random photo picked from the full local library each cycle. */
    ALL_PHOTOS,

    /** Cycles (in order) through a fixed set of user-selected photos. */
    SELECTED,

    /** Cycles (in order) through the photos in a chosen local album. */
    ALBUM,
}

/** Built-in interval options the user can choose in the config screen (minutes). */
enum class WidgetInterval(val minutes: Int, val label: String) {
    THIRTY_MIN(30, "30 min"),
    ONE_HOUR(60, "1 hour"),
    THREE_HOURS(180, "3 hours"),
    EIGHT_HOURS(480, "8 hours"),
    ONE_DAY(1440, "1 day"),
}
