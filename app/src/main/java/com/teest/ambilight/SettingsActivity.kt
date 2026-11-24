package com.teest.ambilight

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shows one settings page (layout, standby, black screen …), chosen by the tile on the main screen. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val xml = intent.getIntExtra(EXTRA_XML, 0)
        if (xml == 0) {
            finish()
            return
        }
        findViewById<TextView>(R.id.title).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment.newInstance(xml))
                .commit()
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_XML = "xml"
    }
}
