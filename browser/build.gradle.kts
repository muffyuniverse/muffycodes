plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.notes.browser"
    compileSdk = 37

    defaultConfig {
        // The same floor as the app. `evaluateJavascript` is API 19 and
        // `WebView.getCurrentWebViewPackage` is API 26, so the app's own floor
        // is the binding constraint and this module adds none of its own.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // **`includeAndroidResources` is not available here**, and the build said so
    // before anything else could: it is an `android.testOptions.unitTests` option
    // of the *application* plugin, and this module uses the library plugin. The
    // tests below do not need it — they read `extract.js` from the module's own
    // resources through the class loader rather than through a merged asset path,
    // which is what that flag would have provided.
    testOptions {
        unitTests {
            // Robolectric's `ShadowWebView` is a fake that records `loadUrl` and
            // does not execute JavaScript. It is enough to test *this module's
            // rules* - which URLs are refused, how a JSON result becomes text -
            // and it is not enough to test a page. The honest split is written
            // down in `BrowserTest`, where the limit matters.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's `org.json` is a stub that throws at runtime, and `unquote` uses
    // `JSONTokener`. The JVM tests need a real implementation on the classpath -
    // the same reason the app's own tests have this line.
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
