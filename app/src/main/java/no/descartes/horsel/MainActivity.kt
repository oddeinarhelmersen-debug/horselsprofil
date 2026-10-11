package no.descartes.horsel

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.log10
import kotlin.math.roundToInt

object Colors {
    val BG = Color.parseColor("#F2F5F3")
    val CARD = Color.parseColor("#FFFFFF")
    val FG = Color.parseColor("#16201E")
    val MUTED = Color.parseColor("#56645F")
    val LINE = Color.parseColor("#D3DCD9")
    val ACCENT = Color.parseColor("#0D6B63")
    val RIGHT = Color.parseColor("#C3313B")
    val LEFT = Color.parseColor("#2552B5")
    val WARN = Color.parseColor("#A45A00")
}

class MainActivity : Activity() {

    private lateinit var p: Profile
    private lateinit var am: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    // Visninger som oppdateres
    private lateinit var headsetText: TextView
    private lateinit var chart: ProfileView
    private lateinit var bandBox: LinearLayout
    private lateinit var earButtons: List<Button>
    private lateinit var musicSwitch: Switch
    private lateinit var musicStatus: TextView
    private lateinit var micButton: Button
    private lateinit var micStatus: TextView
    private lateinit var meterIn: ProgressBar
    private lateinit var meterOut: ProgressBar
    private lateinit var latencyText: TextView
    private lateinit var testIntro: LinearLayout
    private lateinit var testPanel: LinearLayout
    private lateinit var testNow: TextView
    private lateinit var testLevelText: TextView
    private lateinit var testProgress: TextView
    private lateinit var testInfo: TextView

    private var editEar = 2 // 0 venstre, 1 høyre, 2 begge
    private var pendingMicStart = false

