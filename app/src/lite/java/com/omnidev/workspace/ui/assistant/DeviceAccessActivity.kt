package com.omnidev.workspace.ui.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Lite setup includes only permissions used by its shipped features. */
class DeviceAccessActivity : ComponentActivity() {
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Assistant access", style = MaterialTheme.typography.headlineMedium)
                        Text("Choose access for voice and the floating assistant. Files and photos are selected through Android's picker.")
                        Button(onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Microphone") }
                        Button(onClick = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("Display over other apps") }
                        Button(onClick = { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) { Text("Default digital assistant") }
                        Button(onClick = { startActivity(Intent(this@DeviceAccessActivity, VoiceWakeActivity::class.java)) }) { Text("Offline voice and Hi Omni") }
                        DeviceConsentCard()
                        OutlinedButton(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
