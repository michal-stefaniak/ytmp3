package com.ytmp3

import android.content.Context

object Prefs {
    private const val NAME = "ytmp3"
    private lateinit var ctx: Context
    fun init(context: Context) { ctx = context.applicationContext }

    private fun prefs() = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun getInt(key: String, default: Int) = prefs().getInt(key, default)
    private fun setInt(key: String, v: Int) { prefs().edit().putInt(key, v).apply() }
    private fun getBool(key: String, default: Boolean) = prefs().getBoolean(key, default)
    private fun setBool(key: String, v: Boolean) { prefs().edit().putBoolean(key, v).apply() }
    private fun getStr(key: String) = prefs().getString(key, null)
    private fun setStr(key: String, v: String?) { prefs().edit().putString(key, v).apply() }

    var bitrate: Int
        get() = getInt("bitrate", 320)
        set(v) { setInt("bitrate", v) }

    var concurrency: Int
        get() = getInt("concurrency", 2)
        set(v) { setInt("concurrency", v) }

    var downloadDirUri: String?
        get() = getStr("downloadDirUri")
        set(v) { setStr("downloadDirUri", v) }

    var cleanTitle: Boolean
        get() = getBool("cleanTitle", true)
        set(v) { setBool("cleanTitle", v) }

    var normalise: Boolean
        get() = getBool("normalise", false)
        set(v) { setBool("normalise", v) }

    var wifiOnly: Boolean
        get() = getBool("wifiOnly", false)
        set(v) { setBool("wifiOnly", v) }

    var sponsorBlock: Boolean
        get() = getBool("sponsorBlock", false)
        set(v) { setBool("sponsorBlock", v) }

    var autoFolder: Boolean
        get() = getBool("autoFolder", false)
        set(v) { setBool("autoFolder", v) }

    var clipboardSniff: Boolean
        get() = getBool("clipboardSniff", true)
        set(v) { setBool("clipboardSniff", v) }

    var dupeCheck: Boolean
        get() = getBool("dupeCheck", true)
        set(v) { setBool("dupeCheck", v) }

    var storageWarn: Boolean
        get() = getBool("storageWarn", true)
        set(v) { setBool("storageWarn", v) }

    var showSpeed: Boolean
        get() = getBool("showSpeed", true)
        set(v) { setBool("showSpeed", v) }
}
