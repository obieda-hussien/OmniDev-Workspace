package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.components.SettingsPageTabs
import com.omnidev.workspace.ui.motion.OmniIconButton

/**
 * Agent Skills registry/settings screen.
 *
 * Runtime tools remain internal to the router; this surface manages reusable
 * Agent Skills. The content deliberately owns one vertical scroll container so
 * a large installed-skill registry remains reachable on phones and small windows.
 *
 * The function name is kept for navigation/source compatibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolRegistryScreen(
    onNavigateBack: () -> Unit = {},
    onCreateSkillWithOmni: (String) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Agent skills")
                },
                navigationIcon = {
                    OmniIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SettingsPageTabs(listOf("Skill library", "Learned tasks"), selectedTab, { selectedTab = it })
            tabStates.SaveableStateProvider(selectedTab) {
                if (selectedTab == 0) {
                    SkillsSettingsPanel(onCreateWithOmni = onCreateSkillWithOmni, modifier = Modifier.weight(1f))
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) { LearnedTasksPanel(onAskOmni = onCreateSkillWithOmni) }
                }
            }
        }
    }
}
