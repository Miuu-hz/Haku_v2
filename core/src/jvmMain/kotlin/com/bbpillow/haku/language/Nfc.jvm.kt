package com.bbpillow.haku.language

import java.text.Normalizer

internal actual fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
