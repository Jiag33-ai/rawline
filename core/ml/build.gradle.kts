plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.rawline.core.ml"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:cache"))
    api(project(":core:render"))
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.mlkit.subject)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
