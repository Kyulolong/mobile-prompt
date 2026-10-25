package com.teaminpact.mobileprompt.model

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedScript(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val updatedAt: Long,
) {
    /** Display title: first non-empty line, truncated. Empty when the script is blank. */
    val titleLine: String
        get() {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            return if (line.length > 28) line.take(28) + "…" else line
        }
}

/**
 * Script library persisted as JSON in the app's files directory. Same shape as
 * the iOS library (id / text / updatedAt). Texts shared in from other apps are
 * added straight into the list by MainActivity — no inbox needed on Android.
 */
class ScriptStore(context: Context) {
    val scripts = mutableStateListOf<SavedScript>()
    private val file = File(context.filesDir, "scripts.json")

    init {
        load()
    }

    fun add(text: String = ""): SavedScript {
        val s = SavedScript(text = text, updatedAt = System.currentTimeMillis())
        scripts.add(0, s)
        save()
        return s
    }

    fun update(id: String, text: String) {
        val i = scripts.indexOfFirst { it.id == id }
        if (i < 0 || scripts[i].text == text) return
        scripts[i] = scripts[i].copy(text = text, updatedAt = System.currentTimeMillis())
        save()
    }

    fun delete(ids: Set<String>) {
        scripts.removeAll { it.id in ids }
        save()
    }

    fun script(id: String): SavedScript? = scripts.firstOrNull { it.id == id }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val list = ArrayList<SavedScript>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(SavedScript(o.getString("id"), o.getString("text"), o.getLong("updatedAt")))
            }
            scripts.clear()
            scripts.addAll(list)
        } catch (_: Exception) {
            // Corrupt file: start empty rather than crash. Nothing else to recover.
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (s in scripts) {
            arr.put(JSONObject().put("id", s.id).put("text", s.text).put("updatedAt", s.updatedAt))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
