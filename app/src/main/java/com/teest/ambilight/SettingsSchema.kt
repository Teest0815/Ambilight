package com.teest.ambilight

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** Describes every setting (read from the same XML files as the TV screens) so the remote page can show and change them. */
object SettingsSchema {
    enum class Type { SWITCH, SEEK, LIST, TEXT, NUMBER, COLOR }

    class Item(
        val key: String,
        val title: String,
        val summary: String,
        val type: Type,
        val default: String,
        val options: List<Pair<String, String>> = emptyList(),
        val min: Int = 0,
        val max: Int = 100,
        val step: Int = 1,
        val signed: Boolean = false,
    )

    class Page(val title: String, val items: List<Item>)

    val NUMERIC_KEYS = listOf(
        "leds_top", "leds_right", "leds_bottom", "leds_left",
        "skip_leading", "total_leds",
        "wled_preset", "wled_fx", "wled_sx", "wled_ix", "wled_pal",
        "standby_wled_preset", "standby_wled_fx", "standby_wled_sx", "standby_wled_ix", "standby_wled_pal",
    )
    const val SIGNED_KEY = "led_offset"
    val COLOR_KEYS = listOf("black_color", "standby_color")

    /** Never offered remotely. */
    private val HIDDEN = setOf("update_token", "update_repo")

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val APP = "http://schemas.android.com/apk/res-auto"

    @Volatile
    private var cached: List<Page>? = null

    fun pages(context: Context): List<Page> = cached ?: synchronized(this) {
        cached ?: build(context.applicationContext).also { cached = it }
    }

    private fun build(ctx: Context): List<Page> = SettingsPages.REMOTE.map { (title, xml) ->
        Page(title, parse(ctx, xml))
    }.filter { it.items.isNotEmpty() }

    private fun parse(ctx: Context, xml: Int): List<Item> {
        val res = ctx.resources
        val out = ArrayList<Item>()
        val p = res.getXml(xml)
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val key = p.getAttributeValue(ANDROID, "key") ?: continue
                if (key in HIDDEN) continue
                val title = p.getAttributeValue(ANDROID, "title") ?: key
                val summary = p.getAttributeValue(ANDROID, "summary")
                    ?: p.getAttributeValue(ANDROID, "dialogMessage") ?: ""
                val default = p.getAttributeValue(ANDROID, "defaultValue") ?: ""
                when (p.name) {
                    "SwitchPreferenceCompat" -> out += Item(key, title, summary, Type.SWITCH, default)
                    "SeekBarPreference" -> out += Item(
                        key, title, summary, Type.SEEK, default,
                        min = p.getAttributeIntValue(APP, "min", 0),
                        max = p.getAttributeIntValue(ANDROID, "max", 100),
                        step = p.getAttributeIntValue(APP, "seekBarIncrement", 1).coerceAtLeast(1),
                    )
                    "ListPreference" -> {
                        val labels = res.getStringArray(p.getAttributeResourceValue(ANDROID, "entries", 0))
                        val values = res.getStringArray(p.getAttributeResourceValue(ANDROID, "entryValues", 0))
                        out += Item(key, title, summary, Type.LIST, default, labels.indices.map { values[it] to labels[it] })
                    }
                    "EditTextPreference" -> {
                        val type = when {
                            key in NUMERIC_KEYS || key == SIGNED_KEY -> Type.NUMBER
                            key in COLOR_KEYS -> Type.COLOR
                            else -> Type.TEXT
                        }
                        out += Item(key, title, summary, type, default, signed = key == SIGNED_KEY)
                    }
                }
            }
        } finally {
            p.close()
        }
        return out
    }

    fun find(context: Context, key: String): Item? =
        pages(context).asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.key == key }

    /** Stored value as text, the default if nothing was stored yet. */
    fun current(p: SharedPreferences, item: Item): String = when (val v = p.all[item.key]) {
        null -> item.default
        else -> v.toString()
    }

    fun toJson(context: Context, p: SharedPreferences): JSONObject {
        val pages = JSONArray()
        for (page in pages(context)) {
            val items = JSONArray()
            for (i in page.items) {
                val o = JSONObject()
                    .put("key", i.key).put("title", i.title).put("summary", i.summary)
                    .put("type", i.type.name.lowercase())
                val value = current(p, i)
                when (i.type) {
                    Type.SWITCH -> o.put("value", value == "true")
                    Type.SEEK -> o.put("value", value.toIntOrNull() ?: i.min)
                        .put("min", i.min).put("max", i.max).put("step", i.step)
                    Type.LIST -> {
                        o.put("value", value)
                        val opts = JSONArray()
                        for ((v, l) in i.options) opts.put(JSONObject().put("v", v).put("l", l))
                        o.put("options", opts)
                    }
                    else -> o.put("value", value)
                }
                items.put(o)
            }
            pages.put(JSONObject().put("title", page.title).put("items", items))
        }
        val visibility = SettingsVisibility.compute { k -> find(context, k)?.let { current(p, it) } }
        val hidden = JSONArray()
        for ((k, v) in visibility) if (!v) hidden.put(k)
        return JSONObject().put("pages", pages).put("hidden", hidden)
    }

    /** Validates and stores one value. Returns null on success or the reason for refusal. */
    fun apply(context: Context, p: SharedPreferences, key: String, raw: Any?): String? {
        val item = find(context, key) ?: return "Unbekannte Einstellung"
        val e = p.edit()
        when (item.type) {
            Type.SWITCH -> e.putBoolean(key, raw as? Boolean ?: return "Ja/Nein erwartet")
            Type.SEEK -> {
                val n = (raw as? Number)?.toInt() ?: return "Zahl erwartet"
                if (n < item.min || n > item.max) return "Wert außerhalb von ${item.min} bis ${item.max}"
                e.putInt(key, n)
            }
            Type.LIST -> {
                val s = raw as? String ?: return "Text erwartet"
                if (item.options.none { it.first == s }) return "Ungültige Auswahl"
                e.putString(key, s)
            }
            Type.NUMBER -> {
                val s = (raw?.toString() ?: return "Zahl erwartet").trim()
                val ok = if (item.signed) Regex("-?\\d{1,6}").matches(s) else Regex("\\d{1,6}").matches(s)
                if (!ok) return "Ganze Zahl erwartet"
                e.putString(key, s)
            }
            Type.COLOR -> {
                val s = (raw as? String ?: return "Text erwartet").trim()
                if (!Config.isValidColor(s)) return "Ungültige Farbe, Beispiel: #FF6A00"
                e.putString(key, s)
            }
            Type.TEXT -> {
                val s = (raw as? String ?: return "Text erwartet").trim()
                if (s.length > 100 || s.any { it.isISOControl() }) return "Ungültiger Text"
                e.putString(key, s)
            }
        }
        e.apply()
        return null
    }
}
