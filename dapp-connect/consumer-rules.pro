# Keep WebView JavaScript bridge interface methods.
-keepclassmembers class com.jccdex.toolkits.dappconnect.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.jccdex.toolkits.dappconnect.WebAppInterface { *; }

# Keep middleware provider types — called via interface from apps.
-keep class com.jccdex.toolkits.dappconnect.middleware.** { *; }
-keep class com.jccdex.toolkits.dappconnect.provider.** { *; }
