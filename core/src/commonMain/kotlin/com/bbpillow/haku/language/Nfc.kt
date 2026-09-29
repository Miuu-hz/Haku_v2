package com.bbpillow.haku.language

/** Unicode NFC via the platform (no NFC in Kotlin common stdlib). */
internal expect fun nfc(text: String): String
