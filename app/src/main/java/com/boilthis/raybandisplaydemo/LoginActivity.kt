package com.boilthis.raybandisplaydemo

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var userRepository: UserRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        userRepository = UserRepository(this)
        
        // Ensure at least one default admin exists for the first login
        ensureAdminExists()

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val employeeId = findViewById<EditText>(R.id.editEmployeeId).text.toString()
            val pin = findViewById<EditText>(R.id.editPassword).text.toString()

            if (validateCredentials(employeeId, pin)) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("LOGGED_IN_USER_ID", employeeId)
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "INVALID ID OR PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureAdminExists() {
        val users = userRepository.loadUsers().toMutableList()
        if (users.isEmpty()) {
            // Default elite admin
            val admin = User("001", "Wegmans", "Admin", "1234", listOf("Produce", "Bakery", "Market Cafe"))
            users.add(admin)
            userRepository.saveUsers(users)
        }
    }

    private fun validateCredentials(id: String, pin: String): Boolean {
        val users = userRepository.loadUsers()
        return users.any { it.employeeId == id && it.password == pin }
    }
}
