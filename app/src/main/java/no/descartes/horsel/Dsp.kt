package no.descartes.horsel

import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

fun dbToLin(db: Float): Float = 10f.pow(db / 20f)

/** Finner kablede ørepropper (3,5 mm eller USB-C). */
object AudioDevs {
    private val OUT_PREF = intArrayOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
    private val IN_PREF = intArrayOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    fun headsetOut(am: AudioManager): AudioDeviceInfo? {
        val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (t in OUT_PREF) devs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    fun headsetIn(am: AudioManager): AudioDeviceInfo? {
        val devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (t in IN_PREF) devs.firstOrNull { it.type == t }?.let { return it }
        return null
    }
}

/**
 * Grafisk equalizer med halv-oktav peaking-filtre (RBJ).
 * Gevinsten i hvert filter løses iterativt slik at den samlede kurven treffer målkurven
 * i senterfrekvensene, ettersom nabofiltre overlapper.
 */
class GraphicEq(private val fs: Double) {
    companion object {
        val CENTERS = DoubleArray(13) { 250.0 * 2.0.pow((it - 1) / 2.0) } // 177 Hz … 11,3 kHz
        private const val Q = 2.871 // halv oktav båndbredde
    }

    private val n = CENTERS.size
    private val b0 = DoubleArray(n) { 1.0 }
    private val b1 = DoubleArray(n)
    private val b2 = DoubleArray(n)
    private val a1 = DoubleArray(n)
    private val a2 = DoubleArray(n)
    private val z1 = DoubleArray(n)
    private val z2 = DoubleArray(n)

    private val lock = Any()
    private val pending = Array(n) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }
    @Volatile private var dirty = false

    private fun coeffs(fc: Double, gDb: Double): DoubleArray {
        val a = 10.0.pow(gDb / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * Q)
        val c = cos(w0)
        val a0 = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / a0, (-2.0 * c) / a0, (1.0 - alpha * a) / a0,
            (-2.0 * c) / a0, (1.0 - alpha / a) / a0
        )
    }

    private fun magDb(c: DoubleArray, f: Double): Double {
        val w = 2.0 * PI * f / fs
        val cw = cos(w); val c2w = cos(2 * w); val sw = sin(w); val s2w = sin(2 * w)
        val nr = c[0] + c[1] * cw + c[2] * c2w
        val ni = -(c[1] * sw + c[2] * s2w)
        val dr = 1.0 + c[3] * cw + c[4] * c2w
        val di = -(c[3] * sw + c[4] * s2w)
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Setter ny målkurve (dB som funksjon av frekvens). Kan kalles fra hvilken som helst tråd. */
    fun setTarget(target: (Double) -> Double) {
        val t = DoubleArray(n) { target(CENTERS[it]) }
        val g = t.copyOf()
        val err = DoubleArray(n)
        repeat(20) {
            val cs = Array(n) { coeffs(CENTERS[it], g[it]) }
            for (j in 0 until n) {
                var sum = 0.0
                for (i in 0 until n) sum += magDb(cs[i], CENTERS[j])
                err[j] = t[j] - sum
            }
            for (j in 0 until n) g[j] = (g[j] + 0.8 * err[j]).coerceIn(-15.0, 40.0)
        }
        synchronized(lock) {
            for (i in 0 until n) coeffs(CENTERS[i], g[i]).copyInto(pending[i])
            dirty = true
        }
    }

    /** Kalles av lydtråden ved starten av hver blokk. */
    fun beginBlock() {
        if (!dirty) return
        synchronized(lock) {
            for (i in 0 until n) {
                val c = pending[i]
                b0[i] = c[0]; b1[i] = c[1]; b2[i] = c[2]; a1[i] = c[3]; a2[i] = c[4]
            }
            dirty = false
        }
    }

    fun process(x: Float): Float {
        var y = x.toDouble()
        for (i in 0 until n) {
            val o = b0[i] * y + z1[i]
            z1[i] = b1[i] * y - a1[i] * o + z2[i]
            z2[i] = b2[i] * y - a2[i] * o
            y = o
        }
        return y.toFloat()
    }
}
