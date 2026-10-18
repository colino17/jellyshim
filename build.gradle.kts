// AGP 9 has Kotlin built in, but its bundled compiler (2.2) is too old to read the Jellyfin SDK 1.9.2
// and kotlin-stdlib 2.4.x (metadata 2.4). Putting a newer Kotlin Gradle plugin on the build classpath
// makes AGP use that compiler instead.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    // API 37 (required by the Jellyfin SDK 1.9.x / OkHttp 5.5) needs AGP 9.1+, which needs Gradle 9.3.1+.
    id("com.android.application") version "9.1.1" apply false
}
