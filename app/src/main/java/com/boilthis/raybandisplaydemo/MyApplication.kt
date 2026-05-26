package com.boilthis.raybandisplaydemo

import android.app.Application
import com.meta.wearable.dat.core.Wearables

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize the Meta Wearables Device Access Toolkit
        Wearables.initialize(this)
    }
}