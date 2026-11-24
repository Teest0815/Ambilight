# Die App ist klein und nutzt Klassen aus Layouts/Einstellungen per Name: eigenen Code nicht umbenennen.
-keep class com.teest.ambilight.** { *; }
-keepattributes *Annotation*
-dontwarn kotlinx.coroutines.**
