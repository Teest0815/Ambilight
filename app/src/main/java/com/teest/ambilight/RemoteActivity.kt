package com.teest.ambilight

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Shows the address and QR code of the remote settings page. The server runs while this screen is open. */
class RemoteActivity : AppCompatActivity() {

    private lateinit var qr: ImageView
    private lateinit var urlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.bg)))
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        qr = findViewById(R.id.qrImage)
        urlText = findViewById(R.id.urlText)

        val auto = findViewById<SwitchCompat>(R.id.switchAuto)
        auto.isChecked = prefs.getBoolean(PREF_AUTO, false)
        auto.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(PREF_AUTO, checked).apply() }

        findViewById<Button>(R.id.btnNewKey).setOnClickListener {
            RemoteServer.newKey(prefs)
            show()
        }
        findViewById<Button>(R.id.btnRemoteBack).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        RemoteServer.acquire(this, "screen")
        show()
    }

    override fun onStop() {
        RemoteServer.release("screen")
        super.onStop()
    }

    private fun show() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = RemoteServer.url(prefs)
        if (url == null) {
            urlText.text = if (RemoteServer.running) "Keine Netzwerkverbindung gefunden" else "Der Server konnte nicht gestartet werden"
            qr.setImageDrawable(null)
            return
        }
        urlText.text = url.substringBefore("?k=") + "\nSchlüssel: " + url.substringAfter("?k=")
        qr.setImageBitmap(qrBitmap(url))
    }

    private fun qrBitmap(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val scale = 10
        val bmp = Bitmap.createBitmap(m.width * scale, m.height * scale, Bitmap.Config.ARGB_8888)
        val row = IntArray(bmp.width)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                val c = if (m[x, y]) Color.BLACK else Color.WHITE
                for (k in 0 until scale) row[x * scale + k] = c
            }
            for (k in 0 until scale) bmp.setPixels(row, 0, bmp.width, 0, y * scale + k, bmp.width, 1)
        }
        return bmp
    }

    companion object {
        const val PREF_AUTO = "remote_auto"
    }
}
