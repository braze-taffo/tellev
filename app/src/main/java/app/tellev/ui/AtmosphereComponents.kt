package app.tellev.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterPortraitCard(
    name: String,
    tags: List<String>,
    file: File?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.85f)) {
            CharacterCover(file, name, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
            )))
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { trailing() }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(tags.take(2).joinToString(" · ").ifBlank { "✦" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Presentation only: covers reuse the card image and never write to the card. */
@Composable
fun CharacterCover(file: File?, name: String, modifier: Modifier = Modifier) {
    val palettes = listOf(
        listOf(Color(0xFF684637), Color(0xFF282129)),
        listOf(Color(0xFF375953), Color(0xFF1D3038)),
        listOf(Color(0xFF5C4B70), Color(0xFF292635)),
        listOf(Color(0xFF6A5941), Color(0xFF353331)),
    )
    val colors = palettes[(name.hashCode() and Int.MAX_VALUE) % palettes.size]
    Box(
        modifier = modifier.background(Brush.linearGradient(colors)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.06f), size.width * 0.7f,
                Offset(size.width * 0.85f, size.height * 0.1f))
            drawCircle(Color.White.copy(alpha = 0.04f), size.width * 0.5f,
                Offset(size.width * 0.1f, size.height * 0.9f))
        }
        Text(
            name.take(1).ifBlank { "✦" },
            style = MaterialTheme.typography.displayLarge,
            color = Color.White.copy(alpha = 0.38f),
        )
        // The gradient/initial stays visible for JSON cards and failed image decodes.
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun AtmosphereIntro(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainerLow))),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(colors.primary.copy(alpha = 0.06f), size.width * 0.35f,
                Offset(size.width, 0f))
        }
        Row(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        color = colors.primary, fontWeight = FontWeight.SemiBold)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(18.dp), color = colors.surface.copy(alpha = 0.65f)) {
                Icon(icon, contentDescription = null, tint = colors.primary,
                    modifier = Modifier.padding(14.dp).size(28.dp))
            }
        }
    }
}

@Composable
fun QuietTag(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The wooden shelf plank the screenshot's library sits on. Drawn, not an image:
 * no asset, no decode, and it follows the current theme's surface tones.
 */
@Composable
fun ShelfPlank(modifier: Modifier = Modifier) {
    val shelf = MaterialTheme.colorScheme.surfaceContainerHighest
    val shelfEdge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(shelf),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.TopStart)
                .background(shelfEdge),
        )
    }
}

/**
 * One standing book on the shelf: a spine-strip card whose height follows the
 * name length (long titles read as thicker books), so the shelf looks like
 * books rather than a list. Presentation only — all interaction stays on the
 * caller's clickable modifier.
 */
@Composable
fun BookSpineCard(
    name: String,
    entryCount: Int,
    activated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val spineColors = listOf(
        Color(0xFF6B4A34), Color(0xFF3E5A50), Color(0xFF5A4A6B), Color(0xFF6B5A34), Color(0xFF4A5A6B),
    )
    val spine = spineColors[(name.hashCode() and Int.MAX_VALUE) % spineColors.size]
    val height = (96 + (name.length.coerceAtMost(14) * 4)).dp
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = spine,
        modifier = modifier.height(height),
    ) {
        Box(Modifier.fillMaxSize()) {
            // Spine highlight + edge, the two strokes that read as "book" at a glance.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.16f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.22f),
                            ),
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.28f)),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$entryCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (activated) "●" else "○",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activated) Color(0xFF9BE39B) else Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { trailing() }
        }
    }
}
