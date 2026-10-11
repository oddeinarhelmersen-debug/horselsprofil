package no.descartes.horsel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.roundToInt

/** Testfrekvensene i hørselstesten (Hz). */
val FREQS = intArrayOf(250, 500, 1000, 1500, 2000, 3000, 4000, 6000, 8000)

const val MAX_GAIN = 30f
const val ADJ_RANGE = 15f

fun freqName(f: Int): String =
    if (f >= 1000) "${(f / 1000.0).toString().removeSuffix(".0").replace('.', ',')} kHz" else "$f Hz"

/** En lagret hørselstest. */
class SavedTest(
    val name: String,
    val time: Long,
    val thrL: FloatArray,
    val thrR: FloatArray,
    val gL: FloatArray,
    val gR: FloatArray,
    val volumePct: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("time", time).put("volumePct", volumePct)
        .put("thrL", JSONArray(thrL.map { it.toDouble() })).put("thrR", JSONArray(thrR.map { it.toDouble() }))
        .put("gL", JSONArray(gL.map { it.toDouble() })).put("gR", JSONArray(gR.map { it.toDouble() }))

    companion object {
        private fun arr(o: JSONObject, k: String): FloatArray? {
            val a = o.optJSONArray(k) ?: return null
            if (a.length() != FREQS.size) return null
            return FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }

        fun fromJson(o: JSONObject): SavedTest? {
            val thrL = arr(o, "thrL") ?: return null
            val thrR = arr(o, "thrR") ?: return null
            return SavedTest(
                o.optString("name", "Test"), o.optLong("time", 0L), thrL, thrR,
                arr(o, "gL") ?: gainsFrom(thrL, thrR).first,
                arr(o, "gR") ?: gainsFrom(thrL, thrR).second,
                o.optInt("volumePct", 0)
            )
        }

        /** Forsterkning fra tersklene: halvparten av avstanden til det beste punktet (halv-gevinst-regelen). */
        fun gainsFrom(l: FloatArray, r: FloatArray): Pair<FloatArray, FloatArray> {
            val best = minOf(l.minOrNull() ?: 0f, r.minOrNull() ?: 0f)
            fun g(t: Float) = ((t - best) * 0.5f).roundToInt().toFloat().coerceIn(0f, MAX_GAIN)
            return Pair(FloatArray(l.size) { g(l[it]) }, FloatArray(r.size) { g(r[it]) })
        }
    }
}

/**
 * Hørselsprofilen. Kanal 0 = venstre øre, kanal 1 = høyre øre.
 * gL/gR er forsterkningen fra testen (kan finjusteres per øre), adj er den felles
 * justeringskurven som legges oppå for begge ørene.
 */
class Profile {
    var thrL: FloatArray? = null
    var thrR: FloatArray? = null
    var gL = FloatArray(FREQS.size)
    var gR = FloatArray(FREQS.size)
    var adj = FloatArray(FREQS.size)
    var testVolumePct = 0
    var tests = mutableListOf<SavedTest>()
    var activeTest = -1

    var musicOn = false
    var musicStrength = 60
    var musicBass = 4
    var musicPerApp = false

    var micGain = 0
    var micOut = 0
    var micStrength = 100
    var micComp = true
    var micNs = false
    var micSource = 0 // 0 = mikrofonen på ledningen, 1 = mikrofonen i telefonen

    fun gains(ch: Int) = if (ch == 0) gL else gR

    /** Samlet forsterkning for et øre: testen + felles justering. */
    fun eff(ch: Int): FloatArray {
        val g = if (ch == 0) gL else gR
        return FloatArray(FREQS.size) { (g[it] + adj[it]).coerceIn(-10f, MAX_GAIN + ADJ_RANGE) }
    }

    fun applyTest(t: SavedTest) {
        thrL = t.thrL.copyOf(); thrR = t.thrR.copyOf()
        gL = t.gL.copyOf(); gR = t.gR.copyOf()
        testVolumePct = t.volumePct
    }

