package com.omnidev.workspace.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.motion.OmniIconButton

@Composable
internal fun OmniSearchField(query: String, onChange: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint) }, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp), textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) OmniIconButton(onClick = { onChange("") }) {
            Icon(Icons.Default.Close, "Clear search")
        } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
}
