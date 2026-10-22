package com.mardous.booming.online.ui

import android.app.Application
import android.widget.Toast

object OnlineToast {
    private var app: Application? = null

    fun init(application: Application) {
        app = application
    }

    fun show(message: String) {
        app?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}
