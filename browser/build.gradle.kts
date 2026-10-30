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

    testOptions {
        unitTests {
            // Robolectric's `ShadowWebView` is a fake that records `loadUrl` and
            // does not execute JavaScript. It is enough to test *this module's
            // rules* - which URLs are refused, what a timeout says, how a page
            // becomes text - and it is not enough to test a page. The honest
            // split is written down in `BrowserTest`, where the limit matters.
            includeAndroidResources = true
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
