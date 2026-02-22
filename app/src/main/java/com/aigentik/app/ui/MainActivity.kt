package com.aigentik.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.aigentik.app.core.AigentikService
import com.aigentik.app.databinding.ActivityMainBinding

// MainActivity — Aigentik v0.1
// Entry point, starts foreground service
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatus("Starting Aigentik...")
        startAigentikService()
    }

    private fun startAigentikService() {
        val intent = Intent(this, AigentikService::class.java)
        startForegroundService(intent)
        updateStatus("✅ Aigentik is running")
    }

    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }
}
