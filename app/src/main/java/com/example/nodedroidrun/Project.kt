package com.example.nodedroidrun

import org.json.JSONObject

data class Project(
    val id: String,
    val name: String,
    val path: String,
    val cloneUrl: String,
    val source: String, // "github" or "url"
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("path", path)
        put("cloneUrl", cloneUrl)
        put("source", source)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Project = Project(
            id = json.getString("id"),
            name = json.getString("name"),
            path = json.getString("path"),
            cloneUrl = json.getString("cloneUrl"),
            source = json.optString("source", "url"),
            createdAt = json.optLong("createdAt", 0)
        )

        fun nameFromUrl(url: String): String {
            val path = url.trimEnd('/').removeSuffix(".git")
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
        }
    }
}
