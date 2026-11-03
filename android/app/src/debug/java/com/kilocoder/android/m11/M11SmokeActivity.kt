package com.kilocoder.android.m11

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only Einstieg für M1.1. Erscheint im App-Drawer als eigener Eintrag,
 * weil das Debug-Manifest eine zweite Launcher-Activity deklariert.
 */
class M11SmokeActivity : Activity() {

    private val cancel = AtomicBoolean(false)
    private val log = StringBuilder()
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var java: CheckBox
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "M1.1 · PRoot-Smoke-Test"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply {
            text = "Bereit. Start lädt das Rootfs (41 MB) und prüft PRoot."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(status)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        start = Button(this).apply {
            text = "Start"
            setOnClickListener { begin() }
        }
        stop = Button(this).apply {
            text = "Abbrechen"
            isEnabled = false
            setOnClickListener {
                cancel.set(true)
                status.text = "Abbruch angefordert …"
            }
        }
        buttons.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        java = CheckBox(this).apply {
            text = "JDK im Gast installieren (optional, ~200 MB, 10–20 Min.)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        root.addView(java)

        val copy = Button(this).apply {
            text = "Bericht kopieren"
            setOnClickListener { copyReport() }
        }
        root.addView(copy)

        val scroll = ScrollView(this)
        output = TextView(this).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun begin() {
        if (running) return
        running = true
        cancel.set(false)
        log.setLength(0)
        output.text = ""
        start.isEnabled = false
        stop.isEnabled = true
        status.text = "Läuft …"

        val smoke = M11Smoke(this, { line ->
            runOnUiThread {
                log.appendLine(line)
                output.text = log.toString()
            }
        }, { cancel.get() })

        Thread {
            val result = try {
                smoke.run(java.isChecked)
            } catch (ex: Exception) {
                runOnUiThread { status.text = "Fehler: ${ex.javaClass.simpleName}: ${ex.message}" }
                null
            }
            runOnUiThread {
                running = false
                start.isEnabled = true
                stop.isEnabled = false
                if (result != null) {
                    val verdict = when {
                        result.aborted -> "ABGEBROCHEN"
                        result.failed == 0 -> "GO"
                        else -> "NO-GO"
                    }
                    status.text = "$verdict · ${result.passed} bestanden, ${result.failed} fehlgeschlagen, " +
                        "${result.skipped} übersprungen · ${result.report.absolutePath}"
                }
            }
        }.start()
    }

    private fun copyReport() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("M1.1", log.toString()))
        status.text = "Bericht in die Zwischenablage kopiert."
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        cancel.set(true)
        super.onDestroy()
    }
}
