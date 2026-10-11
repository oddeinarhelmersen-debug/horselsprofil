package no.descartes.horsel

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val FS = 48000

/* ------------------------------------------------------------------ */
/* Testtone: korte pip i ett øre                                       */
/* ------------------------------------------------------------------ */
class TonePlayer {
    @Volatile var freq = 1000.0
    @Volatile var levelDb = -40.0
    @Volatile var ear = 1 // 0 = venstre, 1 = høyre
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(outDevice: android.media.AudioDeviceInfo?) {
        if (running) return
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val fmt = AudioFormat.Builder().setSampleRate(FS)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val minBuf = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minBuf, 4096 * 8)).setTransferMode(AudioTrack.MODE_STREAM).build()
            if (outDevice != null) track.setPreferredDevice(outDevice)
            track.play()
            val block = 480
            val buf = FloatArray(block * 2)
            var phase = 0.0
            var t = 0L
            val period = (0.7 * FS).toLong()
            val on = (0.25 * FS).toLong()
            val ramp = (0.015 * FS).toLong()
            while (running) {
                val amp = 10.0.pow(levelDb / 20.0)
                val step = 2.0 * PI * freq / FS
                val ch = ear
                for (i in 0 until block) {
                    val p = t % period
                    val env = when {
                        p >= on -> 0.0
                        p < ramp -> p.toDouble() / ramp
                        p > on - ramp -> (on - p).toDouble() / ramp
                        else -> 1.0
                    }
                    val s = (sin(phase) * amp * env).toFloat()
                    phase += step
                    if (phase > 2 * PI) phase -= 2 * PI
                    buf[2 * i] = if (ch == 0) s else 0f
                    buf[2 * i + 1] = if (ch == 1) s else 0f
                    t++
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
            track.stop()
            track.release()
        }, "tone")
        thread!!.start()
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }
}

