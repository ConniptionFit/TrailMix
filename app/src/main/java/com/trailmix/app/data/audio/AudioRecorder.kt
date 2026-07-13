package com.trailmix.app.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val currentFile: File? get() = outputFile

    fun start(): File {
        stopInternal(delete = false)
        val file = File(context.cacheDir, "trailmix_${System.currentTimeMillis()}.m4a")
        outputFile = file
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        return file
    }

    fun pause() {
        recorder?.pause()
    }

    fun resume() {
        recorder?.resume()
    }

    fun stop(): File? {
        return stopInternal(delete = false)
    }

    fun cancel() {
        stopInternal(delete = true)
    }

    private fun stopInternal(delete: Boolean): File? {
        val file = outputFile
        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (_: Exception) {
            // Already stopped or never started.
        }
        recorder = null
        outputFile = null
        if (delete) {
            file?.delete()
            return null
        }
        return file
    }
}
