package com.spencer.tagtunes

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers what each tag UID plays (a list of songs or a whole folder),
 * and where playback left off (track number + position).
 */
class TagStore(context: Context) {
    private val prefs = context.getSharedPreferences("tags", Context.MODE_PRIVATE)

    data class Track(val uri: Uri, val name: String)

    /** Either [folder] is set (play every audio file in it) or [files] lists the songs. */
    data class Entry(val uid: String, val name: String, val folder: Uri?, val files: List<Track>)

    fun get(uid: String): Entry? {
        prefs.getString("src:$uid", null)?.let { json ->
            val o = JSONObject(json)
            val name = o.optString("name", uid)
            if (o.has("folder")) return Entry(uid, name, Uri.parse(o.getString("folder")), emptyList())
            val arr = o.optJSONArray("files") ?: JSONArray()
            val files = (0 until arr.length()).map {
                val f = arr.getJSONObject(it)
                Track(Uri.parse(f.getString("u")), f.optString("n", "Track ${it + 1}"))
            }
            return Entry(uid, name, null, files)
        }
        // Pairings made by the first version of the app: one file per tag.
        val legacy = prefs.getString("uri:$uid", null) ?: return null
        val name = prefs.getString("name:$uid", uid) ?: uid
        return Entry(uid, name, null, listOf(Track(Uri.parse(legacy), name)))
    }

    fun putFiles(uid: String, name: String, files: List<Track>) {
        val arr = JSONArray()
        files.forEach { arr.put(JSONObject().put("u", it.uri.toString()).put("n", it.name)) }
        save(uid, JSONObject().put("name", name).put("files", arr))
    }

    fun putFolder(uid: String, name: String, folder: Uri) {
        save(uid, JSONObject().put("name", name).put("folder", folder.toString()))
    }

    fun rename(uid: String, newName: String) {
        val json = prefs.getString("src:$uid", null)
        if (json != null) {
            prefs.edit().putString("src:$uid", JSONObject(json).put("name", newName).toString()).apply()
        } else if (prefs.contains("uri:$uid")) {
            prefs.edit().putString("name:$uid", newName).apply()
        }
    }

    private fun save(uid: String, o: JSONObject) {
        prefs.edit()
            .remove("uri:$uid").remove("name:$uid")
            .putString("src:$uid", o.toString())
            .putInt("track:$uid", 0)
            .putInt("pos:$uid", 0)
            .apply()
    }

    fun remove(uid: String) {
        prefs.edit()
            .remove("src:$uid").remove("uri:$uid").remove("name:$uid")
            .remove("track:$uid").remove("pos:$uid")
            .apply()
    }

    fun all(): List<Entry> =
        prefs.all.keys
            .filter { it.startsWith("src:") || it.startsWith("uri:") }
            .map { it.substringAfter(':') }
            .distinct()
            .mapNotNull { get(it) }
            .sortedBy { it.name.lowercase() }

    fun track(uid: String): Int = prefs.getInt("track:$uid", 0)
    fun position(uid: String): Int = prefs.getInt("pos:$uid", 0)

    fun setProgress(uid: String, track: Int, ms: Int) {
        prefs.edit().putInt("track:$uid", track).putInt("pos:$uid", ms).apply()
    }

    /** UID of the tag whose playlist is loaded in the player, so progress is saved to the right tag. */
    var nowPlaying: String?
        get() = prefs.getString("nowPlaying", null)
        set(v) = prefs.edit().putString("nowPlaying", v).apply()

    /** Maximum media volume, as a percent of the phone's max. 100 = no limit. */
    var volumeCap: Int
        get() = prefs.getInt("volumeCap", 100)
        set(v) = prefs.edit().putInt("volumeCap", v).apply()
}
