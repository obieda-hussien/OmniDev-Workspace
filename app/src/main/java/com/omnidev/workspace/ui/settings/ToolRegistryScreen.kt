package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.tools.AdvancedRootShellTool
import com.omnidev.workspace.data.tools.AppManagerTool
import com.omnidev.workspace.data.tools.CallLogTool
import com.omnidev.workspace.data.tools.CommunicationTool
import com.omnidev.workspace.data.tools.DeviceInfoTool
import com.omnidev.workspace.data.tools.GitManagerTool
import com.omnidev.workspace.data.tools.HardwareToggleTool
import com.omnidev.workspace.data.tools.LocationTool
import com.omnidev.workspace.data.tools.LogcatAnalyzerTool
import com.omnidev.workspace.data.tools.NotificationCaptureTool
import com.omnidev.workspace.data.tools.PackageInstallerTool
import com.omnidev.workspace.data.tools.PlannerTool
import com.omnidev.workspace.data.tools.ScreenshotTool
import com.omnidev.workspace.data.tools.SmsReaderTool
import com.omnidev.workspace.data.tools.SystemContactsTool
import com.omnidev.workspace.data.tools.SystemSettingsTool
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import com.omnidev.workspace.data.tools.TelegramBotTool
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.UIAutomationTool
import com.omnidev.workspace.data.tools.VisualInspectorTool

private data class ToolGroup(val title: String, val tools: List<ToolDefinition>)

private val TOOL_GROUPS: List<ToolGroup> by lazy {
    listOf(
        ToolGroup("📁 File & Codebase", buildFileToolDefs()),
        ToolGroup("🧠 Memory", buildMemoryToolDefs()),
        ToolGroup(
            "📲 Device & OS",
            buildList {
                addAll(CommunicationTool.getToolDefinitions())
                addAll(HardwareToggleTool.getToolDefinitions())
                addAll(LocationTool.getToolDefinitions())
                addAll(DeviceInfoTool.getToolDefinitions())
                addAll(AppManagerTool.getToolDefinitions())
                addAll(SystemContactsTool.getToolDefinitions())
                addAll(UIAutomationTool.getToolDefinitions())
                addAll(CallLogTool.getToolDefinitions())
                addAll(SmsReaderTool.getToolDefinitions())
                addAll(ScreenshotTool.getToolDefinitions())
                addAll(SystemSettingsTool.getToolDefinitions())
                addAll(PackageInstallerTool.getToolDefinitions())
                addAll(AdvancedRootShellTool.getToolDefinitions())
            }
        ),
        ToolGroup(
            "🔍 Diagnostics",
            buildList {
                addAll(LogcatAnalyzerTool.getToolDefinitions())
                addAll(VisualInspectorTool.getToolDefinitions())
            }
        ),
        ToolGroup(
            "📅 Planning & Tasks",
            buildList {
                addAll(PlannerTool.getToolDefinitions())
                addAll(TaskSchedulerTool.getToolDefinitions())
            }
        ),
        ToolGroup("🔔 Notifications", NotificationCaptureTool.getToolDefinitions()),
        ToolGroup(
            "🔗 Integrations",
            buildList {
                addAll(TelegramBotTool.getToolDefinitions())
                addAll(GitManagerTool.getToolDefinitions())
            }
        )
    )
}

private fun buildFileToolDefs(): List<ToolDefinition> = listOf(
    ToolDefinition(name = "read_file_lines", description = "Read specific lines from a file. Returns content between startLine and endLine."),
    ToolDefinition(name = "search_codebase", description = "Search for a regex pattern across all files in a directory tree."),
    ToolDefinition(name = "patch_file_content", description = "Find-and-replace inside a file without rewriting the whole document."),
    ToolDefinition(name = "create_file", description = "Create a new file with the given content."),
    ToolDefinition(name = "delete_file", description = "Permanently delete a file."),
    ToolDefinition(name = "run_terminal", description = "Execute a shell command. In God Mode the working directory is unrestricted."),
    ToolDefinition(name = "web_search", description = "Search the web and return ranked results.")
)

private fun buildMemoryToolDefs(): List<ToolDefinition> = listOf(
    ToolDefinition(name = "remember_fact", description = "Save a fact to the long-term knowledge base."),
    ToolDefinition(name = "search_knowledge", description = "Semantic/keyword search across the knowledge base."),
    ToolDefinition(name = "update_memory", description = "Update an existing memory entry by ID."),
    ToolDefinition(name = "delete_memory", description = "Delete a memory entry by ID.")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolRegistryScreen(
    onNavigateBack: () -> Unit = {},
    onCreateSkillWithOmni: (String) -> Unit = {}
) {
    val totalTools = TOOL_GROUPS.sumOf { it.tools.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agent Capabilities")
                        Text(
                            "$totalTools tools + reusable skills",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                SkillsSettingsPanel(onCreateWithOmni = onCreateSkillWithOmni)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            item {
                Text(
                    text = "Tool Arsenal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Runtime actions currently exposed to Omni",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            for (group in TOOL_GROUPS) {
                item {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    ToolGroupCard(group.tools)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ToolGroupCard(tools: List<ToolDefinition>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            tools.forEachIndexed { index, tool ->
                ToolRow(tool)
                if (index < tools.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolDefinition) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (tool.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
