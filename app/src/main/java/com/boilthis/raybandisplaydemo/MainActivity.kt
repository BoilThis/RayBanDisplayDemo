package com.boilthis.raybandisplaydemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.widget.GridLayout
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.core.types.DeviceIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Wegmans Light Palette
    private val WEGMANS_GREEN = "#006938"
    private val WEGMANS_OFF_WHITE = "#F9F8F2"
    private val TEXT_PRIMARY = "#1A1A1A"
    private val TEXT_SECONDARY = "#666666"

    private val DEPARTMENTS_ALL = arrayOf(
        "ALL DEPTS", "Produce", "Market Cafe", "Dairy", "Bakery", 
        "Meat", "Seafood", "Floral", "Nature's Marketplace", "Front-end", "Logistics"
    )

    private var devicesText: TextView? = null
    private var taskListContainer: LinearLayout? = null
    private var userListContainer: LinearLayout? = null
    private var bottomNavigation: BottomNavigationView? = null
    private var tasksSection: View? = null
    private var glassSection: View? = null
    private var reportSection: View? = null
    private var userSection: View? = null
    private var addTaskSection: View? = null
    private var progressText: TextView? = null
    private var welcomeUserText: TextView? = null
    private var deptSpinner: Spinner? = null

    private lateinit var glassesManager: GlassesManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var userRepository: UserRepository
    
    private var currentUser: User? = null
    private var availableDepartments = mutableListOf<String>()
    private var currentDeviceId: DeviceIdentifier? = null
    private var taskList = mutableListOf<GlassTask>()
    private var userList = mutableListOf<User>()
    private var currentTaskIndex = 0
    private var currentSubTaskIndex = 0
    private var selectedDeptFilter = "ALL DEPTS"
    
    private var selectedUserDepartments = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesText = findViewById(R.id.devicesText)
        taskListContainer = findViewById(R.id.taskListContainer)
        userListContainer = findViewById(R.id.userListContainer)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        tasksSection = findViewById(R.id.tasksSection)
        glassSection = findViewById(R.id.glassSection)
        reportSection = findViewById(R.id.reportSection)
        userSection = findViewById(R.id.userSection)
        addTaskSection = findViewById(R.id.addTaskSection)
        progressText = findViewById(R.id.progressStats)
        welcomeUserText = findViewById(R.id.welcomeUserText)
        deptSpinner = findViewById(R.id.deptSpinner)

        devicesText?.text = "SYSTEM INITIALIZING..."
        
        taskRepository = TaskRepository(this)
        userRepository = UserRepository(this)
        glassesManager = GlassesManager(this, this)
        
        val loggedInId = intent.getStringExtra("LOGGED_IN_USER_ID")
        currentUser = userRepository.loadUsers().find { it.employeeId == loggedInId }
        
        currentUser?.let { user ->
            welcomeUserText?.text = "Welcome, ${user.firstName} ${user.lastName}"
        }

        setupAvailableDepartments()
        
        voiceCommandManager = VoiceCommandManager(
            context = this,
            onCommandDetected = { command -> 
                lifecycleScope.launch(Dispatchers.Default) {
                    handleVoiceCommand(command) 
                }
            },
            onListeningStateChanged = { isListening ->
                runOnUiThread {
                    if (isListening) {
                        Toast.makeText(this, "Wegmans Voice: Listening...", Toast.LENGTH_SHORT).show()
                        glassesManager.showListeningState()
                    } else {
                        pushToGlasses()
                    }
                }
            }
        )

        glassesManager.onStatusChanged = { status ->
            runOnUiThread {
                devicesText?.text = status
                Log.d("MainActivity", "SDK Status: $status")
            }
        }
        
        // HUD interaction callbacks
        glassesManager.onCaptureClick = { 
            Log.d("MainActivity", "HUD [PHOTO] TRIGGERED")
            runOnUiThread { 
                Toast.makeText(this, "Capturing Evidence...", Toast.LENGTH_SHORT).show()
                startCapture() 
            } 
        }
        glassesManager.onReviewClick = { 
            Log.d("MainActivity", "HUD [REVIEW] TRIGGERED")
            runOnUiThread { 
                Toast.makeText(this, "Activating Voice...", Toast.LENGTH_SHORT).show()
                voiceCommandManager.startListening(dictation = true) 
            } 
        }
        glassesManager.onCompleteClick = { runOnUiThread { markComplete() } }
        glassesManager.onPrevClick = { runOnUiThread { movePrevious() } }
        glassesManager.onNextClick = { runOnUiThread { moveNext() } }
        
        glassesManager.onSubPrevClick = { runOnUiThread { moveSubPrevious() } }
        glassesManager.onSubNextClick = { runOnUiThread { moveSubNext() } }

        setupDeptSpinner()
        setupNavigation()
        setupButtons()
        setupUserAdmin()
        setupAddTaskUI()
        initGroceryTasks()
        checkPermissions()

        bottomNavigation?.selectedItemId = R.id.nav_tasks
    }

    private fun setupAvailableDepartments() {
        availableDepartments.clear()
        availableDepartments.add("ALL DEPTS")
        
        currentUser?.let { user ->
            if (user.departments.isNotEmpty()) {
                availableDepartments.addAll(user.departments)
            } else {
                availableDepartments.addAll(DEPARTMENTS_ALL.filter { it != "ALL DEPTS" })
            }
        } ?: run {
            availableDepartments.addAll(DEPARTMENTS_ALL.filter { it != "ALL DEPTS" })
        }
    }

    private fun setupUserAdmin() {
        userList = userRepository.loadUsers().toMutableList()
        refreshUserListView()

        findViewById<Button>(R.id.btnSelectDepts).setOnClickListener {
            val depts = DEPARTMENTS_ALL.filter { it != "ALL DEPTS" }.toTypedArray()
            val checked = BooleanArray(depts.size) { i -> depts[i] in selectedUserDepartments }
            
            AlertDialog.Builder(this)
                .setTitle("Select Department Access")
                .setMultiChoiceItems(depts, checked) { _, which, isChecked ->
                    if (isChecked) selectedUserDepartments.add(depts[which])
                    else selectedUserDepartments.remove(depts[which])
                }
                .setPositiveButton("Done", null)
                .show()
        }

        findViewById<Button>(R.id.btnSaveUser).setOnClickListener {
            val fName = findViewById<EditText>(R.id.editFirstName).text.toString()
            val lName = findViewById<EditText>(R.id.editLastName).text.toString()
            val eId = findViewById<EditText>(R.id.editEmployeeId).text.toString()
            val pass = findViewById<EditText>(R.id.editPassword).text.toString()

            if (fName.isBlank() || eId.isBlank()) {
                Toast.makeText(this, "First Name and ID required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val newUser = User(eId, fName, lName, pass, selectedUserDepartments.toList())
            userList.add(newUser)
            userRepository.saveUsers(userList)
            
            findViewById<EditText>(R.id.editFirstName).text.clear()
            findViewById<EditText>(R.id.editLastName).text.clear()
            findViewById<EditText>(R.id.editEmployeeId).text.clear()
            findViewById<EditText>(R.id.editPassword).text.clear()
            selectedUserDepartments.clear()
            
            refreshUserListView()
            Toast.makeText(this, "User $fName Added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUserListView() {
        userListContainer?.removeAllViews()
        userList.forEach { user ->
            val userCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 20) }
                radius = 12f
                strokeWidth = 1
                setStrokeColor(ColorStateList.valueOf("#EEEEEE".toColorInt()))
                setCardBackgroundColor(Color.WHITE)
                cardElevation = 2f
                
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                    
                    val name = TextView(context).apply {
                        text = "${user.firstName} ${user.lastName} (ID: ${user.employeeId})"
                        setTextColor(TEXT_PRIMARY.toColorInt())
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                    }
                    addView(name)
                    
                    val depts = TextView(context).apply {
                        text = "Authorized Access: ${user.departments.joinToString(", ")}"
                        setTextColor(WEGMANS_GREEN.toColorInt())
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(0, 8, 0, 0)
                    }
                    addView(depts)
                }
                addView(layout)
            }
            userListContainer?.addView(userCard)
        }
    }

    private fun setupAddTaskUI() {
        val spinner = findViewById<Spinner>(R.id.addTaskDeptSpinner)
        val depts = DEPARTMENTS_ALL.filter { it != "ALL DEPTS" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_elite, depts)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_elite)
        spinner.adapter = adapter

        findViewById<Button>(R.id.btnAddSubtaskRow).setOnClickListener {
            addSubtaskInputRow()
        }

        findViewById<Button>(R.id.btnFinalizeTask).setOnClickListener {
            finalizeNewTask()
        }
    }

    private fun addSubtaskInputRow() {
        val container = findViewById<LinearLayout>(R.id.subtaskInputsContainer)
        
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }

        val input = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 120, 1f)
            hint = "Subtask detail..."
            textSize = 14f
            setPadding(16, 0, 16, 0)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 8f
                setStroke(1, "#DDDDDD".toColorInt())
            }
        }
        row.addView(input)
        
        val removeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.RED)
            setPadding(24, 0, 24, 0)
            textSize = 18f
            setOnClickListener { container.removeView(row) }
        }
        row.addView(removeBtn)
        
        container.addView(row)
    }

    private fun finalizeNewTask() {
        val title = findViewById<EditText>(R.id.editTaskTitle).text.toString()
        val dept = findViewById<Spinner>(R.id.addTaskDeptSpinner).selectedItem.toString()
        val dueTime = findViewById<EditText>(R.id.editDueTime).text.toString()
        val duration = findViewById<EditText>(R.id.editDuration).text.toString()
        val subtaskContainer = findViewById<LinearLayout>(R.id.subtaskInputsContainer)
        
        if (title.isBlank()) {
            Toast.makeText(this, "Title Required", Toast.LENGTH_SHORT).show()
            return
        }

        val newTask = GlassTask(
            id = taskList.size + 1,
            title = title,
            department = dept,
            dueTime = if (dueTime.isNotBlank()) dueTime else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + 7200000)),
            durationEstimate = if (duration.isNotBlank()) duration else "15m"
        )

        for (i in 0 until subtaskContainer.childCount) {
            val row = subtaskContainer.getChildAt(i) as LinearLayout
            val input = row.getChildAt(0) as EditText
            val subTitle = input.text.toString()
            if (subTitle.isNotBlank()) {
                newTask.subtasks.add(SubTask(i, subTitle))
            }
        }

        taskList.add(newTask)
        taskRepository.saveTasks(taskList)
        
        findViewById<EditText>(R.id.editTaskTitle).text.clear()
        findViewById<EditText>(R.id.editDueTime).text.clear()
        findViewById<EditText>(R.id.editDuration).text.clear()
        subtaskContainer.removeAllViews()
        Toast.makeText(this, "Task Added Successfully", Toast.LENGTH_SHORT).show()
        
        refreshTaskListView()
        bottomNavigation?.selectedItemId = R.id.nav_tasks
    }

    private fun setupDeptSpinner() {
        val adapter = ArrayAdapter(this, R.layout.spinner_item_elite, availableDepartments)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_elite)
        deptSpinner?.adapter = adapter
        deptSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDeptFilter = availableDepartments[position]
                refreshTaskListView()
                
                val filtered = getFilteredTasks()
                if (filtered.isNotEmpty() && taskList[currentTaskIndex] !in filtered) {
                    currentTaskIndex = taskList.indexOf(filtered.first())
                    pushToGlasses()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissions.add(Manifest.permission.CAMERA)
        permissions.add(Manifest.permission.RECORD_AUDIO)
        
        val toRequest = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            requestPermissions(toRequest.toTypedArray(), 101)
        } else {
            observeDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) observeDevices()
    }

    private fun initGroceryTasks() {
        taskList = taskRepository.loadTasks().toMutableList()
        if (taskList.isEmpty()) {
            val deptData = mapOf(
                "Produce" to listOf(
                    "Inspect Leafy Greens" to listOf("Check humidity", "Remove wilting items", "Verify misting cycle"),
                    "Organic Apple Stock" to listOf("Verify PLU stickers", "Rotate stock", "Check for bruising"),
                    "Citrus Display Sweep" to listOf("Check ripeness", "Clean display bin", "Update pricing tags"),
                    "Berry Case Audit" to listOf("Check for mold", "Verify refrigeration temp", "Facing"),
                    "Exotic Fruit Inspection" to listOf("Check signage accuracy", "Verify ripeness", "Stock levels")
                ),
                "Market Cafe" to listOf(
                    "Hot Bar Sanitization" to listOf("Sanitize serving tongs", "Check sneeze guard", "Wipe surfaces"),
                    "Soup Station Audit" to listOf("Log temps", "Refill liners", "Check lid stock"),
                    "Self-Serve Beverage Bar" to listOf("Clean nozzles", "Refill cups/lids", "Check drain lines"),
                    "Pizza Counter Prep" to listOf("Verify dough stock", "Check topping freshness", "Sanitize boards"),
                    "Sushi Case Log" to listOf("Log refrigeration", "Verify sell-by dates", "Check garnish")
                ),
                "Bakery" to listOf(
                    "Artisan Bread Audit" to listOf("Check crust color", "Verify bag labels", "Stock levels"),
                    "Pastry Case Facing" to listOf("Organize eclairs", "Check safety seals", "Wipe glass"),
                    "Custom Cake Order Check" to listOf("Verify pick-up times", "Check decorator notes", "Stock board"),
                    "Muffin Station Refresh" to listOf("Rotate flavor bins", "Verify unit pricing", "Clean tray"),
                    "Bagel Bin Inspection" to listOf("Check for freshness", "Clean crumbs", "Refill tongs")
                ),
                "Dairy" to listOf(
                    "Milk Cooler Sweep" to listOf("Remove near-expiry", "Wipe shelving", "Check gallon orientation"),
                    "Yogurt Wall Audit" to listOf("Facing by flavor", "Verify pricing", "Check safety seals"),
                    "Cheese Shop Check" to listOf("Check moisture levels", "Verify origin labels", "Rotate stock"),
                    "Egg Case Inspection" to listOf("Check for breakage", "Wipe spills", "Verify grade signage"),
                    "Butter/Margarine Facing" to listOf("Pull stock forward", "Check pricing accuracy", "Wipe shelf")
                ),
                "Meat" to listOf(
                    "Ground Beef Log" to listOf("Verify grind dates", "Seal check", "Facing"),
                    "Prime Cut Display" to listOf("Check marbling labels", "Verify weight tags", "Clean glass"),
                    "Poultry Area Audit" to listOf("Check for leaks", "Verify temp logs", "Stock rotation"),
                    "Sausage Wall Facing" to listOf("Organize by variety", "Check pricing tags", "Remove damaged"),
                    "Service Case Sanitization" to listOf("Sanitize scale", "Clean back prep", "Replace knives")
                ),
                "Seafood" to listOf(
                    "Sustainability Audit" to listOf("Verify eco-labels", "Ice level check", "Check signage"),
                    "Shrimp Case Quality" to listOf("Verify smell/texture", "Check weight tags", "Rotate stock"),
                    "Live Lobster Tank" to listOf("Check water clarity", "Verify filter flow", "Clean glass"),
                    "Fillet Display Facing" to listOf("Organize by species", "Check garnish", "Wipe edges"),
                    "Clam/Oyster Audit" to listOf("Verify origin tags", "Check hydration", "Remove debris")
                ),
                "Floral" to listOf(
                    "Bouquet Refresh" to listOf("Trim stems", "Change water", "Verify pricing"),
                    "Plant Hydration Scan" to listOf("Check soil moisture", "Remove dead leaves", "Facing"),
                    "Balloons & Cards" to listOf("Check helium levels", "Organize card rack", "Wipe display"),
                    "Seasonal Arrangement" to listOf("Check inventory", "Update theme signage", "Stock board"),
                    "Floral Cooler Audit" to listOf("Check temp", "Wipe shelves", "Organize premades")
                ),
                "Nature's Marketplace" to listOf(
                    "Supplement Audit" to listOf("Facing", "Check safety seals", "Verify pricing"),
                    "Bulk Bin Refresh" to listOf("Sanitize scoops", "Check for debris", "Refill stock"),
                    "Gluten-Free Aisle" to listOf("Organize by category", "Check labels", "Facing"),
                    "Vitamin Stock Scan" to listOf("Remove expired", "Verify unit tags", "Dust shelving"),
                    "Eco-Friendly Cleaners" to listOf("Facing", "Check for leaks", "Verify pricing")
                ),
                "Front-end" to listOf(
                    "Register Cleanliness" to listOf("Wipe belt", "Clean card reader", "Empty bin"),
                    "Self-Checkout Audit" to listOf("Verify screen status", "Sanitize scanner", "Check bag stock"),
                    "Cart Corral Sweep" to listOf("Organize carts", "Clean handles", "Check wheel status"),
                    "Express Lane Scan" to listOf("Check item count compliance", "Facing candies", "Clean glass"),
                    "Customer Service Desk" to listOf("Organize academic forms", "Refill paper", "Wipe counter")
                ),
                "Logistics" to listOf(
                    "Loading Dock Sweep" to listOf("Clear debris", "Secure ramp", "Check safety lights"),
                    "Backroom Overstock" to listOf("Organize by dept", "Check aisle clear", "Label pallets"),
                    "Cold Storage Log" to listOf("Check freezer temp", "Verify defrost cycle", "Clear ice"),
                    "Delivery Invoice Audit" to listOf("Match PO numbers", "Check for damages", "File records"),
                    "Equipment Battery Check" to listOf("Check jack charge", "Inspect cables", "Log status")
                )
            )

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val baseTime = System.currentTimeMillis()
            
            var idCounter = 0
            deptData.forEach { (dept, tasks) ->
                tasks.forEach { (title, subs) ->
                    val dueStr = sdf.format(Date(baseTime + 7200000)) 
                    val task = GlassTask(
                        id = idCounter++, 
                        title = title, 
                        department = dept,
                        dueTime = dueStr,
                        durationEstimate = "15m"
                    )
                    subs.forEachIndexed { sIdx, sTitle ->
                        task.subtasks.add(SubTask(sIdx, sTitle))
                    }
                    taskList.add(task)
                }
            }
            taskRepository.saveTasks(taskList)
        }
        
        val firstIncomplete = taskList.indexOfFirst { it.status != "COMPLETED" }
        if (firstIncomplete != -1) currentTaskIndex = firstIncomplete
        
        refreshTaskListView()
    }

    private fun setupNavigation() {
        bottomNavigation?.setOnItemSelectedListener { item ->
            tasksSection?.visibility = if (item.itemId == R.id.nav_tasks) View.VISIBLE else View.GONE
            addTaskSection?.visibility = if (item.itemId == R.id.nav_add_task) View.VISIBLE else View.GONE
            glassSection?.visibility = if (item.itemId == R.id.nav_glass) View.VISIBLE else View.GONE
            reportSection?.visibility = if (item.itemId == R.id.nav_report) View.VISIBLE else View.GONE
            userSection?.visibility = if (item.itemId == R.id.nav_users) View.VISIBLE else View.GONE
            true
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnResync)?.setOnClickListener { 
            currentDeviceId?.let { id -> glassesManager.resetAndConnect(id) { pushToGlasses() } } ?: pushToGlasses()
        }
        findViewById<Button>(R.id.registerButton)?.setOnClickListener { glassesManager.startRegistration(this) }
        findViewById<Button>(R.id.btnClearTasks)?.setOnClickListener {
            taskList.clear(); currentTaskIndex = 0; refreshTaskListView()
        }
        findViewById<Button>(R.id.btnMockVoice)?.apply {
            text = "ACTIVATE WEGMANS VOICE"
            setOnClickListener { voiceCommandManager.startListening() }
        }
        findViewById<Button>(R.id.btnGenerateReport)?.setOnClickListener { generateElitePdfReport() }
        findViewById<Button>(R.id.btnResetAudit)?.setOnClickListener { resetAuditProgress() }
    }

    private fun resetAuditProgress() {
        taskList.forEach { task ->
            task.status = "PENDING"
            task.voiceNote = null
            task.capturedImagePath = null
            task.completedBy = null
            task.completionTime = null
            
            task.subtasks.forEach { sub ->
                sub.isCompleted = false
                sub.voiceNote = null
                sub.capturedImagePath = null
            }
        }
        
        currentTaskIndex = 0
        currentSubTaskIndex = 0
        
        taskRepository.saveTasks(taskList)
        runOnUiThread {
            refreshTaskListView()
            pushToGlasses()
            Toast.makeText(this, "Audit Progress Reset", Toast.LENGTH_SHORT).show()
            bottomNavigation?.selectedItemId = R.id.nav_tasks
        }
    }

    private fun moveNext() {
        if (taskList.isEmpty()) return
        val filtered = getFilteredTasks()
        if (filtered.isEmpty()) return
        
        val currentInFiltered = filtered.indexOf(taskList[currentTaskIndex])
        var nextInFiltered = currentInFiltered
        
        for (i in 1 until filtered.size) {
            val candidateIdx = (currentInFiltered + i) % filtered.size
            if (filtered[candidateIdx].status != "COMPLETED") {
                nextInFiltered = candidateIdx
                break
            }
        }
        
        if (nextInFiltered == currentInFiltered) {
            nextInFiltered = (currentInFiltered + 1) % filtered.size
        }

        currentTaskIndex = taskList.indexOf(filtered[nextInFiltered])
        currentSubTaskIndex = 0 // Reset subtask index when moving to new task
        runOnUiThread { refreshTaskListView(); pushToGlasses() }
    }

    private fun movePrevious() {
        if (taskList.isEmpty()) return
        val filtered = getFilteredTasks()
        if (filtered.isEmpty()) return
        
        val currentInFiltered = filtered.indexOf(taskList[currentTaskIndex])
        var prevInFiltered = currentInFiltered

        for (i in 1 until filtered.size) {
            val candidateIdx = if (currentInFiltered - i < 0) filtered.size - (i - currentInFiltered) else currentInFiltered - i
            val normIdx = (candidateIdx + filtered.size) % filtered.size
            if (filtered[normIdx].status != "COMPLETED") {
                prevInFiltered = normIdx
                break
            }
        }

        if (prevInFiltered == currentInFiltered) {
            prevInFiltered = if (currentInFiltered - 1 < 0) filtered.size - 1 else currentInFiltered - 1
        }

        currentTaskIndex = taskList.indexOf(filtered[prevInFiltered])
        currentSubTaskIndex = 0 // Reset subtask index
        runOnUiThread { refreshTaskListView(); pushToGlasses() }
    }

    private fun moveSubNext() {
        val task = taskList[currentTaskIndex]
        if (task.subtasks.isEmpty()) return
        currentSubTaskIndex = (currentSubTaskIndex + 1) % task.subtasks.size
        runOnUiThread { refreshTaskListView(); pushToGlasses() }
    }

    private fun moveSubPrevious() {
        val task = taskList[currentTaskIndex]
        if (task.subtasks.isEmpty()) return
        currentSubTaskIndex = if (currentSubTaskIndex - 1 < 0) task.subtasks.size - 1 else currentSubTaskIndex - 1
        runOnUiThread { refreshTaskListView(); pushToGlasses() }
    }

    private fun markComplete() {
        if (taskList.isEmpty()) return
        val task = taskList[currentTaskIndex]
        
        // If subtasks exist, clicking DONE on glasses marks the FOCUSED subtask as done first
        if (task.subtasks.isNotEmpty() && currentSubTaskIndex in task.subtasks.indices) {
            val sub = task.subtasks[currentSubTaskIndex]
            sub.isCompleted = true
            
            if (task.isFullyComplete()) {
                task.status = "COMPLETED"
                val user = currentUser
                task.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
                task.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                taskRepository.saveTasks(taskList)
                moveNext()
            } else {
                task.status = "IN PROGRESS"
                taskRepository.saveTasks(taskList)
                moveSubNext()
            }
        } else {
            task.status = "COMPLETED"
            val user = currentUser
            task.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
            task.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            taskRepository.saveTasks(taskList)
            moveNext()
        }
    }

    private fun handleVoiceCommand(command: String) {
        if (command.startsWith("DICTATION:")) {
            val text = command.removePrefix("DICTATION:")
            val activeTask = taskList[currentTaskIndex]
            
            // Apply dictation to FOCUSED subtask if exists
            if (activeTask.subtasks.isNotEmpty() && currentSubTaskIndex in activeTask.subtasks.indices) {
                val sub = activeTask.subtasks[currentSubTaskIndex]
                sub.voiceNote = text
                sub.isCompleted = true // Transcribing usually implies completion of the check
                
                if (activeTask.isFullyComplete()) {
                    activeTask.status = "COMPLETED"
                    val user = currentUser
                    activeTask.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
                    activeTask.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    taskRepository.saveTasks(taskList)
                    // BATCHED: Don't call moveNext() immediately if not needed
                } else {
                    activeTask.status = "IN PROGRESS"
                    taskRepository.saveTasks(taskList)
                }
            } else {
                activeTask.voiceNote = text
                activeTask.status = "COMPLETED"
                val user = currentUser
                activeTask.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
                activeTask.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                taskRepository.saveTasks(taskList)
            }
            runOnUiThread { 
                refreshTaskListView()
                pushToGlasses()
            }
            return
        }

        when (command.lowercase()) {
            "next" -> moveNext()
            "back", "previous" -> movePrevious()
            "complete", "done" -> markComplete()
            "capture", "photo" -> startCapture()
            "review", "note" -> voiceCommandManager.startListening(dictation = true)
        }
    }

    private fun startCapture() {
        if (taskList.isEmpty() || currentTaskIndex !in taskList.indices) return
        
        lifecycleScope.launch {
            try {
                runOnUiThread { Toast.makeText(this@MainActivity, "Optimizing Link for Capture...", Toast.LENGTH_SHORT).show() }
                
                // INCREASED DELAY TO CLEAR BLUETOOTH CONGESTION (Important for active calls)
                delay(800)
                
                Log.d("MainActivity", "Requesting Photo from GlassesManager...")
                val photoData = glassesManager.takePhoto()
                
                if (photoData != null) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Photo received, processing...", Toast.LENGTH_SHORT).show() }
                    
                    // Process storage on IO thread
                    val path = withContext(Dispatchers.IO) { savePhotoToInternalStorage(photoData) }
                    
                    if (path != null) {
                        Log.d("MainActivity", "Photo Captured & Saved: $path")
                        val activeTask = taskList[currentTaskIndex]
                        
                        if (activeTask.subtasks.isNotEmpty() && currentSubTaskIndex in activeTask.subtasks.indices) {
                            val sub = activeTask.subtasks[currentSubTaskIndex]
                            sub.capturedImagePath = path
                            if (activeTask.status == "PENDING") activeTask.status = "IN PROGRESS"
                        } else {
                            activeTask.status = "EVIDENCE CAPTURED"
                            activeTask.capturedImagePath = path
                        }
                        
                        taskRepository.saveTasks(taskList)
                        runOnUiThread { 
                            Toast.makeText(this@MainActivity, "Evidence Logged", Toast.LENGTH_SHORT).show()
                            refreshTaskListView()
                            pushToGlasses()
                        }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Error saving photo locally.", Toast.LENGTH_LONG).show() }
                    }
                } else {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Capture failed. Ensure Bluetooth is clear and retry.", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Capture Error", e)
                runOnUiThread { Toast.makeText(this@MainActivity, "Link Congestion - Please Retry", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun savePhotoToInternalStorage(photoData: PhotoData): String? {
        if (currentTaskIndex !in taskList.indices) return null
        return try {
            val fileName = "storeflow_${System.currentTimeMillis()}.jpg"
            val file = File(filesDir, fileName)
            FileOutputStream(file).use { out ->
                when (photoData) {
                    is PhotoData.Bitmap -> photoData.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    is PhotoData.HEIC -> {
                        val bytes = ByteArray(photoData.data.remaining())
                        photoData.data.get(bytes)
                        out.write(bytes)
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) { 
            Log.e("MainActivity", "File Save Error", e)
            null 
        }
    }

    private fun observeDevices() {
        glassesManager.observeDevices { devices ->
            runOnUiThread {
                if (devices.isNotEmpty()) {
                    currentDeviceId = devices.first()
                    currentDeviceId?.let { id -> glassesManager.connect(id) { pushToGlasses() } }
                } else { currentDeviceId = null }
            }
        }
    }

    private fun getFilteredTasks(): List<GlassTask> {
        val userDepts = currentUser?.departments ?: emptyList()
        return if (selectedDeptFilter == "ALL DEPTS") {
            if (userDepts.isNotEmpty()) taskList.filter { it.department in userDepts } else taskList
        } else {
            taskList.filter { it.department == selectedDeptFilter }
        }
    }

    private fun refreshTaskListView() {
        val completedCount = taskList.count { it.status == "COMPLETED" }
        val percent = if (taskList.isEmpty()) 0 else (completedCount * 100) / taskList.size
        progressText?.text = getString(R.string.progress_placeholder, percent)

        taskListContainer?.removeAllViews()
        val filtered = getFilteredTasks()
        
        filtered.forEach { task ->
            val indexInMain = taskList.indexOf(task)
            val isSelected = indexInMain == currentTaskIndex
            val isDone = task.status == "COMPLETED"
            
            // ELITE CARD WRAPPER - STRICT DYNAMIC HEIGHT
            val card = MaterialCardView(this).apply {
                val cardParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                cardParams.setMargins(0, 0, 0, 32)
                layoutParams = cardParams
                
                radius = 16f
                strokeWidth = if (isSelected) 4 else 1
                setStrokeColor(ColorStateList.valueOf(if (isSelected) WEGMANS_GREEN.toColorInt() else "#EEEEEE".toColorInt()))
                cardElevation = if (isSelected) 6f else 2f
                
                val cardColor = when {
                    isDone -> "#E8F5E9"
                    isSelected -> WEGMANS_OFF_WHITE
                    else -> "#FFFFFF"
                }
                setCardBackgroundColor(cardColor.toColorInt())
                
                // INTERNAL CONTENT STACK
                val mainLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    
                    // HEADER
                    val header = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        
                        val title = TextView(context).apply {
                            text = task.title
                            setTextColor(if (isSelected || isDone) TEXT_PRIMARY.toColorInt() else TEXT_SECONDARY.toColorInt())
                            textSize = 22f
                            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(title)
                        
                        val deptLabel = TextView(context).apply {
                            text = task.department.uppercase()
                            setTextColor(WEGMANS_GREEN.toColorInt())
                            textSize = 12f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(12, 0, 12, 0)
                        }
                        addView(deptLabel)

                        val statusLight = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(24, 24)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isDone) WEGMANS_GREEN.toColorInt() else if (isSelected) "#00BCD4".toColorInt() else "#DDDDDD".toColorInt())
                            }
                        }
                        addView(statusLight)
                    }
                    addView(header)

                    // TIMING
                    val timingRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        val dueText = TextView(context).apply {
                            text = "DUE: ${task.dueTime ?: "ASAP"} (Est: ${task.durationEstimate ?: "15m"})"
                            setTextColor("#999999".toColorInt())
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(dueText)
                    }
                    addView(timingRow)

                    // STATUS & CONTROLS
                    val details = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 12, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        val statusText = TextView(context).apply {
                            text = task.status
                            setTextColor(if (isDone) WEGMANS_GREEN.toColorInt() else TEXT_SECONDARY.toColorInt())
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(statusText)

                        // Main Task Image (if any)
                        if (task.capturedImagePath != null) {
                            val thumbCard = MaterialCardView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(16, 0, 16, 0) }
                                radius = 8f
                                val thumb = ImageView(context).apply {
                                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    val bitmap = BitmapFactory.decodeFile(task.capturedImagePath)
                                    setImageBitmap(bitmap)
                                    setOnClickListener { showFullImage(task.capturedImagePath!!) }
                                }
                                addView(thumb)
                            }
                            addView(thumbCard)
                        }

                        val controls = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            val upBtn = TextView(context).apply { text = "▲"; setTextColor("#CCCCCC".toColorInt()); textSize = 16f; setPadding(12, 8, 12, 8); setOnClickListener { moveTaskUp(indexInMain) } }
                            val downBtn = TextView(context).apply { text = "▼"; setTextColor("#CCCCCC".toColorInt()); textSize = 16f; setPadding(12, 8, 12, 8); setOnClickListener { moveTaskDown(indexInMain) } }
                            addView(upBtn); addView(downBtn)
                        }
                        addView(controls)
                    }
                    addView(details)

                    // SUBTASK GRID - REFACTORED FOR MEASUREMENT STABILITY
                    if (task.subtasks.isNotEmpty()) {
                        val subSection = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, 16, 0, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                        
                        val rowCount = Math.ceil(task.subtasks.size / 2.0).toInt()
                        for (r in 0 until rowCount) {
                            val rowLayout = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                weightSum = 2f
                            }
                            
                            for (c in 0..1) {
                                val sIdx = r * 2 + c
                                if (sIdx < task.subtasks.size) {
                                    val sub = task.subtasks[sIdx]
                                    val subItemContainer = LinearLayout(context).apply {
                                        orientation = LinearLayout.VERTICAL
                                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                        setPadding(8, 8, 8, 8)
                                        
                                        val cb = CheckBox(context).apply {
                                            layoutParams = LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                            text = sub.title
                                            isChecked = sub.isCompleted
                                            setTextColor(if (sub.isCompleted) "#666666".toColorInt() else Color.BLACK)
                                            textSize = 13f
                                            buttonTintList = ColorStateList.valueOf(WEGMANS_GREEN.toColorInt())
                                            setOnCheckedChangeListener { _, checked ->
                                                sub.isCompleted = checked
                                                updateTaskStatus(task)
                                                taskRepository.saveTasks(taskList)
                                                refreshTaskListView()
                                                pushToGlasses()
                                            }
                                        }
                                        addView(cb)

                                        if (sub.capturedImagePath != null || sub.voiceNote != null) {
                                            val assetStack = LinearLayout(context).apply {
                                                orientation = LinearLayout.VERTICAL
                                                setPadding(32, 0, 0, 0) 
                                                layoutParams = LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                                )

                                                if (sub.capturedImagePath != null) {
                                                    val thumbCard = MaterialCardView(context).apply {
                                                        layoutParams = LinearLayout.LayoutParams(
                                                            LinearLayout.LayoutParams.MATCH_PARENT, 
                                                            240
                                                        ).apply { setMargins(0, 8, 0, 8) }
                                                        radius = 12f
                                                        strokeWidth = 2
                                                        setStrokeColor(ColorStateList.valueOf("#EEEEEE".toColorInt()))
                                                        val img = ImageView(context).apply {
                                                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                                                            scaleType = ImageView.ScaleType.CENTER_CROP
                                                            val bitmap = BitmapFactory.decodeFile(sub.capturedImagePath)
                                                            setImageBitmap(bitmap)
                                                            setOnClickListener { showFullImage(sub.capturedImagePath!!) }
                                                        }
                                                        addView(img)
                                                    }
                                                    addView(thumbCard)
                                                }

                                                if (sub.voiceNote != null) {
                                                    val noteBox = LinearLayout(context).apply {
                                                        orientation = LinearLayout.VERTICAL
                                                        setPadding(16, 12, 16, 12)
                                                        layoutParams = LinearLayout.LayoutParams(
                                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                                        ).apply { setMargins(0, 4, 0, 8) }
                                                        background = GradientDrawable().apply {
                                                            setColor("#F0F3F1".toColorInt())
                                                            cornerRadius = 10f
                                                        }
                                                        val noteText = TextView(context).apply {
                                                            text = getString(R.string.review_placeholder, sub.voiceNote)
                                                            setTextColor("#333333".toColorInt())
                                                            textSize = 12f
                                                            setTypeface(null, Typeface.ITALIC)
                                                        }
                                                        addView(noteText)
                                                    }
                                                    addView(noteBox)
                                                }
                                            }
                                            addView(assetStack)
                                        }
                                    }
                                    rowLayout.addView(subItemContainer)
                                } else {
                                    rowLayout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                                }
                            }
                            subSection.addView(rowLayout)
                        }
                        addView(subSection)
                    }

                    // MAIN TASK VOICE NOTE
                    task.voiceNote?.let { note ->
                        val voiceBox = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(24, 16, 24, 16)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
                            background = GradientDrawable().apply { setColor("#F0F0F0".toColorInt()); cornerRadius = 12f }
                            addView(TextView(context).apply { text = "AUDIT NOTE"; setTextColor(WEGMANS_GREEN.toColorInt()); textSize = 11f; setTypeface(null, Typeface.BOLD) })
                            addView(TextView(context).apply { text = getString(R.string.review_placeholder, note); setTextColor(TEXT_PRIMARY.toColorInt()); textSize = 16f; setPadding(0, 4, 0, 0) })
                        }
                        addView(voiceBox)
                    }
                }
                addView(mainLayout)
                
                setOnClickListener {
                    currentTaskIndex = indexInMain
                    refreshTaskListView()
                    pushToGlasses()
                }
            }
            taskListContainer?.addView(card)
        }

        // FORCE IMMEDIATE RE-LAYOUT PASS
        taskListContainer?.post {
            taskListContainer?.requestLayout()
            tasksSection?.requestLayout()
            taskListContainer?.invalidate()
        }
    }

    private fun updateTaskStatus(task: GlassTask) {
        if (task.subtasks.all { it.isCompleted }) {
            task.status = "COMPLETED"
            val user = currentUser
            task.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
            task.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        } else if (task.subtasks.any { it.isCompleted }) { 
            task.status = "IN PROGRESS" 
        } else { 
            task.status = "PENDING"
        }
    }

    private fun generateElitePdfReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            val paint = Paint()
            val pageWidth = 595
            val pageHeight = 842
            var pageNumber = 1
            var myPageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var myPage = pdfDocument.startPage(myPageInfo)
            var canvas = myPage.canvas
            val margin = 40f
            val columnGap = 20f
            val columnWidth = (pageWidth - (margin * 2) - columnGap) / 2
            var currentColumn = 0
            var y = 160f

            fun drawHeader(canv: android.graphics.Canvas) {
                paint.color = WEGMANS_GREEN.toColorInt()
                canv.drawRect(0f, 0f, pageWidth.toFloat(), 120f, paint)
                paint.color = Color.WHITE
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = 22f
                canv.drawText("WEGMANS STOREFLOW AUDIT REPORT", margin, 65f, paint)
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                paint.textSize = 10f
                val dateStr = SimpleDateFormat("MMMM dd, yyyy - HH:mm", Locale.getDefault()).format(Date())
                canv.drawText("GENERATED ON: $dateStr", margin, 90f, paint)
            }

            drawHeader(canvas)
            val completed = taskList.count { it.status == "COMPLETED" }
            val percent = if (taskList.isEmpty()) 0 else (completed * 100) / taskList.size
            val sentiment = analyzeSentiment(taskList)
            paint.color = Color.BLACK
            paint.textSize = 14f
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val summaryText = "EXECUTIVE SUMMARY: $percent% COMPLETE | SENTIMENT: $sentiment"
            canvas.drawText(summaryText, (pageWidth - paint.measureText(summaryText)) / 2f, 150f, paint)
            paint.color = "#DDDDDD".toColorInt()
            paint.strokeWidth = 1f
            canvas.drawLine(margin, 165f, pageWidth - margin, 165f, paint)
            y = 190f

            taskList.forEachIndexed { index, task ->
                // 1. PRE-PROCESS SUBTASK IMAGES (HIGH QUALITY)
                val subtaskImages = task.subtasks.map { sub ->
                    if (sub.capturedImagePath != null) {
                        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = BitmapFactory.decodeFile(sub.capturedImagePath, options)
                        if (bitmap != null) {
                            val targetW = (columnWidth - 30f) / 2f 
                            val scale = targetW / bitmap.width.toFloat()
                            val targetH = bitmap.height.toFloat() * scale
                            Bitmap.createScaledBitmap(bitmap, targetW.toInt(), targetH.toInt(), true)
                        } else null
                    } else null
                }

                var mainScaledBitmap: Bitmap? = null
                if (task.capturedImagePath != null) {
                    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bitmap = BitmapFactory.decodeFile(task.capturedImagePath, options)
                    if (bitmap != null) {
                        val targetWidth = columnWidth - 20f
                        val scale = targetWidth / bitmap.width.toFloat()
                        val mainImgHeight = bitmap.height.toFloat() * scale
                        mainScaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth.toInt(), mainImgHeight.toInt(), true)
                    }
                }

                // 2. PRE-CALCULATE ENTIRE TASK BLOCK HEIGHT (DUAL COLUMN LOGIC)
                var taskH = 45f // Header space
                val rowCount = Math.ceil(task.subtasks.size / 2.0).toInt()
                
                for (row in 0 until rowCount) {
                    var rowMaxH = 15f
                    val idx1 = row * 2
                    val idx2 = row * 2 + 1
                    
                    // Col 1
                    var col1H = 12f
                    if (task.subtasks[idx1].voiceNote != null) col1H += 10f
                    subtaskImages[idx1]?.let { col1H += it.height.toFloat() + 5f }
                    
                    // Col 2
                    var col2H = 0f
                    if (idx2 < task.subtasks.size) {
                        col2H = 12f
                        if (task.subtasks[idx2].voiceNote != null) col2H += 10f
                        subtaskImages[idx2]?.let { col2H += it.height.toFloat() + 5f }
                    }
                    rowMaxH = Math.max(col1H, col2H)
                    taskH += rowMaxH + 8f
                }
                
                if (task.voiceNote != null) taskH += 20f
                taskH += 12f // Signature
                mainScaledBitmap?.let { taskH += it.height.toFloat() + 15f }
                
                val totalH = taskH + 40f

                // 3. PAGE & COLUMN BREAK LOGIC
                if (y + totalH > pageHeight - 50f) {
                    if (currentColumn == 0) { currentColumn = 1; y = 190f }
                    else {
                        pdfDocument.finishPage(myPage)
                        pageNumber++
                        myPageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        myPage = pdfDocument.startPage(myPageInfo)
                        canvas = myPage.canvas
                        drawHeader(canvas)
                        currentColumn = 0
                        y = 140f
                    }
                }

                val xS = margin + (currentColumn * (columnWidth + columnGap))
                
                // 4. DRAWING BACKGROUND
                paint.color = "#F7F7F7".toColorInt()
                canvas.drawRect(xS, y - 10f, xS + columnWidth, y + totalH - 15f, paint)
                paint.color = if (task.status == "COMPLETED") WEGMANS_GREEN.toColorInt() else Color.RED
                canvas.drawRect(xS, y - 10f, xS + 6f, y + totalH - 15f, paint)

                // 5. DRAWING CONTENT
                var drawY = y + 10f
                paint.color = Color.BLACK
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                paint.textSize = 10f
                canvas.drawText("${index + 1}. ${task.title.uppercase()}", xS + 12f, drawY, paint)
                
                drawY += 15f
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                paint.textSize = 8f
                canvas.drawText("DEPT: ${task.department}", xS + 12f, drawY, paint)
                
                drawY += 18f
                val subColW = (columnWidth - 30f) / 2f
                
                for (row in 0 until rowCount) {
                    val rowStartDrawY = drawY
                    var rowMaxMoveY = 0f
                    
                    for (col in 0..1) {
                        val subIdx = row * 2 + col
                        if (subIdx >= task.subtasks.size) continue
                        
                        val subXS = xS + 12f + (col * (subColW + 10f))
                        var subDrawY = rowStartDrawY
                        
                        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                        paint.textSize = 7f
                        paint.color = if (task.subtasks[subIdx].isCompleted) WEGMANS_GREEN.toColorInt() else Color.GRAY
                        canvas.drawText("${if (task.subtasks[subIdx].isCompleted) "[X]" else "[ ]"} ${task.subtasks[subIdx].title}", subXS, subDrawY, paint)
                        subDrawY += 10f
                        
                        if (task.subtasks[subIdx].voiceNote != null) {
                            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
                            paint.color = Color.DKGRAY
                            canvas.drawText("  “${task.subtasks[subIdx].voiceNote}”", subXS, subDrawY, paint)
                            subDrawY += 9f
                        }
                        
                        subtaskImages[subIdx]?.let {
                            canvas.drawBitmap(it, subXS + 5f, subDrawY + 2f, null)
                            subDrawY += it.height.toFloat() + 8f
                        }
                        rowMaxMoveY = Math.max(rowMaxMoveY, subDrawY - rowStartDrawY)
                    }
                    drawY += rowMaxMoveY + 5f
                }

                if (task.voiceNote != null) {
                    paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
                    paint.color = Color.BLACK
                    paint.textSize = 8f
                    canvas.drawText("Note: \"${task.voiceNote}\"", xS + 12f, drawY, paint)
                    drawY += 15f
                }
                
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                paint.textSize = 7f
                paint.color = Color.GRAY
                val attr = if (task.completedBy != null) "PERFORMED BY: ${task.completedBy} at ${task.completionTime}" else "DUE BY: ${task.dueTime} (Est: ${task.durationEstimate})"
                canvas.drawText(attr, xS + 12f, drawY, paint)
                drawY += 12f

                mainScaledBitmap?.let { canvas.drawBitmap(it, xS + 10f, drawY + 5f, null) }
                y += totalH
            }
            pdfDocument.finishPage(myPage)
            pushToGlasses()
            val fileName = "Wegmans_Audit_StoreFlow_${System.currentTimeMillis()}.pdf"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            try {
                pdfDocument.writeTo(FileOutputStream(file))
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "StoreFlow Report Generated", Toast.LENGTH_LONG).show(); openPdf(file) }
            } catch (e: Exception) { Log.e("MainActivity", "PDF error", e) } finally { pdfDocument.close() }
        }
    }

    private fun analyzeSentiment(tasks: List<GlassTask>): String {
        val notes = tasks.mapNotNull { it.voiceNote?.lowercase() }
        if (notes.isEmpty()) return "NEUTRAL (NO DATA)"
        var score = 0
        val pos = listOf("good", "great", "clean", "stocked", "complete", "verified", "excellent", "healthy", "fresh")
        val neg = listOf("bad", "dirty", "empty", "expired", "failed", "broken", "spill", "unhealthy", "stale")
        notes.forEach { n -> pos.forEach { if (n.contains(it)) score++ }; neg.forEach { if (n.contains(it)) score-- } }
        return when { score > 2 -> "EXCELLENT"; score > 0 -> "POSITIVE"; score < -2 -> "CRITICAL"; score < 0 -> "CONCERNING"; else -> "NEUTRAL" }
    }

    private fun openPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try { startActivity(intent) } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "No PDF viewer", Toast.LENGTH_SHORT).show() } }
    }

    private fun pushToGlasses() {
        runOnUiThread {
            if (taskList.isNotEmpty() && currentTaskIndex in taskList.indices) {
                glassesManager.renderTaskHUD(taskList[currentTaskIndex], currentTaskIndex, taskList.size, currentSubTaskIndex)
            }
        }
    }

    private fun moveTaskUp(index: Int) {
        if (index > 0) {
            val task = taskList.removeAt(index)
            taskList.add(index - 1, task)
            if (currentTaskIndex == index) currentTaskIndex--
            else if (currentTaskIndex == index - 1) currentTaskIndex++
            taskRepository.saveTasks(taskList)
            refreshTaskListView()
            pushToGlasses()
        }
    }

    private fun moveTaskDown(index: Int) {
        if (index < taskList.size - 1) {
            val task = taskList.removeAt(index)
            taskList.add(index + 1, task)
            if (currentTaskIndex == index) currentTaskIndex++
            else if (currentTaskIndex == index + 1) currentTaskIndex--
            taskRepository.saveTasks(taskList)
            refreshTaskListView()
            pushToGlasses()
        }
    }

    private fun showFullImage(path: String) {
        val imgView = ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(path))
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setView(imgView)
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        glassesManager.cleanup()
        voiceCommandManager.destroy()
    }
}
