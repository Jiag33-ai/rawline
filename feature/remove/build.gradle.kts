plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.rawline.feature.remove"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:render"))
    implementation(project(":core:cache"))
    implementation(project(":core:ml"))
    implementation(project(":feature:editor"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
