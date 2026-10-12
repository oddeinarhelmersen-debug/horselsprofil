package no.descartes.horsel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val SOFT = Color.parseColor("#E3EAE8")
}

class MainActivity : Activity() {

    private lateinit var p: Profile
    private lateinit var am: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale("nb", "NO"))

    // Visninger som oppdateres
    private lateinit var headsetText: TextView
    private lateinit var chart: ProfileView
    private lateinit var adjust: AdjustView
    private lateinit var adjustValues: TextView
    private lateinit var bandBox: LinearLayout
    private lateinit var earPanel: LinearLayout
    private lateinit var earToggle: Button
    private lateinit var earButtons: List<Button>
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
    private lateinit var testsBox: LinearLayout

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
    private var testVolumePct = 0

    private val d get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * d).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        p = Profile.load(this)
        p.save(this) // lagrer eventuell oppgradering av formatet
        window.statusBarColor = Colors.BG
        @Suppress("DEPRECATION")
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
                "Forsinkelse i appen: ca. ${HearingService.latencyMs} ms · Lydbrudd: ${HearingService.underruns}" +
                    (if (HearingService.underruns > 0) " (bufferen er økt automatisk)" else "") else ""
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
        b.backgroundTintList = ColorStateList.valueOf(if (primary) Colors.ACCENT else Colors.SOFT)
        b.setTextColor(if (primary) Color.WHITE else Colors.ACCENT)
    }

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s; textSize = 17f; isAllCaps = false
        minHeight = px(56)
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int = 10, w: Int = LinearLayout.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = px(8)
            })
        }
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card(title: String, step: String? = null): LinearLayout {
        val c = vbox().apply {
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
        return vbox().apply { addView(t); addView(sb, lp(4)) }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 17f; isChecked = checked; setTextColor(Colors.FG)
        minHeight = px(48)
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Colors.ACCENT, Color.parseColor("#B8C2BF")))
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    /** Radioknapper: options er (tittel, forklaring). */
    private fun choice(options: List<Pair<String, String>>, selected: Int, onChange: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        options.forEachIndexed { i, (title, help) ->
            addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = if (help.isEmpty()) title else "$title\n$help"
                textSize = 17f; setTextColor(Colors.FG)
                buttonTintList = ColorStateList.valueOf(Colors.ACCENT)
                minHeight = px(52)
                setPadding(px(6), px(6), 0, px(6))
                isChecked = i == selected
                setOnCheckedChangeListener { _, b -> if (b) onChange(i) }
            })
        }
    }

    private fun meter() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = ColorStateList.valueOf(Colors.ACCENT)
    }

    private fun buildUi(): View {
        val root = vbox().apply { setPadding(px(16), px(20), px(16), px(40)) }
        root.addView(text("Hørselsprofil", 30f, true))
        root.addView(text("Test hørselen på hvert øre, og bruk resultatet på musikk og når du lytter med mikrofonen.", 17f, color = Colors.MUTED), lp(4))
        headsetText = text("", 17f, true)
        root.addView(headsetText, lp(12))

        /* ---------- Hørselstest ---------- */
        val test = card("Hørselstest", "Trinn 1")
        testIntro = vbox()
        testIntro.addView(text("Sitt i et stille rom med EarPods i ørene. Du hører korte pip i ett øre om gangen.\n\nFor hvert pip: trykk «Svakere» til pipet forsvinner, og så «Sterkere» til det så vidt høres igjen. Trykk deretter «Hører så vidt». Det tar 5–10 minutter.", 17f), lp(8))
        testIntro.addView(button("Ta en ny hørselstest", true) { startTest() }, lp(12))
        testIntro.addView(text("Lagrede tester", 18f, true), lp(20))
        testIntro.addView(text("Testen som er «I bruk» styrer lydprofilen. Du kan bytte mellom tester når du vil.", 15f, color = Colors.MUTED), lp(2))
        testsBox = vbox()
        testIntro.addView(testsBox, lp(6))
        testIntro.addView(row(
            button("Ta sikkerhetskopi") { exportBackup() },
            button("Hent sikkerhetskopi") { importBackup() }
        ), lp(12))
        testIntro.addView(text("Sikkerhetskopien er tekst du kan sende til deg selv på e-post eller lagre i Disk. Den inneholder alle testene og justeringskurven.", 14f, color = Colors.MUTED), lp(4))
        test.addView(testIntro)

        testPanel = vbox().apply { visibility = View.GONE }
        testNow = text("", 28f, true).apply { gravity = Gravity.CENTER }
        testLevelText = text("", 20f).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
        testProgress = text("", 15f, color = Colors.MUTED).apply { gravity = Gravity.CENTER }
        testPanel.addView(testNow, lp(10))
        testPanel.addView(testLevelText, lp(6))
        testPanel.addView(row(button("Svakere −5") { changeLevel(-5) }, button("Sterkere +5") { changeLevel(5) }), lp(12))
        testPanel.addView(row(button("Svakere −1") { changeLevel(-1) }, button("Sterkere +1") { changeLevel(1) }), lp(4))
        testPanel.addView(button("Hører så vidt – neste tone", true) { nextTone(false) }, lp(14))
        testPanel.addView(button("Hører ikke tonen i det hele tatt") { nextTone(true) }, lp(6))
        testPanel.addView(testProgress, lp(10))
        testPanel.addView(button("Avbryt testen") { cancelTest() }, lp(10))
        test.addView(testPanel)
        root.addView(test, lp(14))

        /* ---------- Profil ---------- */
        val prof = card("Din lydprofil", "Trinn 2")
        prof.addView(text("Samlet forsterkning per øre", 17f, true), lp(8))
        chart = ProfileView(this)
        prof.addView(chart, lp(6))
        prof.addView(LinearLayout(this).apply {
            addView(text("○ Høyre øre", 15f, true, Colors.RIGHT))
            addView(text("× Venstre øre", 15f, true, Colors.LEFT), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
            addView(text("dB", 15f, color = Colors.MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(18) })
        }, lp(4))

        prof.addView(text("Felles justering for begge ører", 17f, true), lp(22))
        prof.addView(text("Dra et punkt opp for mer, ned for mindre. Dra et sted mellom punktene for å flytte hele kurven. Justeringen legges oppå testresultatet.", 15f, color = Colors.MUTED), lp(2))
        adjust = AdjustView(this)
        adjust.onChange = { chart.invalidate(); updateAdjustValues() }
        adjust.onCommit = { profileChanged(false) }
        prof.addView(adjust, lp(8))
        adjustValues = text("", 14f, color = Colors.MUTED).apply { typeface = Typeface.MONOSPACE }
        prof.addView(adjustValues, lp(2))
        prof.addView(button("Nullstill justeringen") {
            p.adj = FloatArray(FREQS.size); profileChanged(true)
        }, lp(8))

        earToggle = button("Vis finjustering per øre") {
            val show = earPanel.visibility != View.VISIBLE
            earPanel.visibility = if (show) View.VISIBLE else View.GONE
            earToggle.text = if (show) "Skjul finjustering per øre" else "Vis finjustering per øre"
        }
        prof.addView(earToggle, lp(16))
        earPanel = vbox().apply { visibility = View.GONE }
        earPanel.addView(text("Her endrer du testresultatet for ett øre. «Bruk» på en lagret test tar deg tilbake til de opprinnelige verdiene.", 15f, color = Colors.MUTED), lp(8))
        val bBoth = button("Begge") { setEditEar(2) }
        val bL = button("Venstre") { setEditEar(0) }
        val bR = button("Høyre") { setEditEar(1) }
        earButtons = listOf(bL, bR, bBoth)
        earPanel.addView(row(bBoth, bL, bR), lp(8))
        bandBox = vbox()
        earPanel.addView(bandBox, lp(8))
        prof.addView(earPanel)
        root.addView(prof, lp(14))

        /* ---------- Musikk ---------- */
        val mus = card("Musikk og annen lyd", "Trinn 3")
        mus.addView(text("Legger lydprofilen på det du spiller av: YouTube Music, Spotify, podkaster, radio og video.", 17f, color = Colors.MUTED), lp(6))
        mus.addView(switch("Bruk lydprofilen på musikk", p.musicOn) { b -> p.musicOn = b; profileChanged(false) }, lp(10))
        mus.addView(slider("Styrke på profilen", 0, 200, p.musicStrength, { "$it %" }) { v -> p.musicStrength = v; profileChanged(false) }, lp(10))
        mus.addView(text("Musikk spilles vanligvis sterkere enn tale, og da trengs mindre forsterkning. Låter det skarpt eller tynt, skru ned styrken.", 14f, color = Colors.MUTED), lp(2))
        mus.addView(slider("Bass", 0, 12, p.musicBass, { if (it > 0) "+$it dB" else "0 dB" }) { v -> p.musicBass = v; profileChanged(false) }, lp(10))
        mus.addView(text("Hvordan profilen kobles på", 17f, true), lp(16))
        mus.addView(choice(listOf(
            "Alle apper (anbefalt)" to "Virker på all lyd telefonen spiller.",
            "Bare apper som melder fra" to "Reserve hvis «Alle apper» ikke gir noen hørbar forskjell. Virker bare i apper som sier fra til Android når de spiller, og avspillingen må startes på nytt."
        ), if (p.musicPerApp) 1 else 0) { i -> p.musicPerApp = i == 1; profileChanged(false) }, lp(4))
        musicStatus = text("", 15f, color = Colors.MUTED)
        mus.addView(musicStatus, lp(8))
        root.addView(mus, lp(14))

        /* ---------- Mikrofon ---------- */
        val mic = card("Lytt med mikrofonen", "Trinn 4")
        mic.addView(text("Mikrofonen fanger opp lyden rundt deg, og appen spiller den forsterket inn i øreproppene.", 17f, color = Colors.MUTED), lp(6))
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
        mic.addView(text("Mikrofon", 17f, true), lp(14))
        mic.addView(choice(listOf(
            "Mikrofonen på ledningen" to "Følger deg rundt. Fanger også opp din egen stemme.",
            "Mikrofonen i telefonen" to "Legg telefonen nær den som snakker, for eksempel midt på bordet. Gir minst piping."
        ), p.micSource) { i -> p.micSource = i; profileChanged(false) }, lp(4))
        val sdb: (Int) -> String = { if (it > 0) "+$it dB" else "$it dB" }
        mic.addView(slider("Mikrofonfølsomhet", -12, 24, p.micGain, sdb) { v -> p.micGain = v; profileChanged(false) }, lp(12))
        mic.addView(slider("Volum ut", -30, 12, p.micOut, sdb) { v -> p.micOut = v; profileChanged(false) }, lp(8))
        mic.addView(slider("Styrke på profilen", 0, 200, p.micStrength, { "$it %" }) { v -> p.micStrength = v; profileChanged(false) }, lp(8))
        mic.addView(text("Over 100 % forsterker mer enn testen tilsier. Gå opp gradvis, og senk igjen hvis det suser eller piper.", 14f, color = Colors.MUTED), lp(2))
        mic.addView(switch("Demp brå, sterke lyder", p.micComp) { b -> p.micComp = b; profileChanged(false) }, lp(8))
        mic.addView(switch("Demp sus og bakgrunnsstøy", p.micNs) { b -> p.micNs = b; profileChanged(false) }, lp(4))
        mic.addView(text("Hvis det piper eller uler: senk «Volum ut», eller bytt til mikrofonen i telefonen.", 14f, color = Colors.WARN), lp(10))
        root.addView(mic, lp(14))

        /* ---------- Bruk profilen andre steder ---------- */
        val other = card("Bruk profilen på PC-en")
        other.addView(text("Lager en ferdig innstilling for gratisprogrammet Equalizer APO på Windows. Den bruker samme styrke og bass som under «Musikk og annen lyd».", 17f, color = Colors.MUTED), lp(6))
        other.addView(row(
            button("Send til PC", true) { exportApo(send = true) },
            button("Kopier tekst") { exportApo(send = false) }
        ), lp(12))
        other.addView(text("«Send til PC» lar deg sende teksten med e-post eller lagre den i Disk. Lim den inn i filen config.txt i mappen C:\\Program Files\\EqualizerAPO\\config. Oppsettet er forklart øverst i teksten.", 14f, color = Colors.MUTED), lp(6))
        root.addView(other, lp(14))

        val ver = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        root.addView(text("Hørselsprofil versjon $ver", 14f, color = Colors.MUTED).apply { gravity = Gravity.CENTER }, lp(20))

        return ScrollView(this).apply {
            setBackgroundColor(Colors.BG)
            addView(root)
        }
    }

    /* ---------------- Profil ---------------- */

    private fun refreshAll() {
        chart.profile = p
        adjust.profile = p
        updateAdjustValues()
        setEditEar(editEar)
        renderTests()
    }

    private fun updateAdjustValues() {
        adjustValues.text = FREQS.indices.joinToString("  ") { i ->
            val f = FREQS[i]; val v = p.adj[i].roundToInt()
            (if (f >= 1000) "${f / 1000.0}".removeSuffix(".0") + "k" else "$f") + ":" + (if (v > 0) "+$v" else "$v")
        }
    }

    private fun renderTests() {
        testsBox.removeAllViews()
        if (p.tests.isEmpty()) {
            testsBox.addView(text("Ingen lagrede tester ennå.", 16f, color = Colors.MUTED))
            return
        }
        p.tests.withIndex().reversed().forEach { (i, t) ->
            val inUse = i == p.activeTest
            val box = vbox().apply {
                setPadding(px(12), px(10), px(12), px(10))
                background = GradientDrawable().apply {
                    setColor(if (inUse) Color.parseColor("#E6F2EF") else Colors.CARD)
                    cornerRadius = 8 * d; setStroke(px(if (inUse) 2 else 1), if (inUse) Colors.ACCENT else Colors.LINE)
                }
            }
            box.addView(text(t.name + if (inUse) "  ·  I bruk" else "", 17f, true, if (inUse) Colors.ACCENT else Colors.FG))
            box.addView(text("Tatt ${dateFmt.format(Date(t.time))} ved ${t.volumePct} % volum", 14f, color = Colors.MUTED))
            val use = button(if (inUse) "Tilbakestill" else "Bruk", !inUse) {
                p.activeTest = i; p.applyTest(t); profileChanged(true)
                Toast.makeText(this, "«${t.name}» er i bruk.", Toast.LENGTH_SHORT).show()
            }
            val show = button("Vis tall") { showThresholds(t) }
            val del = button("Slett") { confirmDelete(i) }
            box.addView(row(use, show, del), lp(8))
            testsBox.addView(box, lp(8))
        }
    }

    private fun showThresholds(t: SavedTest) {
        val sb = StringBuilder("Svakeste hørbare nivå (dB)\n\n")
        sb.append(String.format(Locale.ROOT, "%-9s %8s %8s\n", "", "Venstre", "Høyre"))
        FREQS.forEachIndexed { i, f ->
            sb.append(String.format(Locale.ROOT, "%-9s %8.0f %8.0f\n", freqName(f), t.thrL[i], t.thrR[i]))
        }
        sb.append("\nLavere tall = bedre hørsel.")
        AlertDialog.Builder(this).setTitle(t.name)
            .setMessage(sb.toString())
            .setPositiveButton("Lukk", null)
            .show()
            .findViewById<TextView>(android.R.id.message)?.typeface = Typeface.MONOSPACE
    }

    private fun confirmDelete(i: Int) {
        val t = p.tests[i]
        AlertDialog.Builder(this).setTitle("Slette «${t.name}»?")
            .setMessage("Testen blir borte fra telefonen. Ta gjerne en sikkerhetskopi først.")
            .setNegativeButton("Avbryt", null)
            .setPositiveButton("Slett") { _, _ ->
                p.tests.removeAt(i)
                if (p.activeTest == i) p.activeTest = -1 else if (p.activeTest > i) p.activeTest--
                profileChanged(true)
            }.show()
    }

    private fun exportBackup() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hørselsprofil – sikkerhetskopi ${dateFmt.format(Date())}")
            putExtra(Intent.EXTRA_TEXT, p.exportJson())
        }
        startActivity(Intent.createChooser(send, "Lagre eller send sikkerhetskopien"))
    }

    private fun exportApo(send: Boolean) {
        val txt = ApoExport.build(p)
        if (send) {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Hørselsprofil – Equalizer APO")
                putExtra(Intent.EXTRA_TEXT, txt)
            }
            startActivity(Intent.createChooser(i, "Send innstillingen til PC-en"))
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Equalizer APO", txt))
            Toast.makeText(this, "Teksten er kopiert.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importBackup() {
        val input = EditText(this).apply {
            hint = "Lim inn teksten fra sikkerhetskopien her"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; maxLines = 10
        }
        val wrap = vbox().apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Hent sikkerhetskopi")
            .setView(wrap)
            .setNegativeButton("Avbryt", null)
            .setPositiveButton("Hent") { _, _ ->
                val n = p.importJson(input.text.toString())
                if (n == null) {
                    Toast.makeText(this, "Teksten ble ikke gjenkjent som en sikkerhetskopi.", Toast.LENGTH_LONG).show()
                } else {
                    profileChanged(true)
                    Toast.makeText(this, "Hentet $n test(er).", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun setEditEar(e: Int) {
        editEar = e
        earButtons.forEachIndexed { i, b -> styleButton(b, i == e) }
        bandBox.removeAllViews()
        FREQS.forEachIndexed { i, f ->
            val v = when (e) {
                0 -> p.gL[i]; 1 -> p.gR[i]; else -> (p.gL[i] + p.gR[i]) / 2f
            }.roundToInt()
            bandBox.addView(slider(freqName(f), 0, MAX_GAIN.toInt(), v, { "+$it dB" }) { nv ->
                if (e != 1) p.gL[i] = nv.toFloat()
                if (e != 0) p.gR[i] = nv.toFloat()
                profileChanged(false)
            }, lp(6))
        }
    }

    private val applyToService = Runnable {
        val needFg = p.musicOn || HearingService.micRunning
        try { HearingService.send(this, HearingService.ACTION_REFRESH, needFg) } catch (_: Exception) {}
    }

    private fun profileChanged(rebuild: Boolean) {
        p.save(this)
        if (rebuild) refreshAll() else { chart.invalidate(); adjust.invalidate() }
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
        testVolumePct = (idx * 100.0 / max).roundToInt()

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
        testNow.text = (if (ear == 0) "Venstre øre" else "Høyre øre") + "\n" + freqName(FREQS[fi])
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
        val l = thrL.copyOf(); val r = thrR.copyOf()
        val (gl, gr) = SavedTest.gainsFrom(l, r)
        val now = System.currentTimeMillis()
        val input = EditText(this).apply {
            setText("Test ${dateFmt.format(Date(now))}")
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val wrap = vbox().apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        fun store(name: String) {
            val t = SavedTest(name.ifBlank { "Test ${dateFmt.format(Date(now))}" }, now, l, r, gl, gr, testVolumePct)
            p.tests.add(t)
            p.activeTest = p.tests.size - 1
            p.applyTest(t)
            profileChanged(true)
            Toast.makeText(this, "Testen er lagret og tatt i bruk.", Toast.LENGTH_LONG).show()
        }
        AlertDialog.Builder(this).setTitle("Lagre hørselstesten")
            .setMessage("Gi testen et navn, for eksempel «Med nye EarPods».")
            .setView(wrap)
            .setCancelable(false)
            .setPositiveButton("Lagre") { _, _ -> store(input.text.toString()) }
            .show()
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
