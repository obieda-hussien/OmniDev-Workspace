package com.omnidev.workspace.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.omnidev.workspace.R

/** The launcher artwork, cropped to its mark and tinted for the current theme. */
@Composable
internal fun OmniMark(modifier: Modifier = Modifier) {
    Icon(painterResource(R.drawable.ic_omni), contentDescription = null,
        modifier = modifier, tint = MaterialTheme.colorScheme.primary)
}
