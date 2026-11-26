package dev.cambraia.openherdr.data

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf

/**
 * Unsent prompts, one per pane, kept until they are sent. They survive leaving the screen, a
 * dropped connection and the app being killed, so a dictated prompt is never typed twice.
 */
class Drafts(private val prefs: SharedPreferences) {
    private val texts = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (key, value) -> if (value is String) put(key, value) }
    }

    operator fun get(key: String): String = texts[key].orEmpty()

    operator fun set(key: String, text: String) {
        if (text.isEmpty()) texts.remove(key) else texts[key] = text
        prefs.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text) }.apply()
    }

    /** Adds dictated [text] after what is already there. */
    fun append(key: String, text: String) {
        set(key, join(get(key), text))
    }

    companion object {
        fun join(draft: String, text: String): String =
            listOf(draft.trimEnd(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
