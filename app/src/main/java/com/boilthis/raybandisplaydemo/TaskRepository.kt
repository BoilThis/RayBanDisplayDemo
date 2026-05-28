package com.boilthis.raybandisplaydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TaskRepository(context: Context) {
    private val file = File(context.filesDir, "tasks_v3.json")

    fun saveTasks(tasks: List<GlassTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            val obj = JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("department", task.department)
                put("status", task.status)
                put("priority", task.priority)
                put("voiceNote", task.voiceNote ?: JSONObject.NULL)
                put("capturedImagePath", task.capturedImagePath ?: JSONObject.NULL)
                
                // New Fields
                put("completedBy", task.completedBy ?: JSONObject.NULL)
                put("completionTime", task.completionTime ?: JSONObject.NULL)
                put("dueTime", task.dueTime ?: JSONObject.NULL)
                put("durationEstimate", task.durationEstimate ?: JSONObject.NULL)
            }
            array.put(obj)
        }
        file.writeText(array.toString())
    }

    fun loadTasks(): List<GlassTask> {
        if (!file.exists()) return emptyList()
        val list = mutableListOf<GlassTask>()
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    GlassTask(
                        id = obj.getInt("id"),
                        title = obj.getString("title"),
                        department = obj.optString("department", "General"),
                        status = obj.getString("status"),
                        priority = obj.getInt("priority"),
                        voiceNote = if (obj.isNull("voiceNote")) null else obj.getString("voiceNote"),
                        capturedImagePath = if (obj.isNull("capturedImagePath")) null else obj.getString("capturedImagePath"),
                        
                        // New Fields
                        completedBy = if (obj.isNull("completedBy")) null else obj.getString("completedBy"),
                        completionTime = if (obj.isNull("completionTime")) null else obj.getString("completionTime"),
                        dueTime = if (obj.isNull("dueTime")) null else obj.getString("dueTime"),
                        durationEstimate = if (obj.isNull("durationEstimate")) "15m" else obj.getString("durationEstimate")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
