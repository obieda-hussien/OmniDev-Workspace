package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Agent Skills registry/settings screen.
 *
 * The old "Tool Arsenal" exposed a large static catalog of implementation tools
 * that the user could not meaningfully manage. Runtime tools remain internal to
 * the agent/tool router; this screen is intentionally focused on reusable skills
 * the user can import, create, enable, disable, and delete.
 *
 * The function name is kept for navigation/source compatibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolRegistryScreen(
    onNavigateBack: () -> Unit = {},
    onCreateSkillWithOmni: (String) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Skills")
                        Text(
                            "Built-in, imported, and Omni-created skills",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SkillsSettingsPanel(onCreateWithOmni = onCreateSkillWithOmni)
            Spacer(Modifier.height(16.dp))
        }
    }
}
