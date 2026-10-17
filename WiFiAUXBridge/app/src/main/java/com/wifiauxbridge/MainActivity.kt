package com.wifiauxbridge

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wifiauxbridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardTransmitter.setOnClickListener {
            startActivity(Intent(this, TransmitterActivity::class.java))
        }
        binding.cardReceiver.setOnClickListener {
            startActivity(Intent(this, ReceiverActivity::class.java))
        }
        binding.tvSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
