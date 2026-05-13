package com.dangerfield.cards.libraries.ui

interface AudioRecorder {
    suspend fun startRecording(): Boolean
    suspend fun stopRecording(): String?
    fun isRecording(): Boolean
    fun release()
}
