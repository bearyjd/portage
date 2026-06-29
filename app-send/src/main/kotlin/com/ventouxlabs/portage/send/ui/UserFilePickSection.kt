/*
 * portage-send (exporter) — GrapheneOS device-parity transfer
 * Copyright (C) 2026 Grepon Labs LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */
package com.ventouxlabs.portage.send.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.ventouxlabs.portage.send.userfile.AndroidUserFileResolver
import com.ventouxlabs.portage.send.userfile.PickedUserFile
import com.ventouxlabs.portage.send.ui.theme.LocalSpacing

@Composable
fun UserFilePickSection(
    files: List<PickedUserFile>,
    onResolveFiles: (List<() -> PickedUserFile?>) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val resolver = AndroidUserFileResolver(LocalContext.current)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onResolveFiles(uris.map { uri -> { resolver.resolve(uri) } }) }
    val spacing = LocalSpacing.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("FILES", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (files.isEmpty()) "Choose photos, documents, or other files"
                    else "${files.size} selected · ${formatBytes(files.sumOf { it.byteLength })}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Choose files") }
        }
        files.forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${file.mimeType} · ${formatBytes(file.byteLength)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemove(file.pickId) }) { Text("Remove") }
            }
        }
    }
}
