plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ibabyblue.amedia.session"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    api(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.guava)
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
