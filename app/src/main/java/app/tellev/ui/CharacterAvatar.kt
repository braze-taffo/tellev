package app.tellev.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * Character avatar backed by the character card file itself (the card's PNG
 * is the avatar, SillyTavern style). Falls back to the classic first-letter
 * placeholder when there is no file (unsaved card), the file is not an image
 * (JSON cards), or decoding fails.
 */
@Composable
fun CharacterAvatar(
    file: File?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    fallbackTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Box(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = fallbackText.firstOrNull()?.uppercase()?.toString() ?: "?"
        if (file != null && file.exists()) {
            SubcomposeAsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {},
                error = {
                    Initials(initial, fallbackTextStyle)
                },
            )
        } else {
            Initials(initial, fallbackTextStyle)
        }
    }
}

@Composable
private fun Initials(initial: String, style: TextStyle) {
    Text(
        text = initial,
        style = style,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
    )
}
