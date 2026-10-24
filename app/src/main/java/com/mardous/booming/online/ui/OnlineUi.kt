package com.mardous.booming.online.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object OnlineToast {
    private var app: Application? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(application: Application) {
        app = application
    }

    fun show(message: String) {
        val context = app ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
