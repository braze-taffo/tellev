# Keep extension bridge methods callable from WebView JavaScript.
-keepclassmembers class app.tellev.core.extension.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep attributes needed by reflection, annotations, and serialization.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# kotlinx.serialization: without these R8 strips the generated $$serializer
# companion `serializer()` methods, so every @Serializable class throws
# "Serializer for class 'X' is not found" at runtime.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the generated serializers for every @Serializable class in the app.
-keep,includedescriptorclasses class app.tellev.**$$serializer { *; }
-keepclassmembers class app.tellev.** {
    *** Companion;
}
-keepclasseswithmembers class app.tellev.** {
    kotlinx.serialization.KSerializer serializer(...);
}

