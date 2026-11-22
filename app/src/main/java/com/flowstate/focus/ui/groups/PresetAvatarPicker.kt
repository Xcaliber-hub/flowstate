package com.flowstate.focus.ui.groups

import com.flowstate.focus.ui.components.AutoSizeText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowstate.focus.R
import com.flowstate.focus.domain.groups.PresetAvatars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

// All approved designs are bundled locally; no uploads or remote images. 1.9.0: two categories, Orbs and
// Stitched, 24 each; the original sticker set was removed.
private const val PICKER_COLUMNS = 3

/**
 * 1.8.7: avatars are 440-464 px artworks shown at 32-58 dp. painterResource() decoded a full-size copy
 * (~0.85 MB) for every avatar on screen, again on every recomposition of a new list row, on the main
 * thread: chat scrolling and the leaderboard churned memory (OOM risk on 2-3 GB phones). Decode once at
 * half size (still >= the 58 dp xxhdpi size) and share it.
 */
private val avatarCache = android.util.LruCache<Int, androidx.compose.ui.graphics.ImageBitmap>(PRESET_CACHE_SIZE)
private const val PRESET_CACHE_SIZE = 32 // one tab of 24 plus the avatars on screen behind the dialog

@Composable
private fun avatarBitmap(resource: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    return androidx.compose.runtime.remember(resource) {
        avatarCache.get(resource) ?: runCatching {
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            android.graphics.BitmapFactory.decodeResource(resources, resource, options)
        }.getOrNull()?.asImageBitmap()?.also { avatarCache.put(resource, it) }
    }
}

private data class AvatarArtwork(val id: String, val label: String, val resource: Int)

/** Drawable for each preset id. Explicit references keep resource shrinking from dropping them. */
private val avatarResources: Map<String, Int> = mapOf(
    "terrarium" to R.drawable.avatar_terrarium,
    "lavalamp" to R.drawable.avatar_lavalamp,
    "aurora" to R.drawable.avatar_aurora,
    "koi" to R.drawable.avatar_koi,
    "nebula" to R.drawable.avatar_nebula,
    "chrome" to R.drawable.avatar_chrome,
    "eclipse" to R.drawable.avatar_eclipse,
    "snowglobe" to R.drawable.avatar_snowglobe,
    "candle" to R.drawable.avatar_candle,
    "curl" to R.drawable.avatar_curl,
    "sakura" to R.drawable.avatar_sakura,
    "desklamp" to R.drawable.avatar_desklamp,
    "dune" to R.drawable.avatar_dune,
    "rain" to R.drawable.avatar_rain,
    "island" to R.drawable.avatar_island,
    "jellyfish" to R.drawable.avatar_jellyfish,
    "chai" to R.drawable.avatar_chai,
    "saturn" to R.drawable.avatar_saturn,
    "prism" to R.drawable.avatar_prism,
    "lantern" to R.drawable.avatar_lantern,
    "fireflies" to R.drawable.avatar_fireflies,
    "storm" to R.drawable.avatar_storm,
    "marble" to R.drawable.avatar_marble,
    "lighthouse" to R.drawable.avatar_lighthouse,
    "chaiowl" to R.drawable.avatar_chaiowl,
    "axolotl" to R.drawable.avatar_axolotl,
    "capybara" to R.drawable.avatar_capybara,
    "moth" to R.drawable.avatar_moth,
    "booksnail" to R.drawable.avatar_booksnail,
    "raccoon" to R.drawable.avatar_raccoon,
    "mushfrog" to R.drawable.avatar_mushfrog,
    "pomodoro" to R.drawable.avatar_pomodoro,
    "gradduck" to R.drawable.avatar_gradduck,
    "crane" to R.drawable.avatar_crane,
    "cassette" to R.drawable.avatar_cassette,
    "bonsai" to R.drawable.avatar_bonsai,
    "mooncat" to R.drawable.avatar_mooncat,
    "reading" to R.drawable.avatar_reading,
    "kulhad" to R.drawable.avatar_kulhad,
    "trail" to R.drawable.avatar_trail,
    "compass" to R.drawable.avatar_compass,
    "vinyl" to R.drawable.avatar_vinyl,
    "paperplane" to R.drawable.avatar_paperplane,
    "pottedcactus" to R.drawable.avatar_pottedcactus,
    "balloon" to R.drawable.avatar_balloon,
    "flame" to R.drawable.avatar_flame,
    "toadstools" to R.drawable.avatar_toadstools,
    "sunrise" to R.drawable.avatar_sunrise,
)

private val approvedArtwork: List<AvatarArtwork> = PresetAvatars.all.mapNotNull { p ->
    avatarResources[p.id]?.let { AvatarArtwork(p.id, p.label, it) }
}

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
        val bitmap = artwork?.let { avatarBitmap(it.resource) }
        if (bitmap != null) Image(bitmap, contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else Text(name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun PresetAvatarPicker(name: String, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
        AutoSizeText("Choose an avatar", style = MaterialTheme.typography.titleSmall)
        Text("Bundled presets only. No photo uploads.", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        // Category tabs: Orbs and Stitched. Opens on the tab holding the current pick.
        var tab by rememberSaveable { mutableIntStateOf(PresetAvatars.categoryOf(selectedId)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetAvatars.categories.forEachIndexed { index, category ->
                val on = index == tab
                Box(
                    Modifier.weight(1f).clip(CircleShape)
                        .background(if (on) colors.secondaryContainer else colors.surfaceContainerHigh)
                        .border(BorderStroke(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline), CircleShape)
                        .selectable(on, role = Role.Tab, onClick = { tab = index })
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(category.name, style = MaterialTheme.typography.labelLarge,
                        color = if (on) colors.onSecondaryContainer else colors.onSurface)
                }
            }
        }
        val tabIds = PresetAvatars.categories[tab.coerceIn(PresetAvatars.categories.indices)].presets.map { it.id }
        // Three per row keeps each 24-avatar category to eight compact rows in the scrolling dialog.
        approvedArtwork.filter { it.id in tabIds }.chunked(PICKER_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { artwork ->
                    val selected = selectedId == artwork.id
                    Column(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                            .border(BorderStroke(if (selected) 2.dp else 1.dp,
                                if (selected) colors.primary else colors.outline), MaterialTheme.shapes.medium)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(artwork.id) })
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProfileAvatar(name, artwork.id, 48)
                        Text(artwork.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) colors.onSecondaryContainer else colors.onSurface)
                        Text(if (selected) "Selected" else "Select", style = MaterialTheme.typography.labelSmall,
                            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                    }
                }
                repeat(PICKER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        TextButton(onClick = { onSelect("") }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (selectedId.isEmpty()) "Use initial, selected" else "Use initial instead of preset avatar"
            }) { AutoSizeText(if (selectedId.isEmpty()) "Initial selected" else "Use initial instead") }
    }
}
