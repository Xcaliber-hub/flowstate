package com.flowstate.focus.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flowstate.focus.R

// All twelve approved designs are bundled locally; no uploads or remote images.
private data class AvatarArtwork(val id: String, val label: String, val resource: Int)
private val approvedArtwork = listOf(
    AvatarArtwork("sprout", "Sprout", R.drawable.avatar_sprout),
    AvatarArtwork("sunbeam", "Sunbeam", R.drawable.avatar_sunbeam),
    AvatarArtwork("moon", "Moon", R.drawable.avatar_moon),
    AvatarArtwork("fox", "Fox", R.drawable.avatar_fox),
    AvatarArtwork("orbit", "Orbit", R.drawable.avatar_orbit),
    AvatarArtwork("summit", "Summit", R.drawable.avatar_summit),
    AvatarArtwork("cloud", "Cloud", R.drawable.avatar_cloud),
    AvatarArtwork("ember", "Ember", R.drawable.avatar_ember),
    AvatarArtwork("turtle", "Turtle", R.drawable.avatar_turtle),
    AvatarArtwork("jelly", "Jelly", R.drawable.avatar_jelly),
    AvatarArtwork("comet", "Comet", R.drawable.avatar_comet),
    AvatarArtwork("bloom", "Bloom", R.drawable.avatar_bloom),
)

@Composable
internal fun ProfileAvatar(name: String, avatarId: String, size: Int, ring: Long? = null) {
    val artwork = approvedArtwork.firstOrNull { it.id == avatarId }
    Box(
        Modifier.size(size.dp)
            .then(if (ring != null) Modifier.background(Color(ring), CircleShape).padding(3.dp) else Modifier)
            .clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = if (artwork == null) "$name initial" else "$name, ${artwork.label} avatar" },
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) Image(painterResource(artwork.resource), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else Text(name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun PresetAvatarPicker(name: String, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
        Text("Choose an avatar", style = MaterialTheme.typography.titleSmall)
        Text("Bundled presets only. No photo uploads.", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        approvedArtwork.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { artwork ->
                    val selected = selectedId == artwork.id
                    Column(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                            .border(BorderStroke(if (selected) 2.dp else 1.dp,
                                if (selected) colors.primary else colors.outline), MaterialTheme.shapes.medium)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(artwork.id) })
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProfileAvatar(name, artwork.id, 52)
                        Text(artwork.label, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) colors.onSecondaryContainer else colors.onSurface)
                        Text(if (selected) "Selected" else "Select", style = MaterialTheme.typography.labelSmall,
                            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton(onClick = { onSelect("") }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (selectedId.isEmpty()) "Use initial, selected" else "Use initial instead of preset avatar"
            }) { Text(if (selectedId.isEmpty()) "Initial selected" else "Use initial instead") }
    }
}
