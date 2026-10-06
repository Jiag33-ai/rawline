plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.rawline.core.studio.render"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:studio-model"))
    api(project(":core:native"))
    implementation(project(":core:render"))
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
