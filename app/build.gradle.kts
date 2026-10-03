import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

android {
    namespace = "app.rawline"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.rawline"
        minSdk = 31
        targetSdk = 37
        versionCode = maxOf(buildNumber, 1)
        versionName = "0.1.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
    }
    signingConfigs {
        create("release") {
            val ks = System.getenv("RAWLINE_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("RAWLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAWLINE_KEY_ALIAS")
                keyPassword = System.getenv("RAWLINE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("RAWLINE_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":core:ui"))
    implementation(project(":core:native"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
