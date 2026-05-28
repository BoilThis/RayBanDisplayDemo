package com.boilthis.raybandisplaydemo

data class User(
    val employeeId: String,
    val firstName: String,
    val lastName: String,
    val password: String,
    val departments: List<String> = emptyList()
)
