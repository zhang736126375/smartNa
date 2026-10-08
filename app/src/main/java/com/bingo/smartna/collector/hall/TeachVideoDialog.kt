package com.bingo.smartna.collector.hall

import android.app.Dialog
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bingo.smartna.R

object TeachVideoDialog {

    private const val ASSET = "guide_wearing_tutorial.mp4"

    fun show(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        val dialog = Dialog(activity, R.style.Theme_SmartNa_TeachVideo)
        dialog.setContentView(R.layout.dialog_teach_video)
        dialog.setCancelable(true)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scrim = dialog.findViewById<View>(R.id.teachScrim)
        val videoCard = dialog.findViewById<View>(R.id.videoCard)
        val videoFrame = dialog.findViewById<FrameLayout>(R.id.videoFrame)
        val surface = dialog.findViewById<SurfaceView>(R.id.teachVideo)
        val btnPlay = dialog.findViewById<ImageView>(R.id.btnPlay)
        val session = PlayerSession(activity, videoFrame, btnPlay)

        scrim.setOnClickListener { dialog.dismiss() }
        videoCard.setOnClickListener { session.toggle() }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                session.open(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                session.detachSurface()
            }
        })

        val previousVolumeStream = activity.volumeControlStream
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> session.pause()
                Lifecycle.Event.ON_DESTROY -> if (dialog.isShowing) dialog.dismiss()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            session.release()
            activity.volumeControlStream = previousVolumeStream
            activity.lifecycle.removeObserver(observer)
        }
        dialog.show()
    }

    private class PlayerSession(
        private val activity: AppCompatActivity,
        private val videoFrame: FrameLayout,
        private val btnPlay: ImageView
    ) {
        private var player: MediaPlayer? = null
        private var prepared = false
        private var released = false

        fun open(holder: SurfaceHolder) {
            if (released || player != null) return
            val media = MediaPlayer()
            player = media
            try {
                activity.assets.openFd(ASSET).use { afd ->
                    media.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                media.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                media.setDisplay(holder)
                media.setOnVideoSizeChangedListener { _, width, height ->
                    if (width > 0 && height > 0) applyVideoSize(width, height)
                }
                media.setOnPreparedListener {
                    prepared = true
                    applyVideoSize(it.videoWidth, it.videoHeight)
                    it.start()
                    btnPlay.visibility = View.GONE
                }
                media.setOnCompletionListener {
                    it.seekTo(0)
                    btnPlay.visibility = View.VISIBLE
                }
                media.setOnErrorListener { _, _, _ ->
                    Toast.makeText(activity, R.string.teach_video_error, Toast.LENGTH_SHORT).show()
                    true
                }
                media.prepareAsync()
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.teach_video_error, Toast.LENGTH_SHORT).show()
                release()
            }
        }

        fun toggle() {
            val media = player ?: return
            if (!prepared || released) return
            if (media.isPlaying) {
                media.pause()
                btnPlay.visibility = View.VISIBLE
            } else {
                media.start()
                btnPlay.visibility = View.GONE
            }
        }

        fun pause() {
            val media = player ?: return
            if (prepared && !released && media.isPlaying) {
                media.pause()
                btnPlay.visibility = View.VISIBLE
            }
        }

        fun detachSurface() {
            player?.setDisplay(null)
        }

        fun release() {
            if (released) return
            released = true
            prepared = false
            player?.run {
                setOnPreparedListener(null)
                setOnCompletionListener(null)
                setOnErrorListener(null)
                setOnVideoSizeChangedListener(null)
                setDisplay(null)
                release()
            }
            player = null
        }

        private fun applyVideoSize(videoW: Int, videoH: Int) {
            if (videoW <= 0 || videoH <= 0) return
            val width = videoFrame.width.takeIf { it > 0 } ?: return
            val rawHeight = (width * videoH / videoW.toFloat()).toInt().coerceAtLeast(1)
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
            videoFrame.layoutParams = videoFrame.layoutParams.apply {
                height = rawHeight.coerceAtMost(maxHeight)
            }
        }
    }
}
