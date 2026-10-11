package no.descartes.horsel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Holder lydprofilen aktiv i bakgrunnen: på musikk (DynamicsProcessing)
 * og på mikrofonlytting (egen lydbehandling). Kjører som forgrunnstjeneste
 * så Android ikke stopper den når skjermen slås av.
 */
class HearingService : Service() {

    companion object {
        const val ACTION_REFRESH = "no.descartes.horsel.REFRESH"
        const val ACTION_MIC_START = "no.descartes.horsel.MIC_START"
        const val ACTION_MIC_STOP = "no.descartes.horsel.MIC_STOP"
        const val ACTION_SUSPEND = "no.descartes.horsel.SUSPEND"
        const val EXTRA_FG = "fg"
        private const val CHANNEL = "horsel"
        private const val NOTIF_ID = 1

        @Volatile var micRunning = false
        @Volatile var micStatus = ""
        @Volatile var musicStatus = "Av"
        @Volatile var inLevel = 0f
        @Volatile var outLevel = 0f
        @Volatile var latencyMs = 0

        fun send(ctx: Context, action: String, foreground: Boolean) {
            val i = Intent(ctx, HearingService::class.java).setAction(action).putExtra(EXTRA_FG, foreground)
            if (foreground) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var am: AudioManager
    private lateinit var music: MusicEq
    private lateinit var mic: MicEngine
    private val handler = Handler(Looper.getMainLooper())
    private var suspended = false
    private var musicWanted = false
    private var lastPerApp = false

    private val levelPoll = object : Runnable {
        override fun run() {
            inLevel = mic.inLevel
            outLevel = mic.outLevel
            latencyMs = mic.latencyMs
            if (micRunning && !mic.isRunning) {
                micRunning = false
                micStatus = "Lyttingen stoppet uventet."
                applyState()
            }
            musicStatus = if (musicWanted) music.status else "Av"
            handler.postDelayed(this, 150)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (micRunning && AudioDevs.headsetOut(am) == null) {
                mic.stop()
                micRunning = false
                micStatus = "Stoppet fordi øreproppene ble koblet fra."
                applyState()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        music = MusicEq(this)
        mic = MicEngine(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hørselsprofil", NotificationManager.IMPORTANCE_LOW)
        )
        am.registerAudioDeviceCallback(deviceCallback, handler)
        handler.post(levelPoll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Startet med startForegroundService: må gå i forgrunnen umiddelbart.
        if (intent?.getBooleanExtra(EXTRA_FG, false) == true) {
            val type = if (intent.action == ACTION_MIC_START) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            goForeground(type)
        }
        val p = Profile.load(this)
        when (intent?.action) {
            ACTION_MIC_START -> {
                suspended = false
                val err = mic.start(p)
                micRunning = err == null
                micStatus = err ?: "Lytter via mikrofonen på ledningen"
            }
            ACTION_MIC_STOP -> {
                mic.stop()
                micRunning = false
                micStatus = "Stoppet"
            }
            ACTION_SUSPEND -> {
                suspended = true
                mic.stop()
                micRunning = false
            }
            else -> suspended = false
        }
        mic.updateProfile(p)
        applyMusic(p)
        applyState()
        return START_NOT_STICKY
    }

    private fun applyMusic(p: Profile) {
        val want = p.musicOn && !suspended
        if (want && (!musicWanted || p.musicPerApp != lastPerApp)) {
            music.start(p) // første gang, eller bytte mellom «all lyd» og «per app»
        } else if (want) {
            music.update(p)
        } else if (musicWanted) {
            music.stop()
        }
        musicWanted = want
        lastPerApp = p.musicPerApp
        // Mikrofonlyttingen har egen behandling: pause musikkprofilen så lyden ikke behandles to ganger.
        music.setEnabled(!micRunning)
        musicStatus = if (want) music.status else "Av"
    }

    private fun applyState() {
        music.setEnabled(!micRunning)
        var type = 0
        if (micRunning) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (musicWanted && music.isActive) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (type == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            goForeground(type)
        }
    }

    private fun goForeground(type: Int) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, buildNotification(), type)
        else startForeground(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            micRunning -> "Mikrofonlytting er på"
            musicWanted -> "Lydprofilen brukes på musikk"
            else -> "Starter …"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hørselsprofil")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (micRunning) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, HearingService::class.java).setAction(ACTION_MIC_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Stopp mikrofon", stop
                ).build()
            )
        }
        return b.build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(levelPoll)
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        mic.stop()
        micRunning = false
        music.stop()
        musicWanted = false
        musicStatus = "Av"
        super.onDestroy()
    }
}
