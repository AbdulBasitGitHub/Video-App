package com.instarec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        fun add(text: String, action: () -> Unit) {
            col.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }
        col.addView(TextView(this).apply {
            text = "Do steps 1, 2 and 4 once. Step 3 once after install, and again after every phone restart " +
                "(you'll get a notification to tap)."
            textSize = 16f
            setPadding(0, 0, 0, 30)
        })
        add("1. Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        add("2. Enable Instagram detector (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add("3. Start recorder (approve once)") {
            startActivity(Intent(this, ProjectionActivity::class.java))
        }
        add("4. Disable battery optimization") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        add("Stop recorder") { stopService(Intent(this, RecorderService::class.java)) }

        val crash = prefs.getString("last", null)
        if (crash != null) {
            col.addView(TextView(this).apply {
                text = "LAST CRASH (long-press to copy):\n" + crash.take(4000)
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(0, 40, 0, 20)
            })
            add("Clear crash log") { prefs.edit().clear().apply(); recreate() }
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