    // Hørselstest
    private val tone = TonePlayer()
    private val testOrder = intArrayOf(2, 3, 4, 5, 6, 7, 8, 1, 0) // 1 kHz først, så opp, så ned
    private var testSeq = listOf<Pair<Int, Int>>()
    private var testPos = 0
    private var testLevel = -40
    private var thrL = FloatArray(FREQS.size)
    private var thrR = FloatArray(FREQS.size)
    private var savedVolume = -1

    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        p = Profile.load(this)
        window.statusBarColor = Colors.BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildUi())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        if (testPanel.visibility == View.VISIBLE) cancelTest()
    }

    private val poll = object : Runnable {
        override fun run() {
            val hs = AudioDevs.headsetOut(am)
            headsetText.text = if (hs != null) "✓ Ørepropper tilkoblet" else "Ingen kablede ørepropper funnet. Koble til EarPods."
            headsetText.setTextColor(if (hs != null) Colors.ACCENT else Colors.WARN)
            val running = HearingService.micRunning
            micButton.text = if (running) "Stopp mikrofonlytting" else "Start mikrofonlytting"
            styleButton(micButton, primary = !running)
            micStatus.text = HearingService.micStatus
            meterIn.progress = levelPct(HearingService.inLevel)
            meterOut.progress = levelPct(HearingService.outLevel)
            latencyText.text = if (running && HearingService.latencyMs > 0)
                "Forsinkelse i appen: ca. ${HearingService.latencyMs} ms (pluss litt i telefonens lydsystem)" else ""
            musicStatus.text = "Status: " + HearingService.musicStatus
            handler.postDelayed(this, 200)
        }
    }

    private fun levelPct(rms: Float): Int {
        if (rms <= 0f) return 0
        val db = 20 * log10(rms)
        return ((db + 60f) / 60f * 100f).coerceIn(0f, 100f).roundToInt()
    }

    /* ---------------- UI-bygging ---------------- */

    private fun text(s: String, size: Float = 17f, bold: Boolean = false, color: Int = Colors.FG) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    private fun styleButton(b: Button, primary: Boolean) {
        b.backgroundTintList = ColorStateList.valueOf(if (primary) Colors.ACCENT else Color.parseColor("#E3EAE8"))
        b.setTextColor(if (primary) Color.WHITE else Colors.ACCENT)
    }

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 17f; isAllCaps = false
        minHeight = px(56)
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 10, w: Int = LinearLayout.LayoutParams.MATCH_PARENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { topMargin = px(top) }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(8)
            })
        }
    }

    private fun card(title: String, step: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(18))
            background = GradientDrawable().apply {
                setColor(Colors.CARD); cornerRadius = 12 * d; setStroke(px(1), Colors.LINE)
            }
        }
        if (step != null) c.addView(text(step.uppercase(), 13f, true, Colors.MUTED).apply { letterSpacing = 0.08f })
        c.addView(text(title, 22f, true), lp(2))
        return c
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val t = text("$label: ${fmt(value)}", 17f, true)
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            progressTintList = ColorStateList.valueOf(Colors.ACCENT)
            thumbTintList = ColorStateList.valueOf(Colors.ACCENT)
            minimumHeight = px(44)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, prog: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = prog + min
                    t.text = "$label: ${fmt(v)}"
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(t); addView(sb, lp(4))
        }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 17f; isChecked = checked; setTextColor(Colors.FG)
        minHeight = px(48)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Colors.ACCENT, Color.parseColor("#B8C2BF")))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun meter() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = ColorStateList.valueOf(Colors.ACCENT)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(20), px(16), px(40))
        }
        root.addView(text("Hørselsprofil", 30f, true))
        root.addView(text("Test hørselen på hvert øre, og bruk resultatet på musikk og når du lytter med mikrofonen på ledningen.", 17f, color = Colors.MUTED), lp(4))
        headsetText = text("", 17f, true)
        root.addView(headsetText, lp(12))

        /* Hørselstest */
        val test = card("Hørselstest", "Trinn 1")
        testIntro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        testIntro.addView(text("Sitt i et stille rom med EarPods i ørene. Du hører korte pip i ett øre om gangen.\n\nFor hvert pip: trykk «Svakere» til pipet forsvinner, og så «Sterkere» til det så vidt høres igjen. Trykk deretter «Hører så vidt». Det tar 5–10 minutter.", 17f), lp(8))
        testInfo = text("", 15f, color = Colors.MUTED)
        testIntro.addView(testInfo, lp(8))
        testIntro.addView(button("Start hørselstest", true) { startTest() }, lp(12))
        test.addView(testIntro)

        testPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        testNow = text("", 28f, true).apply { gravity = Gravity.CENTER }
        testLevelText = text("", 20f).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        testProgress = text("", 15f, color = Colors.MUTED).apply { gravity = Gravity.CENTER }
        testPanel.addView(testNow, lp(10))
        testPanel.addView(testLevelText, lp(6))
        testPanel.addView(row(
            button("Svakere −5") { changeLevel(-5) },
            button("Sterkere +5") { changeLevel(5) }
        ), lp(12))
        testPanel.addView(row(
            button("Svakere −1") { changeLevel(-1) },
            button("Sterkere +1") { changeLevel(1) }
        ), lp(4))
        testPanel.addView(button("Hører så vidt – neste tone", true) { nextTone(false) }, lp(14))
        testPanel.addView(button("Hører ikke tonen i det hele tatt") { nextTone(true) }, lp(6))
        testPanel.addView(testProgress, lp(10))
        testPanel.addView(button("Avbryt testen") { cancelTest() }, lp(10))
        test.addView(testPanel)
        root.addView(test, lp(14))

        /* Profil */
        val prof = card("Din lydprofil", "Trinn 2")
        chart = ProfileView(this)
        prof.addView(chart, lp(10))
        prof.addView(LinearLayout(this).apply {
            addView(text("○ Høyre øre", 15f, true, Colors.RIGHT))
            addView(text("× Venstre øre", 15f, true, Colors.LEFT), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
            addView(text("forsterkning i dB", 15f, color = Colors.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
        }, lp(4))
        prof.addView(text("Finjuster for:", 17f, true), lp(16))
        val bBoth = button("Begge") { setEditEar(2) }
        val bL = button("Venstre") { setEditEar(0) }
        val bR = button("Høyre") { setEditEar(1) }
        earButtons = listOf(bL, bR, bBoth)
        prof.addView(row(bBoth, bL, bR), lp(6))
        bandBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        prof.addView(bandBox, lp(8))
        prof.addView(button("Nullstill all forsterkning") {
            p.gL = FloatArray(FREQS.size); p.gR = FloatArray(FREQS.size); profileChanged(true)
        }, lp(12))
        root.addView(prof, lp(14))

        /* Musikk */
        val mus = card("Musikk og annen lyd", "Trinn 3")
        mus.addView(text("Profilen legges på det du spiller av i Spotify, YouTube, podkaster, radio osv.", 17f, color = Colors.MUTED), lp(6))
        musicSwitch = switch("Bruk lydprofilen på musikk", p.musicOn) { b -> p.musicOn = b; profileChanged(false) }
        mus.addView(musicSwitch, lp(10))
        mus.addView(slider("Styrke", 0, 100, p.musicStrength, { "$it %" }) { v -> p.musicStrength = v; profileChanged(false) }, lp(8))
        mus.addView(switch("Reservemetode: bare apper som støtter equalizer", p.musicPerApp) { b -> p.musicPerApp = b; profileChanged(false) }, lp(8))
        mus.addView(text("Slå på reservemetoden bare hvis du ikke hører forskjell med profilen av og på. Da virker den i apper som Spotify, men ikke overalt.", 14f, color = Colors.MUTED), lp(2))
        musicStatus = text("", 15f, color = Colors.MUTED)
        mus.addView(musicStatus, lp(8))
        mus.addView(text("Skru gjerne opp telefonvolumet litt: profilen demper lyden totalt sett for å gi plass til forsterkningen uten forvrengning.", 14f, color = Colors.MUTED), lp(6))
        root.addView(mus, lp(14))

        /* Mikrofon */
        val mic = card("Lytt med mikrofonen", "Trinn 4")
        mic.addView(text("Mikrofonen på ledningen fanger opp lyden rundt deg, og appen spiller den forsterket inn i øreproppene. Musikkprofilen settes på pause så lenge dette er på.", 17f, color = Colors.MUTED), lp(6))
        micButton = button("Start mikrofonlytting", true) { toggleMic() }
        mic.addView(micButton, lp(12))
        micStatus = text("", 15f, color = Colors.MUTED)
        mic.addView(micStatus, lp(6))
        meterIn = meter(); meterOut = meter()
        mic.addView(text("Inn fra mikrofonen", 14f, color = Colors.MUTED), lp(8))
        mic.addView(meterIn)
        mic.addView(text("Ut til ørene", 14f, color = Colors.MUTED), lp(4))
        mic.addView(meterOut)
        latencyText = text("", 14f, color = Colors.MUTED)
        mic.addView(latencyText, lp(4))
        val sdb: (Int) -> String = { if (it > 0) "+$it dB" else "$it dB" }
        mic.addView(slider("Mikrofonfølsomhet", -12, 24, p.micGain, sdb) { v -> p.micGain = v; profileChanged(false) }, lp(12))
        mic.addView(slider("Volum ut", -30, 12, p.micOut, sdb) { v -> p.micOut = v; profileChanged(false) }, lp(8))
        mic.addView(slider("Styrke på profilen", 0, 100, p.micStrength, { "$it %" }) { v -> p.micStrength = v; profileChanged(false) }, lp(8))
        mic.addView(switch("Demp brå, sterke lyder", p.micComp) { b -> p.micComp = b; profileChanged(false) }, lp(8))
        mic.addView(switch("Demp sus og bakgrunnsstøy", p.micNs) { b -> p.micNs = b; profileChanged(false) }, lp(4))
        mic.addView(text("Hvis det piper eller uler: senk «Volum ut». EarPods slipper ut litt lyd, og mikrofonen kan fange den opp igjen.", 14f, color = Colors.WARN), lp(10))
        root.addView(mic, lp(14))

        return ScrollView(this).apply {
            setBackgroundColor(Colors.BG)
            addView(root)
        }
    }

    /* ---------------- Profil ---------------- */

    private fun refreshAll() {
        chart.profile = p
        setEditEar(editEar)
        testInfo.text = if (p.thrL != null && p.thrR != null)
            "Du har en lagret test (tatt ved ${p.testVolumePct} % volum). En ny test erstatter den."
        else "Ingen test tatt ennå."
    }

    private fun setEditEar(e: Int) {
        editEar = e
        earButtons.forEachIndexed { i, b -> styleButton(b, i == e) }
        bandBox.removeAllViews()
        FREQS.forEachIndexed { i, f ->
            val v = when (e) {
                0 -> p.gL[i]; 1 -> p.gR[i]; else -> (p.gL[i] + p.gR[i]) / 2f
            }.roundToInt()
            val name = if (f >= 1000) "${(f / 1000.0).toString().removeSuffix(".0").replace('.', ',')} kHz" else "$f Hz"
            bandBox.addView(slider(name, 0, MAX_GAIN.toInt(), v, { "+$it dB" }) { nv ->
                if (e != 1) p.gL[i] = nv.toFloat()
                if (e != 0) p.gR[i] = nv.toFloat()
                profileChanged(false)
                chart.invalidate()
            }, lp(6))
        }
    }

    private val applyToService = Runnable {
        val needFg = p.musicOn || HearingService.micRunning
        try { HearingService.send(this, HearingService.ACTION_REFRESH, needFg) } catch (_: Exception) {}
    }

    private fun profileChanged(rebuild: Boolean) {
        p.save(this)
        if (rebuild) refreshAll() else chart.invalidate()
        handler.removeCallbacks(applyToService)
        handler.postDelayed(applyToService, 250)
    }

    /* ---------------- Mikrofon ---------------- */

    private fun toggleMic() {
        if (HearingService.micRunning) {
            HearingService.send(this, HearingService.ACTION_MIC_STOP, false)
            return
        }
        if (AudioDevs.headsetOut(am) == null) {
            Toast.makeText(this, "Koble til EarPods først.", Toast.LENGTH_LONG).show(); return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) {
            pendingMicStart = true
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        HearingService.micStatus = "Starter …"
        HearingService.send(this, HearingService.ACTION_MIC_START, true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (pendingMicStart) {
            pendingMicStart = false
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleMic()
            else Toast.makeText(this, "Appen trenger tilgang til mikrofonen for å lytte.", Toast.LENGTH_LONG).show()
        }
    }

    /* ---------------- Hørselstest ---------------- */

    private fun startTest() {
        val out = AudioDevs.headsetOut(am)
        if (out == null) {
            Toast.makeText(this, "Koble til EarPods først.", Toast.LENGTH_LONG).show(); return
        }
        // Slå av all behandling under testen
        try { HearingService.send(this, HearingService.ACTION_SUSPEND, false) } catch (_: Exception) {}
        // Fast volum under testen, så resultatet er sammenlignbart
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val idx = (max * 0.6).roundToInt()
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) } catch (_: Exception) {}
        p.testVolumePct = (idx * 100.0 / max).roundToInt()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        testSeq = listOf(1, 0).flatMap { ear -> testOrder.map { fi -> Pair(ear, fi) } }
        testPos = 0
        thrL = FloatArray(FREQS.size); thrR = FloatArray(FREQS.size)
        testIntro.visibility = View.GONE
        testPanel.visibility = View.VISIBLE
        tone.start(out)
        showStep()
    }

    private fun showStep() {
        val (ear, fi) = testSeq[testPos]
        testLevel = -40
        tone.ear = ear
        tone.freq = FREQS[fi].toDouble()
        tone.levelDb = testLevel.toDouble()
        val f = FREQS[fi]
        val fname = if (f >= 1000) "${(f / 1000.0).toString().removeSuffix(".0").replace('.', ',')} kHz" else "$f Hz"
        testNow.text = (if (ear == 0) "Venstre øre" else "Høyre øre") + "\n" + fname
        testNow.setTextColor(if (ear == 0) Colors.LEFT else Colors.RIGHT)
        testProgress.text = "Tone ${testPos + 1} av ${testSeq.size}"
        updateLevelText()
    }

    private fun changeLevel(delta: Int) {
        testLevel = (testLevel + delta).coerceIn(-110, -6)
        tone.levelDb = testLevel.toDouble()
        updateLevelText()
    }

    private fun updateLevelText() {
        testLevelText.text = "Styrke: $testLevel dB"
    }

    private fun nextTone(notHeard: Boolean) {
        val (ear, fi) = testSeq[testPos]
        val v = if (notHeard) -6f else testLevel.toFloat()
        if (ear == 0) thrL[fi] = v else thrR[fi] = v
        testPos++
        if (testPos >= testSeq.size) finishTest() else showStep()
    }

    private fun endTestCommon() {
        tone.stop()
        if (savedVolume >= 0) try { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0) } catch (_: Exception) {}
        savedVolume = -1
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        testPanel.visibility = View.GONE
        testIntro.visibility = View.VISIBLE
    }

    private fun finishTest() {
        endTestCommon()
        p.thrL = thrL.copyOf(); p.thrR = thrR.copyOf()
        p.gainsFromThresholds()
        profileChanged(true)
        Toast.makeText(this, "Testen er lagret, og profilen er oppdatert.", Toast.LENGTH_LONG).show()
    }

    private fun cancelTest() {
        endTestCommon()
        handler.post(applyToService)
    }

    override fun onDestroy() {
        tone.stop()
        super.onDestroy()
    }
}
