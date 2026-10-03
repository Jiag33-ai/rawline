plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.rawline.core.cache"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:native"))
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
}
