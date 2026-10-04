package com.omnidev.workspace.ui.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.theme.OmniDevTheme

/** Android 16 mobile sensor grants require a permission-usage/privacy rationale destination. */
class HealthAccessRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniDevTheme(dynamicColor = false) {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Health and sensor access", style = MaterialTheme.typography.headlineSmall)
                        Text("Android 16 uses separate permissions for heart rate, oxygen saturation, skin temperature and background sensor access. You can grant each separately and revoke it in Android settings at any time.")
                        Text("This build's access center checks and requests these grants. It does not implement a Health Connect record reader or start health monitoring. A permission grant alone does not collect your health records.")
                        Text("Information you explicitly include in an OmniDev conversation can be stored in local chat history and sent to the model provider configured for that conversation. Choose what you share, and remove saved conversations through chat history when needed.")
                        Button(onClick = ::finish) { Text("Done") }
                    }
                }
            }
        }
    }
}
