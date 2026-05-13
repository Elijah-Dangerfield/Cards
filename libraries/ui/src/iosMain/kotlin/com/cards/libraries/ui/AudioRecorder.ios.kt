package com.dangerfield.cards.libraries.ui

import kotlinx.cinterop.ExperimentalForeignApi
import me.tatarka.inject.annotations.Inject
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

@OptIn(ExperimentalForeignApi::class)
@ContributesBinding(AppScope::class)
@Inject
class IosAudioRecorder : AudioRecorder {

    private var audioRecorder: AVAudioRecorder? = null
    private var currentFilePath: String? = null
    private var recording = false

    override suspend fun startRecording(): Boolean {
        return try {
            val audioSession = AVAudioSession.sharedInstance()
            audioSession.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionModeDefault, 0u, null)
            audioSession.setActive(true, null)

            val fileManager = NSFileManager.defaultManager
            val documentsUrl = fileManager.URLsForDirectory(
                NSDocumentDirectory,
                NSUserDomainMask
            ).firstOrNull() as? NSURL ?: return false

            val recordingsDir = documentsUrl.URLByAppendingPathComponent("recordings")
            if (recordingsDir != null && !fileManager.fileExistsAtPath(recordingsDir.path ?: "")) {
                fileManager.createDirectoryAtURL(recordingsDir, true, null, null)
            }

            val fileName = "recording_${NSUUID().UUIDString}.m4a"
            val fileUrl = recordingsDir?.URLByAppendingPathComponent(fileName) ?: return false
            currentFilePath = fileUrl.path

            val settings = mapOf<Any?, Any?>(
                platform.AVFAudio.AVFormatIDKey to platform.CoreAudioTypes.kAudioFormatMPEG4AAC,
                platform.AVFAudio.AVSampleRateKey to 44100.0,
                platform.AVFAudio.AVNumberOfChannelsKey to 1,
                platform.AVFAudio.AVEncoderAudioQualityKey to 127L, // AVAudioQuality.max (high quality)
            )

            audioRecorder = AVAudioRecorder(fileUrl, settings, null)
            audioRecorder?.prepareToRecord()
            val started = audioRecorder?.record() ?: false
            recording = started
            started
        } catch (e: Exception) {
            release()
            false
        }
    }

    override suspend fun stopRecording(): String? {
        return try {
            audioRecorder?.stop()
            recording = false
            val path = currentFilePath
            audioRecorder = null
            path
        } catch (e: Exception) {
            release()
            null
        }
    }

    override fun isRecording(): Boolean = recording

    override fun release() {
        try {
            audioRecorder?.stop()
        } catch (_: Exception) { }
        audioRecorder = null
        recording = false
    }
}
