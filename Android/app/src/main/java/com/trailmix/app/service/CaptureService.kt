package com.trailmix.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.trailmix.app.MainActivity
import com.trailmix.app.R
import com.trailmix.app.data.speech.CaptureEngine
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.ui.capture.CaptureUiState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps a capture session alive while the user switches to another app (the
 * meeting, a video, …) — required by Android for mic access in the background
 * and for MediaProjection-based device-audio capture.
 *
 * CAP-12: also owns the *visible* side of "a capture is running" — a live-updating
 * notification with Pause/Resume + Stop actions and a meeting-name/elapsed-time line
 * (the ongoing capture channel stays silent, per the original design intent — this is
 * information, not an alert), plus two one-shot, actually-alerting notifications on a
 * separate channel: a "still recording?" nudge after [CaptureSessionManager]'s bump
 * timer fires, and a "call ended" prompt when the phone call this capture started
 * during appears to have ended. Both nudges are driven by [CaptureSessionManager] so
 * they fire even when the app isn't foregrounded — the whole point is that a silent
 * background capture is easy to forget about.
 */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var engine: CaptureEngine
    @Inject lateinit var sessionManager: CaptureSessionManager

    /** Service-lifetime scope for the notification-driving collectors below — created
     * in [onCreate], torn down in [onDestroy]. Independent of [sessionManager]'s own
     * scope, which outlives any single service instance. */
    private var serviceScope: CoroutineScope? = null
    private var observersStarted = false

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildOngoingNotification(sessionManager.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                startObservers()
            }

            ACTION_ATTACH_PROJECTION -> {
                // Upgrade the service type first — Android 14+ requires an
                // active mediaProjection-typed FGS before the token is used.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildOngoingNotification(sessionManager.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? =
                    IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (resultData != null) {
                    engine.attachDeviceAudio(resultCode, resultData)
                }
                startObservers()
            }

            // CAP-12: notification-driven controls. All three route through the same
            // CaptureSessionManager methods the in-app UI uses — no duplicated logic.
            ACTION_PAUSE -> sessionManager.pause()
            ACTION_RESUME -> sessionManager.resume()
            // Fire-and-forget: there's no NavController to hand the merged note id to
            // from a notification tap, so it just lands on Home for the user to open
            // later — the same outcome as tapping End & Merge and walking away.
            ACTION_STOP_AND_SAVE -> sessionManager.endAndMerge {}

            ACTION_DISMISS_REMINDER -> notificationManager().cancel(REMINDER_NOTIFICATION_ID)
            ACTION_DISMISS_CALL_ENDED -> {
                notificationManager().cancel(CALL_ENDED_NOTIFICATION_ID)
                sessionManager.consumeCallEndedPrompt()
            }

            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager().cancel(REMINDER_NOTIFICATION_ID)
                notificationManager().cancel(CALL_ENDED_NOTIFICATION_ID)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observersStarted = false
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    /** Wired once per foreground-service lifetime: keeps the ongoing notification's
     * timer/meeting-title/pause state in sync, and turns [CaptureSessionManager]'s two
     * nudge flows into the attention-getting one-shot notifications. */
    private fun startObservers() {
        if (observersStarted) return
        observersStarted = true
        val scope = serviceScope ?: return

        sessionManager.state
            // CAP-13: elapsedLabel is deliberately NOT compared — the notification runs a
            // native chronometer while recording, so a per-second re-post would be pure
            // churn. Pausing freezes the label, and `paused` flipping already triggers here.
            .distinctUntilChanged { old, new ->
                old.meetingTitle == new.meetingTitle &&
                    old.noteTitle == new.noteTitle &&
                    old.paused == new.paused &&
                    old.merging == new.merging &&
                    (old.recording || old.paused || old.merging) == (new.recording || new.paused || new.merging)
            }
            .onEach { state ->
                // A stray final "everything false" emission (right after the session
                // actually ends) shouldn't resurrect the notification — ACTION_STOP
                // already tore it down.
                if (state.recording || state.paused || state.merging) {
                    notificationManager().notify(NOTIFICATION_ID, buildOngoingNotification(state))
                }
            }
            .launchIn(scope)

        sessionManager.bumpNudge
            .onEach { postReminderNotification() }
            .launchIn(scope)

        sessionManager.callEndedEvents
            .onEach { postCallEndedNotification() }
            .launchIn(scope)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Active capture",
                    // CAP-13: IMPORTANCE_LOW, not MIN. MIN gets collapsed into the shade's
                    // silent section with its actions hidden, which defeats the point of a
                    // notification whose whole job is "you are still recording — here is
                    // Pause/Stop". LOW is still silent and never a heads-up; it just stays
                    // visible and expandable. Importance can't be raised on an existing
                    // channel (the user owns it once created), which is why this is a NEW
                    // channel id and the old one is deleted below.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while TrailMix is recording, with pause and stop controls."
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        // CAP-12: deliberately a *separate*, actually-alerting channel — the ongoing
        // capture channel above stays silent by design, but a nudge that's easy to miss
        // defeats its own purpose.
        if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL_ID,
                    "Capture reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Nudges when a capture has run a long time, or when a " +
                        "call it was tracking has ended."
                },
            )
        }
    }

    private fun tapIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, CaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The at-a-glance answer to "is this thing still recording, and into what?" (CAP-13).
     *
     * Title is the **note** being written into, not the calendar event — those differ, and
     * on a resumed capture the note has a real name worth showing. While recording, the
     * elapsed time is a native **chronometer**: Android advances it itself, so the timer is
     * smooth without re-posting the notification once a second. A paused session freezes it
     * to static text, because a chronometer that keeps counting while paused would be a lie.
     */
    private fun buildOngoingNotification(state: CaptureUiState): Notification {
        ensureChannels()
        val recording = !state.merging && !state.paused
        val status = when {
            state.merging -> "Merging on-device…"
            state.paused -> "Paused · ${state.elapsedLabel}"
            else -> "Recording · transcribing on-device"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(state.noteTitle)
            .setContentText(status)
            .setSubText(state.meetingTitle?.takeIf { it != state.noteTitle })
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(recording)
            .setUsesChronometer(recording)
            .setWhen(System.currentTimeMillis() - state.elapsedMs)
            .setContentIntent(tapIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // No point offering Pause/Stop mid-merge — there's nothing left to control.
        if (!state.merging) {
            if (state.paused) {
                builder.addAction(R.drawable.ic_stat_play, "Resume", actionIntent(ACTION_RESUME, 1))
            } else {
                builder.addAction(R.drawable.ic_stat_pause, "Pause", actionIntent(ACTION_PAUSE, 1))
            }
            builder.addAction(R.drawable.ic_stat_stop, "Stop", actionIntent(ACTION_STOP_AND_SAVE, 2))
        }
        return builder.build()
    }

    private fun postReminderNotification() {
        ensureChannels()
        val state = sessionManager.state.value
        val subject = state.meetingTitle?.let { "\"$it\"" } ?: "This capture"
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("Still recording?")
            .setContentText("$subject has been running for ${state.elapsedLabel} — still using it?")
            .setAutoCancel(true)
            .setContentIntent(tapIntent())
            .addAction(R.drawable.ic_stat_play, "Still here", actionIntent(ACTION_DISMISS_REMINDER, 3))
            .addAction(R.drawable.ic_stat_stop, "Stop & save", actionIntent(ACTION_STOP_AND_SAVE, 4))
            .build()
        notificationManager().notify(REMINDER_NOTIFICATION_ID, notification)
    }

    private fun postCallEndedNotification() {
        ensureChannels()
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("Call ended")
            .setContentText("Finish transcribing this note now, or keep it running and finish later.")
            .setAutoCancel(true)
            .setContentIntent(tapIntent())
            .addAction(R.drawable.ic_stat_stop, "Finish now", actionIntent(ACTION_STOP_AND_SAVE, 5))
            .addAction(R.drawable.ic_stat_play, "Finish later", actionIntent(ACTION_DISMISS_CALL_ENDED, 6))
            .build()
        notificationManager().notify(CALL_ENDED_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "capture_v2"

        /** CAP-12's IMPORTANCE_MIN channel, replaced by [CHANNEL_ID] in CAP-13. */
        private const val LEGACY_CHANNEL_ID = "capture"
        private const val REMINDER_CHANNEL_ID = "capture_reminder"
        private const val NOTIFICATION_ID = 1
        private const val REMINDER_NOTIFICATION_ID = 2
        private const val CALL_ENDED_NOTIFICATION_ID = 3
        private const val ACTION_START = "com.trailmix.app.capture.START"
        private const val ACTION_ATTACH_PROJECTION = "com.trailmix.app.capture.ATTACH_PROJECTION"
        private const val ACTION_STOP = "com.trailmix.app.capture.STOP"
        private const val ACTION_PAUSE = "com.trailmix.app.capture.PAUSE"
        private const val ACTION_RESUME = "com.trailmix.app.capture.RESUME"
        private const val ACTION_STOP_AND_SAVE = "com.trailmix.app.capture.STOP_AND_SAVE"
        private const val ACTION_DISMISS_REMINDER = "com.trailmix.app.capture.DISMISS_REMINDER"
        private const val ACTION_DISMISS_CALL_ENDED = "com.trailmix.app.capture.DISMISS_CALL_ENDED"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java).setAction(ACTION_START),
            )
        }

        fun attachProjection(context: Context, resultCode: Int, resultData: Intent) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_ATTACH_PROJECTION)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, resultData),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
