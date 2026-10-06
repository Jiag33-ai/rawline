plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "app.rawline.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // the DNG probe tests read the 8 KB heads of synthetic DNGs (W15)
        unitTests.all { it.systemProperty("imp.dir", file("src/test/resources/dng").path) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:cache"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.sqlite.jdbc)
}
