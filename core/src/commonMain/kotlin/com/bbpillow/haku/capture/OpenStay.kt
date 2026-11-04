package com.bbpillow.haku.capture

/** A stay with an arrival and no departure yet: where the user is now, as far as capture knows. It has no diary line until it ends. */
data class OpenStay(val startUtcMs: Long, val lat: Double, val lon: Double)
