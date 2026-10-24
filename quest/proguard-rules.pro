# The viewer page calls these through window.PocketPortalApp.
-keepclassmembers class app.pocketportal.quest.QuestBridge {
    @android.webkit.JavascriptInterface <methods>;
}
