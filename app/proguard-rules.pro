# Started by name with app_process over the phone's own ADB connection.
-keep class app.pocketportal.PanelPower { public static void main(java.lang.String[]); *; }
-keep class app.pocketportal.InputInjector { public static void main(java.lang.String[]); *; }
-keep class app.pocketportal.DexDisplay { public static void main(java.lang.String[]); *; }
# DexDisplay runs these in the shell process.
-keep class app.pocketportal.VideoEncoder { *; }
-keep enum app.pocketportal.VideoCodec { *; }
-keep enum app.pocketportal.Quality { *; }

# libadb-android and its certificate helper use reflection internally.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-dontwarn android.sun.security.**
-dontwarn org.conscrypt.**
