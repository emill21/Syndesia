package com.syndesia.app

import android.content.Context
import org.json.JSONArray

data class Release(val version: String, val date: String, val title: String, val items: List<String>)

/** Release notes from assets/changelog.json — the same file the GitHub release workflow publishes. */
object Changelog {
    @Volatile
    private var cache: List<Release>? = null

    fun load(context: Context): List<Release> {
        cache?.let { return it }
        val list = try {
            val arr = JSONArray(context.assets.open("changelog.json").bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.getJSONArray("items")
                Release(
                    version = o.getString("version"),
                    date = o.optString("date"),
                    title = o.optString("title"),
                    items = (0 until items.length()).map { items.getString(it) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache = list
        return list
    }
}
