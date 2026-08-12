# kotlinx.serialization keeps its generated serializers via @Serializable companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.galleryorganizer.** {
    *** Companion;
}
-keepclasseswithmembers class com.galleryorganizer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit bundled models.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
