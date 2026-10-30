plugins {
    id("com.android.application") version "9.2.0" apply false
    // The library plugin for `:browser`. Same version, because a module and the
    // app it is linked into must agree about the platform they are compiled for.
    id("com.android.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
