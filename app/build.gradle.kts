plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Base64
import java.util.Properties

android {
    namespace = "com.example.notes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.notes"
        // JGit uses java.nio.file, which Android gained in API 26.
        minSdk = 26
        targetSdk = 35
        // The version can be set from the outside, so the build that the
        // continuous integration makes gets the number of the release.
        versionCode = (project.findProperty("muffy.versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("muffy.versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        create("release") {
            // The keystore and its passwords live outside the repository. They
            // come either from keystore.properties (a local build) or from
            // environment variables (a build on the continuous integration),
            // where the keystore is handed over as base64.
            val keystoreProperties = Properties().apply {
                val file = rootProject.file("keystore.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }

            val base64Key = System.getenv("MUFFY_KEYSTORE_BASE64")
            val storePasswordEnv = System.getenv("MUFFY_KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("MUFFY_KEY_ALIAS")
            val keyPasswordEnv = System.getenv("MUFFY_KEY_PASSWORD")

            var resolvedStoreFile: File? = null
            var resolvedStorePassword: String? = null
            var resolvedKeyAlias: String? = null
            var resolvedKeyPassword: String? = null

            if (base64Key != null && storePasswordEnv != null) {
                // A build on the continuous integration: the key arrives as
                // base64 and is written to a temporary file.
                val temp = File.createTempFile("muffy-release", ".jks")
                temp.deleteOnExit()
                temp.writeBytes(Base64.getDecoder().decode(base64Key))
                resolvedStoreFile = temp
                resolvedStorePassword = storePasswordEnv
                resolvedKeyAlias = keyAliasEnv ?: "notes"
                resolvedKeyPassword = keyPasswordEnv ?: storePasswordEnv
            } else {
                resolvedStoreFile = keystoreProperties.getProperty("storeFile")
                    ?.let { rootProject.file(it) }
                resolvedStorePassword = keystoreProperties.getProperty("storePassword")
                resolvedKeyAlias = keystoreProperties.getProperty("keyAlias")
                resolvedKeyPassword = keystoreProperties.getProperty("keyPassword")
            }

            val hasKeystore = resolvedStoreFile != null &&
                resolvedStoreFile.exists() &&
                resolvedStorePassword != null &&
                resolvedKeyAlias != null &&
                resolvedKeyPassword != null

            if (hasKeystore) {
                storeFile = resolvedStoreFile
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
            // Sign with v1 (JAR) and v3 as well as v2, so the APK installs on
            // older and newer Android versions alike.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * A release build that is not signed is worse than no build at all: it looks
 * fine and then cannot be installed. The check makes the build fail instead,
 * so the continuous integration never publishes an unsigned APK.
 */
gradle.taskGraph.whenReady {
    val hasRelease = allTasks.any { it.name == "assembleRelease" }
    if (hasRelease) {
        val releaseSigning = extensions.getByName("android")
            .let { it as com.android.build.gradle.AppExtension }
            .signingConfigs.getByName("release")
        val signed = releaseSigning.storeFile != null
        if (!signed) {
            throw GradleException(
                "The release build has no signing key. Put one in keystore.properties " +
                    "(storeFile, storePassword, keyAlias, keyPassword) or set the " +
                    "environment variables MUFFY_KEYSTORE_BASE64, MUFFY_KEYSTORE_PASSWORD, " +
                    "MUFFY_KEY_ALIAS and MUFFY_KEY_PASSWORD."
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws at runtime; the JVM unit tests
    // need a real implementation on the classpath.
    testImplementation("org.json:json:20231013")

    // JGit: a pure-Java git implementation, so no native code is needed on Android.
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
}
