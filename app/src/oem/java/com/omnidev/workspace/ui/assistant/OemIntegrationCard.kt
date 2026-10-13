package com.omnidev.workspace.ui.assistant

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun OemIntegrationCard() {
    val context = LocalContext.current
    var result by remember { mutableStateOf("Checking isolated system service…") }
    val hotword by com.omnidev.workspace.data.assistant.OemHotwordController.status.collectAsState()
    LaunchedEffect(Unit) {
        result = com.omnidev.workspace.data.tools.OemSystemTools.execute(context, mapOf("action" to "status"), null).output
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ROM integration", style = MaterialTheme.typography.titleMedium)
            Text(result)
            Text(hotword, style = MaterialTheme.typography.bodySmall)
        }
    }
}
