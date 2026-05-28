package com.boilthis.raybandisplaydemo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class UserRepository(context: Context) {
    private val file = File(context.filesDir, "users.json")

    fun saveUsers(users: List<User>) {
        val array = JSONArray()
        users.forEach { user ->
            val obj = JSONObject().apply {
                put("employeeId", user.employeeId)
                put("firstName", user.firstName)
                put("lastName", user.lastName)
                put("password", user.password)
                
                val deptArray = JSONArray()
                user.departments.forEach { deptArray.put(it) }
                put("departments", deptArray)
            }
            array.put(obj)
        }
        file.writeText(array.toString())
    }

    fun loadUsers(): List<User> {
        if (!file.exists()) return emptyList()
        val list = mutableListOf<User>()
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val deptArray = obj.getJSONArray("departments")
                val depts = mutableListOf<String>()
                for (j in 0 until deptArray.length()) {
                    depts.add(deptArray.getString(j))
                }
                
                list.add(User(
                    employeeId = obj.getString("employeeId"),
                    firstName = obj.getString("firstName"),
                    lastName = obj.getString("lastName"),
                    password = obj.getString("password"),
                    departments = depts
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
