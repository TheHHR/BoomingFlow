package com.mardous.booming.online.util

import android.util.Log

/**
 * Unified Console Logger for SpatialFlow.
 * Provides clean, single-entry, filterable Logcat output for
 * audio formats, stream extraction, client fallbacks, and ExoPlayer events.
 */
object ConsoleLogger {

    private const val TAG = "SpatialFlowConsole"

    fun d(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
    }

    fun i(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, "[$tag] $message", throwable)
        } else {
            Log.w(TAG, "[$tag] $message")
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, "[$tag] $message", throwable)
        } else {
            Log.e(TAG, "[$tag] $message")
        }
    }

    fun format(message: String) {
        Log.i(TAG, "🎧 [FORMAT] $message")
    }

    fun stream(message: String) {
        Log.i(TAG, "🎵 [STREAM] $message")
    }

    fun client(message: String) {
        Log.i(TAG, "🌐 [CLIENT] $message")
    }

    fun player(message: String) {
        Log.i(TAG, "▶️ [PLAYER] $message")
    }
}
