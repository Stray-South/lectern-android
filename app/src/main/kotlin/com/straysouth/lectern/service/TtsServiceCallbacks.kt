package com.straysouth.lectern.service

interface TtsServiceCallbacks {
    fun onPlayPause()
    fun onStop()
    fun onTaskRemoved()
}