    fun save(ctx: Context) {
        val tj = JSONArray(); tests.forEach { tj.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("version", 2)
            .putString("thrL", thrL?.joinToString(","))
            .putString("thrR", thrR?.joinToString(","))
            .putString("gL", gL.joinToString(","))
            .putString("gR", gR.joinToString(","))
            .putString("adj", adj.joinToString(","))
            .putString("tests", tj.toString())
            .putInt("activeTest", activeTest)
            .putInt("testVolumePct", testVolumePct)
            .putBoolean("musicOn", musicOn)
            .putInt("musicStrength", musicStrength)
            .putInt("musicBass", musicBass)
            .putBoolean("musicPerApp", musicPerApp)
            .putInt("micGain", micGain)
            .putInt("micOut", micOut)
            .putInt("micStrength", micStrength)
            .putBoolean("micComp", micComp)
            .putBoolean("micNs", micNs)
            .putInt("micSource", micSource)
            .apply()
    }

    /** Hele profilen som tekst, for sikkerhetskopi. */
    fun exportJson(): String {
        val tj = JSONArray(); tests.forEach { tj.put(it.toJson()) }
        return JSONObject().put("app", "Hørselsprofil").put("format", 1)
            .put("adj", JSONArray(adj.map { it.toDouble() }))
            .put("tests", tj).put("activeTest", activeTest).toString(2)
    }

    /** Leser inn en sikkerhetskopi. Returnerer antall tester som ble lagt til, eller null ved feil. */
    fun importJson(text: String): Int? = try {
        val o = JSONObject(text.trim())
        val ta = o.getJSONArray("tests")
        var n = 0
        for (i in 0 until ta.length()) {
            val t = SavedTest.fromJson(ta.getJSONObject(i)) ?: continue
            if (tests.none { it.time == t.time && it.name == t.name }) { tests.add(t); n++ }
        }
        o.optJSONArray("adj")?.let { a ->
            if (a.length() == FREQS.size) adj = FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
        tests.sortBy { it.time }
        if (activeTest < 0 && tests.isNotEmpty()) { activeTest = tests.size - 1; applyTest(tests[activeTest]) }
        n
    } catch (e: Exception) { null }

    companion object {
        private const val PREFS = "horselsprofil"

        private fun parse(s: String?): FloatArray? {
            if (s.isNullOrBlank()) return null
            val parts = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
            return if (parts.size == FREQS.size) parts.toFloatArray() else null
        }

        fun load(ctx: Context): Profile {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = sp.getInt("version", 1)
            return Profile().apply {
                thrL = parse(sp.getString("thrL", null))
                thrR = parse(sp.getString("thrR", null))
                parse(sp.getString("gL", null))?.let { gL = it }
                parse(sp.getString("gR", null))?.let { gR = it }
                parse(sp.getString("adj", null))?.let { adj = it }
                try {
                    val ta = JSONArray(sp.getString("tests", "[]"))
                    for (i in 0 until ta.length()) SavedTest.fromJson(ta.getJSONObject(i))?.let { tests.add(it) }
                } catch (_: Exception) {}
                activeTest = sp.getInt("activeTest", -1).coerceAtMost(tests.size - 1)
                testVolumePct = sp.getInt("testVolumePct", 0)
                musicOn = sp.getBoolean("musicOn", false)
                musicStrength = sp.getInt("musicStrength", 60)
                musicBass = sp.getInt("musicBass", 4)
                musicPerApp = sp.getBoolean("musicPerApp", false)
                micGain = sp.getInt("micGain", 0)
                micOut = sp.getInt("micOut", 0)
                micStrength = sp.getInt("micStrength", 100)
                micComp = sp.getBoolean("micComp", true)
                micNs = sp.getBoolean("micNs", false)
                micSource = sp.getInt("micSource", 0)
                if (version < 2) {
                    // Eldre versjon: ta vare på testen som første lagrede test, og demp musikkstyrken.
                    val l = thrL; val r = thrR
                    if (l != null && r != null && tests.isEmpty()) {
                        tests.add(SavedTest("Første test", System.currentTimeMillis(), l, r, gL.copyOf(), gR.copyOf(), testVolumePct))
                        activeTest = 0
                    }
                    musicStrength = 60
                }
            }
        }

        /**
         * Forsterkning (dB) ved vilkårlig frekvens, lineært interpolert på log-frekvens.
         * Under 250 Hz faller den til 0 ved 125 Hz, over 8 kHz til 0 ved 16 kHz,
         * så vi ikke forsterker dur og sus utenfor det som er målt.
         */
        fun interp(g: FloatArray, f: Double): Double {
            val first = FREQS.first().toDouble()
            val last = FREQS.last().toDouble()
            if (f <= first) {
                val k = (ln(f / (first / 2)) / ln(2.0)).coerceIn(0.0, 1.0)
                return g.first() * k
            }
            if (f >= last) {
                val k = (1.0 - ln(f / last) / ln(2.0)).coerceIn(0.0, 1.0)
                return g.last() * k
            }
            for (i in 0 until FREQS.size - 1) {
                val f0 = FREQS[i].toDouble()
                val f1 = FREQS[i + 1].toDouble()
                if (f < f1) {
                    val t = ln(f / f0) / ln(f1 / f0)
                    return g[i] + (g[i + 1] - g[i]) * t
                }
            }
            return g.last().toDouble()
        }

        /** Vekt for basskontrollen: 1 under 150 Hz, faller til 0 ved 400 Hz. */
        fun bassWeight(f: Double): Double = ((ln(400.0 / f)) / ln(400.0 / 150.0)).coerceIn(0.0, 1.0)
    }
}
