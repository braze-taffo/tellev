# Keep extension bridge methods callable from WebView JavaScript.
-keepclassmembers class app.tellev.core.extension.** {
    @android.webkit.JavascriptInterface <methods>;
}

