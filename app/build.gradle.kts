plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.colino17.jellyshim"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.colino17.jellyshim"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jellyfin.sdk:jellyfin-core:1.9.2")
    // The SDK logs through SLF4J but does not ship an implementation; this routes it to Logcat
    implementation("org.slf4j:slf4j-android:1.7.36")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
