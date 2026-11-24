package com.teest.ambilight

import android.content.SharedPreferences
import android.graphics.Color

enum class Protocol { DDP, UDP }
enum class StartCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }
enum class BlackAction { OFF, HOLD, STATIC, EFFECT, WLED }
enum class StandbyAction { OFF, WLED, STATIC, EFFECT }

/** Everything that decides where LEDs sit on the screen edge. Data class so it can be compared cheaply. */
data class LayoutSpec(
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    val corner: StartCorner,
    val clockwise: Boolean,
    val offset: Int,
    val skipLeading: Int,
    val totalLeds: Int,
    val depthPercent: Int,
)

data class Config(
    val host: String,
    val protocol: Protocol,
    val wledOnStart: Boolean,
    val wledOffStop: Boolean,
    val layout: LayoutSpec,
    val fps: Int,
    val captureWidth: Int,
    val vibrant: Boolean,
    val smoothingMs: Int,
    val brightness: Int,
    val saturation: Int,
    val gamma: Float,
    val gainR: Int,
    val gainG: Int,
    val gainB: Int,
    val barDetect: Boolean,
    val barThreshold: Int,
    val barDelayMs: Int,
    val barSymmetric: Boolean,
    val barMaxPercent: Int,
    val blackAction: BlackAction,
    val blackDelayMs: Int,
    val blackThreshold: Int,
    val blackColor: Int,
    val blackBrightness: Int,
    val blackEffect: String,
    val effectSpeed: Int,
    val wledPreset: Int,
    val wledFx: Int,
    val wledSx: Int,
    val wledIx: Int,
    val wledPal: Int,
    val autoStart: Boolean,
    val adaptive: Boolean,
    val standbyAction: StandbyAction,
    val standbyColor: Int,
    val standbyBrightness: Int,
    val standbyEffect: String,
    val standbyEffectSpeed: Int,
    val standbyPreset: Int,
    val standbyFx: Int,
    val standbySx: Int,
    val standbyIx: Int,
    val standbyPal: Int,
    val blackFadeMs: Int,
    val standbyFadeMs: Int,
    val pauseOnDream: Boolean,
) {
    companion object {
        const val DEFAULT_BLACK_COLOR = "#FF6A00"

        fun load(p: SharedPreferences): Config {
            val corner = when (p.str("start_corner", "bottom_left")) {
                "top_left" -> StartCorner.TOP_LEFT
                "top_right" -> StartCorner.TOP_RIGHT
                "bottom_right" -> StartCorner.BOTTOM_RIGHT
                else -> StartCorner.BOTTOM_LEFT
            }
            val action = when (p.str("black_action", "off")) {
                "hold" -> BlackAction.HOLD
                "static" -> BlackAction.STATIC
                "effect" -> BlackAction.EFFECT
                "wled" -> BlackAction.WLED
                else -> BlackAction.OFF
            }
            return Config(
                host = p.str("host", "").trim(),
                protocol = if (p.str("protocol", "ddp") == "udp") Protocol.UDP else Protocol.DDP,
                wledOnStart = p.bool("wled_on_start", true),
                wledOffStop = p.bool("wled_off_stop", false),
                layout = LayoutSpec(
                    top = p.num("leds_top", 30).coerceIn(0, 1000),
                    right = p.num("leds_right", 17).coerceIn(0, 1000),
                    bottom = p.num("leds_bottom", 30).coerceIn(0, 1000),
                    left = p.num("leds_left", 17).coerceIn(0, 1000),
                    corner = corner,
                    clockwise = p.str("direction", "clockwise") != "counter",
                    offset = p.num("led_offset", 0).coerceIn(-5000, 5000),
                    skipLeading = p.num("skip_leading", 0).coerceIn(0, 5000),
                    totalLeds = p.num("total_leds", 0).coerceIn(0, 5000),
                    depthPercent = p.num("depth", 12).coerceIn(3, 40),
                ),
                fps = p.num("fps", 30).coerceIn(5, 60),
                captureWidth = p.num("capture_width", 160).coerceIn(32, 480),
                vibrant = p.str("color_mode", "average") == "vibrant",
                smoothingMs = p.num("smoothing", 150).coerceIn(0, 2000),
                brightness = p.num("brightness", 100).coerceIn(0, 100),
                saturation = p.num("saturation", 100).coerceIn(0, 300),
                gamma = p.str("gamma", "1.0").toFloatOrNull()?.coerceIn(1f, 3f) ?: 1f,
                gainR = p.num("gain_r", 100).coerceIn(0, 100),
                gainG = p.num("gain_g", 100).coerceIn(0, 100),
                gainB = p.num("gain_b", 100).coerceIn(0, 100),
                barDetect = p.bool("bar_detect", true),
                barThreshold = p.num("bar_threshold", 20).coerceIn(1, 100),
                barDelayMs = p.num("bar_delay", 1000).coerceIn(0, 10000),
                barSymmetric = p.bool("bar_symmetric", true),
                barMaxPercent = p.num("bar_max", 35).coerceIn(5, 45),
                blackAction = action,
                blackDelayMs = p.num("black_delay", 1500).coerceIn(0, 60000),
                blackThreshold = p.num("black_threshold", 4).coerceIn(1, 60),
                blackColor = parseColor(p.str("black_color", DEFAULT_BLACK_COLOR)),
                blackBrightness = p.num("black_brightness", 50).coerceIn(1, 100),
                blackEffect = p.str("black_effect", "breathing"),
                effectSpeed = p.num("effect_speed", 40).coerceIn(1, 100),
                wledPreset = p.num("wled_preset", 0).coerceIn(0, 250),
                wledFx = p.num("wled_fx", 0).coerceIn(0, 255),
                wledSx = p.num("wled_sx", 128).coerceIn(0, 255),
                wledIx = p.num("wled_ix", 128).coerceIn(0, 255),
                wledPal = p.num("wled_pal", 0).coerceIn(0, 255),
                autoStart = p.bool("auto_start", false),
                adaptive = p.bool("adaptive", true),
                standbyAction = when (p.str("standby_action", "off")) {
                    "wled" -> StandbyAction.WLED
                    "static" -> StandbyAction.STATIC
                    "effect" -> StandbyAction.EFFECT
                    else -> StandbyAction.OFF
                },
                standbyColor = parseColor(p.str("standby_color", DEFAULT_BLACK_COLOR)),
                standbyBrightness = p.num("standby_brightness", 30).coerceIn(1, 100),
                standbyEffect = p.str("standby_effect", "breathing"),
                standbyEffectSpeed = p.num("standby_effect_speed", 40).coerceIn(1, 100),
                standbyPreset = p.num("standby_wled_preset", 0).coerceIn(0, 250),
                standbyFx = p.num("standby_wled_fx", 0).coerceIn(0, 255),
                standbySx = p.num("standby_wled_sx", 128).coerceIn(0, 255),
                standbyIx = p.num("standby_wled_ix", 128).coerceIn(0, 255),
                standbyPal = p.num("standby_wled_pal", 0).coerceIn(0, 255),
                blackFadeMs = p.num("black_fade_ms", 800).coerceIn(0, 5000),
                standbyFadeMs = p.num("standby_fade_ms", 800).coerceIn(0, 5000),
                pauseOnDream = p.bool("pause_on_dream", true),
            )
        }

        fun isValidColor(text: String): Boolean = try {
            Color.parseColor(text.trim())
            true
        } catch (e: IllegalArgumentException) {
            false
        }

        private fun parseColor(text: String): Int = try {
            Color.parseColor(text.trim())
        } catch (e: IllegalArgumentException) {
            Color.parseColor(DEFAULT_BLACK_COLOR)
        }
    }
}

// SeekBar preferences store Int, EditText preferences store String: accept both.
private fun SharedPreferences.num(key: String, def: Int): Int = when (val v = all[key]) {
    is Int -> v
    is Long -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: def
    else -> def
}

private fun SharedPreferences.str(key: String, def: String): String = when (val v = all[key]) {
    is String -> v
    null -> def
    else -> v.toString()
}

private fun SharedPreferences.bool(key: String, def: Boolean): Boolean =
    (all[key] as? Boolean) ?: def
