package com.example.qnnwhistle

import android.content.Context

/**
 * Tiny wrapper around SharedPreferences for VAD tuning. All values are computed
 * from a single 0..100 "sensitivity" slider so users don't have to think about
 * RMS thresholds.
 *
 *   sensitivity=0   → very strict (only loud, clean speech triggers)
 *   sensitivity=50  → default (matches old hardcoded values)
 *   sensitivity=100 → very permissive (faint speech triggers, noise prone)
 */
object Prefs {
    private const val FILE = "qnn_whistle_prefs"
    private const val KEY_SENSITIVITY = "vad_sensitivity"
    private const val KEY_ENGINE = "stt_engine"
    const val DEFAULT_SENSITIVITY = 50
    const val ENGINE_WHISTLE = "whistle"
    const val ENGINE_MOONSHINE_V2 = "moonshine_v2"

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        get(ctx).getString(KEY_ENGINE, ENGINE_WHISTLE) ?: ENGINE_WHISTLE

    fun setEngine(ctx: Context, engine: String) {
        get(ctx).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun getSensitivity(ctx: Context): Int =
        get(ctx).getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(0, 100)

    fun setSensitivity(ctx: Context, value: Int) {
        get(ctx).edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 100)).apply()
    }

    /**
     * Map sensitivity (0..100) to (energyFloor, speechMultiplier).
     * Defaults at sensitivity=50 match the old hardcoded constants
     * (energyFloor=1000, speechMultiplier=3.5).
     */
    fun vadParams(ctx: Context): Pair<Float, Float> {
        val s = getSensitivity(ctx).toFloat() / 100f          // 0..1
        // Higher sensitivity → lower threshold floor and lower multiplier.
        val energyFloor      = 2000f - 1800f * s              // 2000 → 200
        val speechMultiplier = 5.0f  - 3.5f  * s              // 5.0 → 1.5
        return energyFloor to speechMultiplier
    }
}
