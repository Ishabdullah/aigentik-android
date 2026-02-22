package com.aigentik.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.R
import com.aigentik.app.core.AigentikService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Starting Aigentik..."
        startForegroundService(Intent(this, AigentikService::class.java))
        tvStatus.text = "✅ Aigentik is running"
    }
}
