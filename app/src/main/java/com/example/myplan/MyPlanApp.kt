package com.example.myplan

import android.app.Application
import com.example.myplan.notification.NotificationHelper

class MyPlanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
