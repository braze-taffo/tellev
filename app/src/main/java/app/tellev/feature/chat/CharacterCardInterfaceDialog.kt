package app.tellev.feature.chat

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellev.R

/** Mount the existing script runtime so card UI and event handlers keep the same JS state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterCardInterfaceDialog(webView: WebView, onDismiss: () -> Unit) {
    val background = Color(0xFF0A1416)
    DisposableEffect(webView) {
        onDispose { (webView.parent as? ViewGroup)?.removeView(webView) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.chat_character_card_interface)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = background,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_close_character_card_interface))
                        }
                    },
                )
                AndroidView(
                    factory = { FrameLayout(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    update = { frame ->
                        if (webView.parent !== frame) {
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            frame.removeAllViews()
                            frame.addView(webView, FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ))
                        }
                    },
                )
            }
        }
    }
}
