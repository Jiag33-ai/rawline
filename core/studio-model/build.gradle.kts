plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.rawline.core.studio.model"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
