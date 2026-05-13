package com.dangerfield.cards.libraries.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import java.io.File
import java.util.UUID

@ContributesBinding(AppScope::class)
@Inject
class AndroidAudioRecorder(
    private val context: Context,
) : AudioRecorder {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String? = null
    private var recording = false

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        try {
            val recordingsDir = File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }

            val fileName = "recording_${UUID.randomUUID()}.m4a"
            val file = File(recordingsDir, fileName)
            currentFilePath = file.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            recording = true
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    override suspend fun stopRecording(): String? = withContext(Dispatchers.IO) {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            recording = false
            currentFilePath
        } catch (e: Exception) {
            release()
            null
        }
    }

    override fun isRecording(): Boolean = recording

    override fun release() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
        recording = false
    }
}
