package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.db.dao.KnowledgeDao
import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsSearchField
import com.omnidev.workspace.ui.motion.OmniIconButton
import com.omnidev.workspace.ui.motion.omniAnimateContentSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryExplorerScreen(knowledgeDao: KnowledgeDao, onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snippetsFlow = remember(knowledgeDao) { knowledgeDao.observeAll() }
    val loaded by snippetsFlow.collectAsStateWithLifecycle(initialValue = null)
    val snippets = loaded.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editTarget = snippets.firstOrNull { it.id == editId }
    var pendingDelete by remember { mutableStateOf<KnowledgeSnippet?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val categories = remember(snippets) { snippets.groupingBy { it.category }.eachCount().toSortedMap() }
    LaunchedEffect(categories, loaded) { if (loaded != null && category != null && category !in categories) category = null }
    val filtered = remember(snippets, query, category, oldestFirst) {
        val search = query.trim()
        snippets.filter {
            (category == null || it.category == category) &&
                (search.isBlank() || listOf(it.content, it.tags, it.category).any { text -> text.contains(search, true) })
        }.let { if (oldestFirst) it.sortedBy { row -> row.createdAt } else it.sortedByDescending { row -> row.createdAt } }
    }
    if (editTarget != null || adding) {
        val target = editTarget
        MemoryEditDialog(target, saving, error, onDismiss = { if (!saving) { adding = false; editId = null; error = null } },
            onConfirm = { content, chosenCategory, tags ->
                saving = true; error = null
                scope.launch {
                    try {
                        if (target != null) knowledgeDao.update(target.copy(content = content.trim(), category = chosenCategory.trim().lowercase(), tags = tags.trim().lowercase()))
                        else knowledgeDao.insert(KnowledgeSnippet(content = content.trim(), category = chosenCategory.trim().lowercase(), tags = tags.trim().lowercase()))
                        editId = null; adding = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = "Could not save the memory. Please try again." }
                    finally { saving = false }
                }
            })
    }
    pendingDelete?.let { target ->
        AlertDialog(onDismissRequest = { if (!saving) { pendingDelete = null; error = null } },
            title = { Text("Delete memory?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Omni will stop using this memory in future answers.")
                Text(target.content, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true; error = null
                    scope.launch {
                        try { knowledgeDao.deleteById(target.id); pendingDelete = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = "Could not delete the memory. Please try again." }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Deleting…" else "Delete", color = MaterialTheme.colorScheme.error) }
            }, dismissButton = { TextButton(enabled = !saving, onClick = { pendingDelete = null; error = null }) { Text("Cancel") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Omni Memory") }, navigationIcon = {
        OmniIconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }, actions = { TextButton(onClick = { adding = true; error = null }, enabled = loaded != null && !saving) {
        Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add")
    } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("What Omni remembers", style = MaterialTheme.typography.headlineSmall)
                Text("Review the facts, preferences and project rules your assistant can use.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Memory summary", style = MaterialTheme.typography.titleMedium)
                        Text("${snippets.size} saved entries across ${categories.size} categories. This summary comes from saved entries; it does not infer new personal facts.", style = MaterialTheme.typography.bodySmall)
                        categories.forEach { (name, count) -> Text("${prettyCategory(name)} · $count", style = MaterialTheme.typography.labelLarge) }
                        snippets.filter { it.category == "user_preference" }.take(3).forEach { entry ->
                            Text("[${entry.id}] ${entry.content.take(240)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Search, read, edit or delete the source entries below. Profile details are managed separately in Your profile.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { SettingsSearchField(query, { query = it }, "Search memories, tags or categories") }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All · ${snippets.size}") })
                    categories.forEach { (name, count) ->
                        FilterChip(selected = category == name, onClick = { category = if (category == name) null else name }, label = { Text("${prettyCategory(name)} · $count") })
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${filtered.size} ${if (filtered.size == 1) "memory" else "memories"}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { oldestFirst = !oldestFirst }) { Text(if (oldestFirst) "Oldest first" else "Newest first") }
                }
            }
            if (loaded == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (filtered.isEmpty()) item {
                SettingsEmptyState(if (snippets.isEmpty()) "Start your memory collection" else "No matching memories",
                    if (snippets.isEmpty()) "Add a preference or project rule you want Omni to remember." else "Try another search or reset the category.")
                if (snippets.isEmpty()) Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add your first memory") }
                else TextButton(onClick = { query = ""; category = null }, modifier = Modifier.fillMaxWidth()) { Text("Reset filters") }
            }
            items(filtered, key = { it.id }) { snippet ->
                MemoryCard(snippet, onEdit = { editId = snippet.id; error = null }, onDelete = { pendingDelete = snippet; error = null })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MemoryCard(snippet: KnowledgeSnippet, onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by rememberSaveable(snippet.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().omniAnimateContentSize(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(prettyCategory(snippet.category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Text(snippet.content, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (snippet.tags.isNotBlank()) Text("Tags · ${snippet.tags}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(snippet.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read full memory") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(initial: KnowledgeSnippet?, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit) {
    var content by rememberSaveable(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var category by rememberSaveable(initial?.id) { mutableStateOf(initial?.category ?: "general") }
    var tags by rememberSaveable(initial?.id) { mutableStateOf(initial?.tags.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial != null) "Edit memory" else "Add memory") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(content, { content = it }, label = { Text("Memory") }, placeholder = { Text("A fact, preference or project rule…") },
                minLines = 4, enabled = !saving, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true, enabled = !saving,
                supportingText = { Text("For example: general, project_rule, user_preference") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags · optional") }, singleLine = true, enabled = !saving,
                supportingText = { Text("Separate keywords with commas.") }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = {
            TextButton(onClick = { onConfirm(content, category, tags) }, enabled = !saving && content.isNotBlank() && category.isNotBlank()) {
                Text(if (saving) "Saving…" else if (initial != null) "Save changes" else "Add memory")
            }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}

private fun prettyCategory(category: String): String = category.split('_', '-', ' ').filter { it.isNotBlank() }
    .joinToString(" ") { it.replaceFirstChar { character -> character.uppercase() } }.ifBlank { "General" }
