package com.omnidev.workspace.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import kotlinx.coroutines.launch

/**
 * Omni Memory management surface.
 *
 * The user sees one editable memory collection. Semantic/vector retrieval is an internal search
 * strategy over these same rows, not a second user-visible datastore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryExplorerScreen(
    knowledgeDao: KnowledgeDao,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val allSnippets by knowledgeDao.observeAll().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<KnowledgeSnippet?>(null) }
    var pendingDelete by remember { mutableStateOf<KnowledgeSnippet?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val categories = remember(allSnippets) {
        allSnippets.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val dialogSnippet = editTarget
    if (dialogSnippet != null || showAddDialog) {
        MemoryEditDialog(
            initial = dialogSnippet,
            onDismiss = {
                editTarget = null
                showAddDialog = false
            },
            onConfirm = { content, category, tags ->
                scope.launch {
                    if (dialogSnippet != null) {
                        knowledgeDao.update(
                            dialogSnippet.copy(
                                content = content.trim(),
                                category = category.trim().lowercase(),
                                tags = tags.trim().lowercase()
                            )
                        )
                    } else {
                        knowledgeDao.insert(
                            KnowledgeSnippet(
                                content = content.trim(),
                                category = category.trim().lowercase(),
                                tags = tags.trim().lowercase()
                            )
                        )
                    }
                    editTarget = null
                    showAddDialog = false
                }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete memory?") },
            text = {
                Text(
                    "This removes the memory from Omni Memory. " +
                        "Keyword and semantic retrieval will both stop seeing it."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { knowledgeDao.deleteById(target.id) }
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    val filtered = remember(allSnippets, searchQuery, selectedCategory) {
        val query = searchQuery.trim()
        allSnippets.filter { snippet ->
            val categoryMatches = selectedCategory == null || snippet.category == selectedCategory
            val queryMatches = query.isBlank() ||
                snippet.content.contains(query, ignoreCase = true) ||
                snippet.tags.contains(query, ignoreCase = true) ||
                snippet.category.contains(query, ignoreCase = true)
            categoryMatches && queryMatches
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Omni Memory")
                        Text(
                            text = "${allSnippets.size} memories · one unified store",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add memory")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search content, tags, or categories…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                supportingText = {
                    Text("Semantic retrieval uses these same memories automatically in the agent.")
                },
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") }
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            selectedCategory = if (selectedCategory == category) null else category
                        },
                        label = { Text(prettyCategory(category)) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            allSnippets.isEmpty() -> "No memories yet. Tap + to add your first one."
                            searchQuery.isNotBlank() -> "No memories match \"$searchQuery\"."
                            else -> "No memories in this category."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = filtered, key = { it.id }) { snippet ->
                        MemoryCard(
                            snippet = snippet,
                            onEdit = { editTarget = snippet },
                            onDelete = { pendingDelete = snippet }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    snippet: KnowledgeSnippet,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prettyCategory(snippet.category),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snippet.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (snippet.tags.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "🏷 ${snippet.tags}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit memory",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete memory",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initial: KnowledgeSnippet?,
    onDismiss: () -> Unit,
    onConfirm: (content: String, category: String, tags: String) -> Unit
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "general") }
    var tags by remember(initial?.id) { mutableStateOf(initial?.tags ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Edit Memory" else "Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Memory") },
                    placeholder = { Text("Fact, preference, project rule, architecture note…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    minLines = 3
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    placeholder = { Text("user_preference, project_rule, architecture, general") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    placeholder = { Text("comma-separated keywords") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(content, category, tags) },
                enabled = content.isNotBlank() && category.isNotBlank()
            ) {
                Text(if (initial != null) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun prettyCategory(category: String): String =
    category
        .split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        .ifBlank { "General" }
