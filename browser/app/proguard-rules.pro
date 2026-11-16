# JS bridge methods are called by name from page scripts.
-keepclassmembers class space.nami.browser.Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
