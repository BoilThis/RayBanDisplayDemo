package com.boilthis.raybandisplaydemo

data class SubTask(
    val id: Int,
    val title: String,
    var isCompleted: Boolean = false,
    var voiceNote: String? = null,
    var capturedImagePath: String? = null
)

data class GlassTask(
    val id: Int,
    val title: String,
    val department: String = "General",
    var status: String = "PENDING",
    val priority: Int = 1,
    var voiceNote: String? = null,
    var capturedImagePath: String? = null,
    
    var completedBy: String? = null,
    var completionTime: String? = null,
    var dueTime: String? = null,
    var durationEstimate: String? = "15m",
    
    val subtasks: MutableList<SubTask> = mutableListOf()
) {
    fun getCompletionProgress(): String {
        if (subtasks.isEmpty()) return ""
        val done = subtasks.count { it.isCompleted }
        return "$done/${subtasks.size}"
    }

    fun isFullyComplete(): Boolean {
        return subtasks.isEmpty() || subtasks.all { it.isCompleted }
    }
}
