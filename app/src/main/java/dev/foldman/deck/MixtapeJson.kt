package dev.foldman.deck

import org.json.JSONArray
import org.json.JSONObject

/** The file the mixtapes are kept in. */
object MixtapeJson {
    const val VERSION = 1

    fun encode(list: List<Mixtape>): String {
        val array = JSONArray()
        for (m in list) {
            array.put(JSONObject().put("id", m.id).put("name", m.name).put("keys", JSONArray(m.keys)))
        }
        return JSONObject().put("version", VERSION).put("mixtapes", array).toString()
    }

    /** The mixtapes in [text], or null when it isn't a file this version understands. */
    fun decode(text: String): List<Mixtape>? = runCatching {
        val root = JSONObject(text)
        if (root.getInt("version") != VERSION) return@runCatching null
        val array = root.getJSONArray("mixtapes")
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val keys = o.getJSONArray("keys")
            Mixtape(o.getString("id"), o.getString("name"), List(keys.length()) { k -> keys.getString(k) })
        }
    }.getOrNull()
}
