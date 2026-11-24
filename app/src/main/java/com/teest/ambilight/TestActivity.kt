package com.teest.ambilight

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shows a test picture full screen. The running Ambilight captures it and sends the result to WLED.
 * Any key (or a tap) ends the test; the test also ends by itself after its duration.
 */
class TestActivity : AppCompatActivity() {

    private lateinit var view: TestView
    private var kind = TestKind.ALL
    private var startMs = 0L
    private var stopServiceWhenDone = false

    private val frame = object : Runnable {
        override fun run() {
            val t = SystemClock.elapsedRealtime() - startMs
            if (t >= TestPatterns.LEAD_IN_MS + TestPatterns.durationMs(kind)) {
                finish()
                return
            }
            view.tMs = t
            view.invalidate()
            view.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = runCatching { TestKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }
            .getOrDefault(TestKind.ALL)
        stopServiceWhenDone = intent.getBooleanExtra(EXTRA_STOP_SERVICE, false)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TestView(this).also {
            it.kind = kind
            it.isFocusable = true
            it.isFocusableInTouchMode = true
            it.setOnClickListener { finish() }
        }
        setContentView(view)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        startMs = SystemClock.elapsedRealtime()
        view.postOnAnimation(frame)
    }

    override fun onStop() {
        view.removeCallbacks(frame)
        // leaving the screen (Home, other app) ends the test
        if (!isFinishing) finish()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (stopServiceWhenDone) {
            stopService(Intent(this, CaptureService::class.java))
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_STOP_SERVICE = "stop_service"
    }
}
