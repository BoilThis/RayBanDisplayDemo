package com.boilthis.raybandisplaydemo

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize the Meta Wearables Device Access Toolkit
        val result = Wearables.initialize(this)
        Log.d("MyApplication", "Wearables SDK Initialize: $result")
    }
}