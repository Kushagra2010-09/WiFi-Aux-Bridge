package com.wifiauxbridge

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wifiauxbridge.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.tvBack.setOnClickListener { finish() }

        when (prefs.quality) {
            QualityPreset.LOW -> binding.radioLow.isChecked = true
            QualityPreset.MEDIUM -> binding.radioMedium.isChecked = true
            QualityPreset.HIGH -> binding.radioHigh.isChecked = true
        }
        binding.seekBuffer.max = 480
        binding.seekBuffer.progress = prefs.bufferMs.coerceIn(40, 480)
        updateBufferLabel(binding.seekBuffer.progress)
        binding.seekBuffer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateBufferLabel(progress.coerceAtLeast(40))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.switchLowLatency.isChecked = prefs.lowLatency
        binding.switchAutoReconnect.isChecked = prefs.autoReconnect
        binding.etPort.setText(prefs.tcpPort.toString())

        binding.btnSave.setOnClickListener {
            prefs.quality = when (binding.qualityGroup.checkedRadioButtonId) {
                binding.radioLow.id -> QualityPreset.LOW
                binding.radioMedium.id -> QualityPreset.MEDIUM
                else -> QualityPreset.HIGH
            }
            prefs.bufferMs = binding.seekBuffer.progress.coerceAtLeast(40)
            prefs.lowLatency = binding.switchLowLatency.isChecked
            prefs.autoReconnect = binding.switchAutoReconnect.isChecked
            val port = binding.etPort.text.toString().toIntOrNull()
            if (port != null && port in 1024..65535) {
                prefs.tcpPort = port
            } else {
                Toast.makeText(this, "Port must be between 1024 and 65535 — kept previous value.", Toast.LENGTH_LONG).show()
            }
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateBufferLabel(ms: Int) {
        binding.tvBufferValue.text = "$ms ms"
    }
}
