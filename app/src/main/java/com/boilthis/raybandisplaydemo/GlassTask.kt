package com.boilthis.raybandisplaydemo

data class GlassTask(
    val id: Int,
    val title: String,
    val department: String = "General",
    var status: String = "PENDING",
    val priority: Int = 1,
    var voiceNote: String? = null,
    var capturedImagePath: String? = null,
    
    // New Audit Attribution & Timing
    var completedBy: String? = null,
    var completionTime: String? = null,
    var dueTime: String? = null,
    var durationEstimate: String? = "15m"
)
