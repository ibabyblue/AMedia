plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ibabyblue.amedia.preload"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":amedia-core"))
    api(project(":amedia-cache"))
    implementation(libs.androidx.media3.exoplayer)
    testImplementation(libs.junit)
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
