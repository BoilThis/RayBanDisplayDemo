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

    private var currentUser: User? = null
    private var availableDepartments = mutableListOf<String>()

    private var devicesText: TextView? = null
    private var taskListContainer: LinearLayout? = null
    private var userListContainer: LinearLayout? = null
    private var bottomNavigation: BottomNavigationView? = null
    private var tasksSection: View? = null
    private var glassSection: View? = null
    private var reportSection: View? = null
    private var userSection: View? = null
    private var progressText: TextView? = null
    private var deptSpinner: Spinner? = null

    private lateinit var glassesManager: GlassesManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var taskRepository: TaskRepository
    private lateinit var userRepository: UserRepository
    
    private var currentDeviceId: DeviceIdentifier? = null
    private var taskList = mutableListOf<GlassTask>()
    private var userList = mutableListOf<User>()
    private var currentTaskIndex = 0
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
        progressText = findViewById(R.id.progressStats)
        deptSpinner = findViewById(R.id.deptSpinner)

        devicesText?.text = "SYSTEM INITIALIZING..."
        
        taskRepository = TaskRepository(this)
        userRepository = UserRepository(this)
        
        val loggedInId = intent.getStringExtra("LOGGED_IN_USER_ID")
        currentUser = userRepository.loadUsers().find { it.employeeId == loggedInId }
        
        setupAvailableDepartments()
        
        glassesManager = GlassesManager(this, this)
        
        voiceCommandManager = VoiceCommandManager(
            context = this,
            onCommandDetected = { command -> handleVoiceCommand(command) },
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
        glassesManager.onCaptureClick = { runOnUiThread { startCapture() } }
        glassesManager.onReviewClick = { runOnUiThread { voiceCommandManager.startListening(dictation = true) } }
        glassesManager.onCompleteClick = { runOnUiThread { markComplete() } }
        glassesManager.onPrevClick = { runOnUiThread { movePrevious() } }
        glassesManager.onNextClick = { runOnUiThread { moveNext() } }

        setupDeptSpinner()
        setupNavigation()
        setupButtons()
        setupUserAdmin()
        initGroceryTasks()
        checkPermissions()

        bottomNavigation?.selectedItemId = R.id.nav_tasks
    }

    private fun setupAvailableDepartments() {
        availableDepartments.clear()
        availableDepartments.add("ALL DEPTS")
        
        currentUser?.let { user ->
            // If they have specific departments assigned, only show those.
            // If none assigned (empty list), we'll assume they have access to all (Admin-like)
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
            
            // Clear form
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
                "Produce" to arrayOf("Inspect Leafy Greens", "Check Organic Apple Stock", "Sanitize Misting System"),
                "Market Cafe" to arrayOf("Check Hot Bar Temp", "Refill Self-Serve Soup", "Clean Dining Tables"),
                "Dairy" to arrayOf("Audit Milk Expiration", "Check Yogurt Inventory", "Organize Cheese Case"),
                "Bakery" to arrayOf("Verify Bread Crust Quality", "Check Pastry Case Stock", "Slice Fresh Loaves"),
                "Meat" to arrayOf("Log Ground Beef Temps", "Grade Prime Cut Display", "Check Package Seals"),
                "Seafood" to arrayOf("Verify Sustainable Labels", "Clean Ice Display", "Check Shrimp Freshness"),
                "Floral" to arrayOf("Trim Fresh Bouquets", "Refill Vase Water", "Check Plant Hydration"),
                "Nature's Marketplace" to arrayOf("Audit Supplement Aisle", "Check Bulk Bin Levels", "Organize Gluten-Free"),
                "Front-end" to arrayOf("Sanitize POS Stations", "Check Cart Corral", "Audit Bag Inventory"),
                "Logistics" to arrayOf("Unload Cold Shipment", "Scan Backroom Overstock", "Verify Delivery Invoice")
            )

            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val baseTime = System.currentTimeMillis()
            
            var idCounter = 0
            deptData.forEach { (dept, tasks) ->
                tasks.forEachIndexed { i, title ->
                    // Set a dummy due time (e.g., 2 hours from now)
                    val dueStr = sdf.format(Date(baseTime + 7200000)) 
                    taskList.add(GlassTask(
                        id = idCounter++, 
                        title = title, 
                        department = dept,
                        dueTime = dueStr,
                        durationEstimate = if (i % 2 == 0) "10m" else "20m"
                    ))
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
        runOnUiThread { refreshTaskListView(); pushToGlasses() }
    }

    private fun markComplete() {
        if (taskList.isEmpty()) return
        val task = taskList[currentTaskIndex]
        task.status = "COMPLETED"
        
        // Record attribution
        val user = currentUser
        task.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
        task.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        
        taskRepository.saveTasks(taskList)
        moveNext()
    }

    private fun moveTaskUp(index: Int) {
        if (index > 0) {
            val task = taskList.removeAt(index)
            taskList.add(index - 1, task)
            taskRepository.saveTasks(taskList)
            refreshTaskListView()
            pushToGlasses()
        }
    }

    private fun moveTaskDown(index: Int) {
        if (index < taskList.size - 1) {
            val task = taskList.removeAt(index)
            taskList.add(index + 1, task)
            taskRepository.saveTasks(taskList)
            refreshTaskListView()
            pushToGlasses()
        }
    }

    private fun handleVoiceCommand(command: String) {
        if (command.startsWith("DICTATION:")) {
            val text = command.removePrefix("DICTATION:")
            val task = taskList[currentTaskIndex]
            task.voiceNote = text
            task.status = "COMPLETED"
            
            // Record attribution
            val user = currentUser
            task.completedBy = if (user != null) "${user.firstName} ${user.lastName}" else "Anonymous"
            task.completionTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            taskRepository.saveTasks(taskList)
            runOnUiThread { refreshTaskListView() }
            pushToGlasses()
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
        if (taskList[currentTaskIndex].status == "COMPLETED") return

        lifecycleScope.launch {
            try {
                val photoData = glassesManager.takePhoto()
                if (photoData != null) {
                    val path = savePhotoToInternalStorage(photoData)
                    if (path != null && currentTaskIndex in taskList.indices) {
                        taskList[currentTaskIndex].status = "EVIDENCE CAPTURED"
                        taskList[currentTaskIndex].capturedImagePath = path
                        taskRepository.saveTasks(taskList)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Capture error", e)
            } finally {
                runOnUiThread { refreshTaskListView(); pushToGlasses() }
            }
        }
    }

    private fun savePhotoToInternalStorage(photoData: PhotoData): String? {
        if (currentTaskIndex !in taskList.indices) return null
        return try {
            val fileName = "task_${taskList[currentTaskIndex].id}_${System.currentTimeMillis()}.jpg"
            val file = File(filesDir, fileName)
            FileOutputStream(file).use { out ->
                when (photoData) {
                    is PhotoData.Bitmap -> photoData.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    is PhotoData.HEIC -> {
                        val bytes = ByteArray(photoData.data.remaining())
                        photoData.data.get(bytes)
                        out.write(bytes)
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) { null }
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
            if (userDepts.isNotEmpty()) {
                taskList.filter { it.department in userDepts }
            } else {
                taskList // Admin access to all
            }
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
            
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) }
                radius = 16f
                strokeWidth = if (isSelected) 4 else 1
                setStrokeColor(ColorStateList.valueOf(if (isSelected) WEGMANS_GREEN.toColorInt() else "#EEEEEE".toColorInt()))
                cardElevation = if (isSelected) 6f else 2f
                setCardBackgroundColor(if (isSelected) WEGMANS_OFF_WHITE.toColorInt() else Color.WHITE)
                
                val mainLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                    
                    val header = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val title = TextView(context).apply {
                            text = task.title
                            setTextColor(if (isSelected || isDone) TEXT_PRIMARY.toColorInt() else TEXT_SECONDARY.toColorInt())
                            textSize = 18f
                            setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(title)
                        
                        val deptLabel = TextView(context).apply {
                            text = task.department.uppercase()
                            setTextColor(WEGMANS_GREEN.toColorInt())
                            textSize = 10f
                            setTypeface(null, Typeface.BOLD)
                            setPadding(12, 0, 12, 0)
                        }
                        addView(deptLabel)

                        val statusLight = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(20, 20)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isDone) WEGMANS_GREEN.toColorInt() else if (isSelected) "#00BCD4".toColorInt() else "#DDDDDD".toColorInt())
                            }
                        }
                        addView(statusLight)
                    }
                    addView(header)

                    val details = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 12, 0, 0)
                        val status = TextView(context).apply {
                            text = task.status
                            setTextColor(if (isDone) WEGMANS_GREEN.toColorInt() else TEXT_SECONDARY.toColorInt())
                            textSize = 11f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(status)

                        if (task.capturedImagePath != null) {
                            val thumbCard = MaterialCardView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(16, 0, 16, 0) }
                                radius = 8f
                                val thumb = ImageView(context).apply {
                                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setImageURI(Uri.fromFile(File(task.capturedImagePath!!)))
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

                    task.voiceNote?.let { note ->
                        val voiceBox = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(24, 16, 24, 16)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 24, 0, 0) }
                            background = GradientDrawable().apply { setColor("#F0F0F0".toColorInt()); cornerRadius = 12f }
                            addView(TextView(context).apply { text = "AUDIT NOTE"; setTextColor(WEGMANS_GREEN.toColorInt()); textSize = 9f; setTypeface(null, Typeface.BOLD) })
                            addView(TextView(context).apply { text = getString(R.string.review_placeholder, note); setTextColor(TEXT_PRIMARY.toColorInt()); textSize = 14f; setPadding(0, 4, 0, 0) })
                        }
                        addView(voiceBox)
                    }
                }
                addView(mainLayout)
                setOnClickListener { currentTaskIndex = indexInMain; refreshTaskListView(); pushToGlasses() }
                if (isSelected) {
                    val pulse = AlphaAnimation(0.8f, 1.0f).apply { duration = 1000; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
                    startAnimation(pulse)
                }
            }
            taskListContainer?.addView(card)
        }
    }

    private fun showFullImage(path: String) {
        val imgView = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(path)); adjustViewBounds = true }
        AlertDialog.Builder(this).setView(imgView).setPositiveButton("Close", null).show()
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
                canv.drawText("WEGMANS ELITE AUDIT REPORT", margin, 65f, paint)
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
                val textHeight = 65f
                var imageAreaHeight = 0f
                var scaledBitmap: Bitmap? = null
                var finalImageHeight = 0f
                if (task.capturedImagePath != null) {
                    val bitmap = BitmapFactory.decodeFile(task.capturedImagePath)
                    if (bitmap != null) {
                        val targetWidth = columnWidth - 20f
                        val scale = targetWidth / bitmap.width.toFloat()
                        finalImageHeight = bitmap.height.toFloat() * scale
                        imageAreaHeight = finalImageHeight + 15f
                        scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth.toInt(), finalImageHeight.toInt(), true)
                    }
                }
                val totalTaskHeight = textHeight + imageAreaHeight + 25f
                if (y + totalTaskHeight > pageHeight - 50f) {
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
                val xStart = margin + (currentColumn * (columnWidth + columnGap))
                paint.color = "#F7F7F7".toColorInt(); canvas.drawRect(xStart, y - 10f, xStart + columnWidth, y + totalTaskHeight - 15f, paint)
                paint.color = if (task.status == "COMPLETED") WEGMANS_GREEN.toColorInt() else Color.RED; canvas.drawRect(xStart, y - 10f, xStart + 6f, y + totalTaskHeight - 15f, paint)
                paint.color = Color.BLACK; paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); paint.textSize = 10f; canvas.drawText("${index + 1}. ${task.title.uppercase()}", xStart + 12f, y + 10f, paint)
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL); paint.textSize = 8f; canvas.drawText("DEPT: ${task.department}", xStart + 12f, y + 25f, paint)
                if (task.voiceNote != null) { 
                    paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
                    canvas.drawText("Note: \"${task.voiceNote}\"", xStart + 12f, y + 40f, paint) 
                }

                // New Audit Metadata in PDF
                paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                paint.textSize = 7f
                paint.color = Color.GRAY
                val attribution = if (task.completedBy != null) "PERFORMED BY: ${task.completedBy} at ${task.completionTime}" else "DUE BY: ${task.dueTime} (Est: ${task.durationEstimate})"
                canvas.drawText(attribution, xStart + 12f, y + 52f, paint)
                
                if (scaledBitmap != null) {
                    canvas.drawBitmap(scaledBitmap, xStart + 10f, y + 65f, null)
                }
                y += totalTaskHeight
            }
            pdfDocument.finishPage(myPage)
            pushToGlasses()
            val fileName = "Wegmans_Audit_Elite_${System.currentTimeMillis()}.pdf"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            try {
                pdfDocument.writeTo(FileOutputStream(file))
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Elite Report Generated", Toast.LENGTH_LONG).show(); openPdf(file) }
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
                glassesManager.renderTaskHUD(taskList[currentTaskIndex], currentTaskIndex, taskList.size)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        glassesManager.cleanup()
        voiceCommandManager.destroy()
    }
}