/* ------------------------------------------------------------------ */
/* Mikrofonlytting: mikrofon på ledningen → EQ per øre → ørepropper    */
/* ------------------------------------------------------------------ */
class MicEngine(private val ctx: Context) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val eqL = GraphicEq(FS.toDouble())
    private val eqR = GraphicEq(FS.toDouble())
    private var ns: NoiseSuppressor? = null

    @Volatile private var inGain = 1f
    @Volatile private var outGain = 1f
    @Volatile private var compOn = true
    @Volatile private var nsOn = false

    @Volatile var inLevel = 0f
    @Volatile var outLevel = 0f
    @Volatile var latencyMs = 0

    val isRunning get() = running

    fun updateProfile(p: Profile) {
        val s = p.micStrength / 100.0
        val l = p.eff(0); val r = p.eff(1)
        eqL.setTarget { f -> Profile.interp(l, f) * s }
        eqR.setTarget { f -> Profile.interp(r, f) * s }
        inGain = dbToLin(p.micGain.toFloat())
        outGain = dbToLin(p.micOut.toFloat())
        compOn = p.micComp
        nsOn = p.micNs
        try { ns?.enabled = p.micNs } catch (_: Exception) {}
    }

    /** Returnerer en feilmelding, eller null når lyttingen er startet. */
    @SuppressLint("MissingPermission")
    fun start(p: Profile): String? {
        if (running) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outDev = AudioDevs.headsetOut(am)
            ?: return "Fant ingen kablede ørepropper. Koble til EarPods først."
        val inDev = if (p.micSource == 1) AudioDevs.phoneMic(am)
            ?: return "Fant ikke mikrofonen i telefonen."
        else AudioDevs.headsetIn(am)
            ?: return "Fant ikke mikrofonen på ledningen. Sjekk at pluggen sitter helt inne, eller velg mikrofonen i telefonen."
        updateProfile(p)

        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
        val n = burst.coerceIn(64, 480)

        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

        val inFmt = AudioFormat.Builder().setSampleRate(FS).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minIn = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord.Builder().setAudioSource(source).setAudioFormat(inFmt)
                .setBufferSizeInBytes(max(minIn, n * 4 * 4)).build()
        } catch (e: Exception) {
            return "Kunne ikke åpne mikrofonen: ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return "Mikrofonen kunne ikke startes."
        }
        rec.setPreferredDevice(inDev)
        ns = if (NoiseSuppressor.isAvailable()) try { NoiseSuppressor.create(rec.audioSessionId) } catch (_: Exception) { null } else null
        try { ns?.enabled = nsOn } catch (_: Exception) {}

        val outFmt = AudioFormat.Builder().setSampleRate(FS).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val minOut = AudioTrack.getMinBufferSize(FS, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(outFmt)
                .setBufferSizeInBytes(minOut).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        } catch (e: Exception) {
            ns?.release(); rec.release()
            return "Kunne ikke åpne lydutgangen: ${e.message}"
        }
        track.setPreferredDevice(outDev)
        try { track.bufferSizeInFrames = n * 2 } catch (_: Exception) {}

        running = true
        thread = Thread({ loop(rec, track, n) }, "mic-loop").also { it.start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        inLevel = 0f; outLevel = 0f
    }

    private fun loop(rec: AudioRecord, track: AudioTrack, n: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val inBuf = FloatArray(n)
        val out = FloatArray(n * 2)

        // Tidskonstanter
        val aC = exp(-1.0 / (FS * 0.005)).toFloat(); val rC = exp(-1.0 / (FS * 0.15)).toFloat()
        val rLim = exp(-1.0 / (FS * 0.05)).toFloat()
        val aN = exp(-1.0 / (FS * 0.01)).toFloat(); val rN = exp(-1.0 / (FS * 0.2)).toFloat()
        var envC = 0f; var envLim = 0f; var envN = 0f; var gN = 1f
        val compThr = -30f; val compRatio = 3f
        val limThr = 0.89f // −1 dBFS
        val gateThr = 0.001f // −60 dBFS

        try {
            rec.startRecording()
            track.play()
            track.write(FloatArray(n * 2), 0, n * 2, AudioTrack.WRITE_BLOCKING)
            latencyMs = ((track.bufferSizeInFrames + 2 * n) * 1000L / FS).toInt() + 10

            while (running) {
                val r = rec.read(inBuf, 0, n, AudioRecord.READ_BLOCKING)
                if (r < 0) break
                if (r == 0) continue
                eqL.beginBlock(); eqR.beginBlock()
                val ig = inGain; val og = outGain; val comp = compOn; val gate = nsOn
                var sumIn = 0f; var sumOut = 0f
                for (i in 0 until r) {
                    var x = inBuf[i] * ig
                    sumIn += x * x
                    if (gate) {
                        // Mild nedadgående ekspander: demper sus i stillhet
                        val a = abs(x)
                        envN = if (a > envN) aN * envN + (1 - aN) * a else rN * envN + (1 - rN) * a
                        val target = if (envN < gateThr) max(0.15f, envN / gateThr) else 1f
                        gN += (target - gN) * 0.002f
                        x *= gN
                    }
                    var l = eqL.process(x)
                    var rr = eqR.process(x)
                    if (comp) {
                        val pk = max(abs(l), abs(rr))
                        envC = if (pk > envC) aC * envC + (1 - aC) * pk else rC * envC + (1 - rC) * pk
                        val db = 20f * log10(envC + 1e-9f)
                        if (db > compThr) {
                            val g = dbToLin(-(db - compThr) * (1f - 1f / compRatio))
                            l *= g; rr *= g
                        }
                    }
                    l *= og; rr *= og
                    val pk2 = max(abs(l), abs(rr))
                    envLim = if (pk2 > envLim) pk2 else rLim * envLim + (1 - rLim) * pk2
                    if (envLim > limThr) {
                        val g = limThr / envLim
                        l *= g; rr *= g
                    }
                    l = l.coerceIn(-0.98f, 0.98f)
                    rr = rr.coerceIn(-0.98f, 0.98f)
                    out[2 * i] = l
                    out[2 * i + 1] = rr
                    sumOut += 0.5f * (l * l + rr * rr)
                }
                inLevel = sqrt(sumIn / r)
                outLevel = sqrt(sumOut / r)
                track.write(out, 0, r * 2, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: Exception) {
        } finally {
            running = false
            try { rec.stop() } catch (_: Exception) {}
            try { track.stop() } catch (_: Exception) {}
            rec.release(); track.release()
            try { ns?.release() } catch (_: Exception) {}
            ns = null
        }
    }
}

/* ------------------------------------------------------------------ */
/* Musikk: DynamicsProcessing på all avspilling, eller per app         */
/* ------------------------------------------------------------------ */
class MusicEq(private val ctx: Context) {
    companion object {
        /** Øvre grense for hvert bånd: halv-oktaver 125 Hz … 16 kHz, pluss toppbånd til 20 kHz. */
        val CUTOFFS: FloatArray = FloatArray(16) { k -> if (k == 15) 20000f else (125.0 * 2.0.pow(k / 2.0)).toFloat() }
        fun center(k: Int): Double = if (k == 0) 90.0 else sqrt(CUTOFFS[k - 1].toDouble() * CUTOFFS[k])
    }

    private val effects = HashMap<Int, DynamicsProcessing>()
    private var profile: Profile? = null
    private var receiverOn = false
    private var enabled = true

    var status = ""
        private set
    val isActive get() = effects.isNotEmpty() || receiverOn

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (!effects.containsKey(session)) attach(session)
                    status = "Per app: virker på ${effects.size} avspilling(er) nå"
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    effects.remove(session)?.release()
                    status = "Per app: virker på ${effects.size} avspilling(er) nå"
                }
            }
        }
    }

    fun start(p: Profile) {
        stop()
        profile = p
        if (!p.musicPerApp) {
            if (attach(0)) {
                status = "På for all lyd"
                return
            }
            status = "Telefonen avviste «all lyd». Bruker per-app i stedet."
        } else {
            status = "Per app: venter på at en musikkapp starter avspilling"
        }
        val f = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        receiverOn = true
    }

    fun stop() {
        if (receiverOn) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverOn = false
        }
        effects.values.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        status = "Av"
    }

    fun update(p: Profile) {
        profile = p
        effects.values.forEach { dp ->
            try {
                val (gains, headroom) = bandGains(p)
                for (ch in 0..1) for (k in CUTOFFS.indices)
                    dp.setPreEqBandByChannelIndex(ch, k, DynamicsProcessing.EqBand(true, CUTOFFS[k], gains[ch][k]))
                dp.setInputGainAllChannelsTo(headroom)
            } catch (_: Exception) {}
        }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        effects.values.forEach { try { it.enabled = on } catch (_: Exception) {} }
    }

    private fun bandGains(p: Profile): Pair<Array<FloatArray>, Float> {
        val s = p.musicStrength / 100.0
        val g = Array(2) { ch ->
            val e = p.eff(ch)
            FloatArray(CUTOFFS.size) { k ->
                val f = center(k)
                (Profile.interp(e, f) * s + p.musicBass * Profile.bassWeight(f)).toFloat()
            }
        }
        val maxG = max(g[0].maxOrNull() ?: 0f, g[1].maxOrNull() ?: 0f)
        // Litt demping gir plass til forsterkningen; begrenseren tar resten.
        val headroom = -minOf(8f, maxG * 0.35f)
        return Pair(g, headroom)
    }

    private fun attach(session: Int): Boolean {
        val p = profile ?: return false
        return try {
            val (gains, headroom) = bandGains(p)
            val b = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, CUTOFFS.size, false, 0, false, 0, true
            )
            for (ch in 0..1) {
                val eq = DynamicsProcessing.Eq(true, true, CUTOFFS.size)
                for (k in CUTOFFS.indices) eq.setBand(k, DynamicsProcessing.EqBand(true, CUTOFFS[k], gains[ch][k]))
                b.setPreEqByChannelIndex(ch, eq)
            }
            b.setInputGainAllChannelsTo(headroom)
            b.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
            val dp = DynamicsProcessing(Int.MAX_VALUE, session, b.build())
            dp.enabled = enabled
            effects[session] = dp
            true
        } catch (e: Exception) {
            false
        }
    }
}
