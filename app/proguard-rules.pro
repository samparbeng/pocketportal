# Started by name with app_process over the phone's own ADB connection.
-keep class app.pocketportal.PanelPower { public static void main(java.lang.String[]); *; }

# libadb-android and its certificate helper use reflection internally.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.security.** { *; }
-dontwarn android.sun.security.**
-dontwarn org.conscrypt.**
