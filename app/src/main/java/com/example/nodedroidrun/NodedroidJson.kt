package com.example.nodedroidrun

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CommandEntry(
    val label: String,
    val command: String
)

data class NodedroidJson(
    val commands: List<CommandEntry> = emptyList()
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (c in commands) {
            arr.put(JSONObject().apply {
                put("label", c.label)
                put("command", c.command)
            })
        }
        return JSONObject().apply {
            put("commands", arr)
        }
    }

    companion object {
        fun fromJson(json: String): NodedroidJson {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("commands") ?: JSONArray()
            val cmds = mutableListOf<CommandEntry>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                cmds.add(CommandEntry(
                    label = entry.optString("label", ""),
                    command = entry.optString("command", "")
                ))
            }
            return NodedroidJson(cmds)
        }

        fun load(projectDir: File): NodedroidJson {
            val file = File(projectDir, "nodedroid.json")
            if (!file.exists()) return NodedroidJson()
            return try {
                fromJson(file.readText())
            } catch (_: Exception) {
                NodedroidJson()
            }
        }

        fun save(projectDir: File, data: NodedroidJson) {
            val file = File(projectDir, "nodedroid.json")
            file.writeText(data.toJson().toString(2))
        }
    }
}
