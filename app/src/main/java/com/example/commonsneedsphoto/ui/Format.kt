package com.example.commonsneedsphoto.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Distance as shown in the place cards: metres close by, kilometres further away. */
internal fun formatDistance(meters: Double): String = if (meters < 1000.0) {
    "${meters.roundToInt()} m"
} else {
    String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
}
