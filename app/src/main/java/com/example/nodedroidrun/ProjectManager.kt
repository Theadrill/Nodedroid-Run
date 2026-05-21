package com.example.nodedroidrun

import android.content.Context
import org.json.JSONArray
import java.io.File

object ProjectManager {

    private const val PROJECTS_FILE = "projects.json"

    fun getProjectsDir(context: Context): File =
        File(context.filesDir, "projects").also { it.mkdirs() }

    fun getProjectDir(context: Context, project: Project): File =
        File(getProjectsDir(context), project.id)

    fun load(context: Context): List<Project> {
        val file = File(context.filesDir, PROJECTS_FILE)
        if (!file.exists()) return emptyList()
        val json = JSONArray(file.readText())
        val list = mutableListOf<Project>()
        for (i in 0 until json.length()) {
            list.add(Project.fromJson(json.getJSONObject(i)))
        }
        return list
    }

    fun save(context: Context, projects: List<Project>) {
        val json = JSONArray()
        projects.forEach { json.put(it.toJson()) }
        File(context.filesDir, PROJECTS_FILE).writeText(json.toString(2))
    }

    fun add(context: Context, project: Project) {
        val list = load(context).toMutableList()
        list.add(0, project)
        save(context, list)
    }

    fun remove(context: Context, project: Project) {
        getProjectDir(context, project).deleteRecursively()
        val list = load(context).toMutableList()
        list.removeAll { it.id == project.id }
        save(context, list)
    }
}
