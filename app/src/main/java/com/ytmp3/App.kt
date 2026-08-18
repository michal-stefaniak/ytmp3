package com.ytmp3

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.view.WindowCompat
import com.yausername.ffmpeg.FFmpeg

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FFmpeg.getInstance().init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                WindowCompat.setDecorFitsSystemWindows(a.window, true)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
