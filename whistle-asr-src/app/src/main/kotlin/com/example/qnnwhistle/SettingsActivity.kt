package com.example.qnnwhistle

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Single-screen settings: a sensitivity slider that controls VAD thresholds
 * applied next time mic listening starts, plus an STT engine selector.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val current = Prefs.getSensitivity(this)

        val engineTitle = TextView(this).apply {
            text = "Speech-to-text engine"
            textSize = 18f
        }
        val engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val whistleBtn = RadioButton(this@SettingsActivity).apply {
                id = 1
                text = "Whistle (cactus-needle, CPU)"
            }
            val moonshineBtn = RadioButton(this@SettingsActivity).apply {
                id = 2
                text = "Moonshine v2 streaming-tiny (ONNX Runtime, CPU, unquantized)"
            }
            addView(whistleBtn)
            addView(moonshineBtn)
            check(if (Prefs.getEngine(this@SettingsActivity) == Prefs.ENGINE_MOONSHINE_V2) 2 else 1)
            setOnCheckedChangeListener { _, checkedId ->
                Prefs.setEngine(
                    this@SettingsActivity,
                    if (checkedId == 2) Prefs.ENGINE_MOONSHINE_V2 else Prefs.ENGINE_WHISTLE
                )
            }
        }
        val engineHelp = TextView(this).apply {
            text = "Takes effect the next time the app's recognition service " +
                   "process restarts (force-stop the app, or reboot, to be sure)."
            textSize = 13f
            setPadding(0, pad / 2, 0, pad)
        }

        val title = TextView(this).apply {
            text = "Microphone sensitivity"
            textSize = 18f
        }
        val value = TextView(this).apply {
            text = labelFor(current)
            textSize = 14f
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    value.text = labelFor(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setSensitivity(this@SettingsActivity, sb?.progress ?: current)
                }
            })
        }
        val help = TextView(this).apply {
            text = "Lower = stricter (only loud, clean speech is captured).\n" +
                   "Higher = more permissive (faint speech is captured but more " +
                   "background noise gets through).\n\n" +
                   "Changes apply the next time you start the mic."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(engineTitle)
            addView(engineGroup)
            addView(engineHelp)
            addView(title)
            addView(value)
            addView(seek)
            addView(help)
        }
        setContentView(root)
    }

    private fun labelFor(p: Int): String {
        val (floor, mult) = computeParams(p)
        val tag = when {
            p <= 25 -> "Strict"
            p <= 60 -> "Default"
            p <= 85 -> "Sensitive"
            else    -> "Very sensitive"
        }
        return "Level: $p — $tag\n(floor=${floor.toInt()}  multiplier=${"%.2f".format(mult)})"
    }

    private fun computeParams(p: Int): Pair<Float, Float> {
        val s = p.coerceIn(0, 100) / 100f
        val energyFloor      = 2000f - 1800f * s
        val speechMultiplier = 5.0f  - 3.5f  * s
        return energyFloor to speechMultiplier
    }
}
