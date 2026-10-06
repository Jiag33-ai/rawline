import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
// CI: the run number, as before. A local build is 1 unless asked otherwise (RAWLINE_VERSION_CODE or -PversionCode=N), for
// example to install over a newer sideloaded build. Not automatic: a large local number would block later CI builds.
val versionCodeOverride = (System.getenv("RAWLINE_VERSION_CODE") ?: providers.gradleProperty("versionCode").orNull)?.toIntOrNull()

android {
    namespace = "app.rawline"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.rawline"
        minSdk = 31
        targetSdk = 37
        versionCode = versionCodeOverride?.takeIf { it > 0 } ?: maxOf(buildNumber, 1)
        versionName = "0.1.$buildNumber"
        // Only arm64-v8a (the phone). Dependency AARs (LiteRT, graphics-path) ship x86, x86_64 and armeabi-v7a copies that the
        // filter inside core/native does not reach; they were about 22 MB of every sideload update.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("int", "BUILD_NUMBER", "$buildNumber")
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
        // Studio (docs/STUDIO_SPEC.md): off unless a build asks for it with -PstudioEnabled=true. S1b has no entry gated by it; the only way in is the debug-only Studio screen
        // (src/debug), which is not compiled into release. S1c puts the mode switch behind this flag.
        buildConfigField("boolean", "STUDIO_ENABLED", "${providers.gradleProperty("studioEnabled").orNull == "true"}")
    }
    signingConfigs {
        create("release") {
            // Secrets win; otherwise the committed sideload key is used so every build is signed with the same key and
            // installs over the previous one. It is not secret (personal sideload app): replace it with secrets if that matters.
            val ks = System.getenv("RAWLINE_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("RAWLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAWLINE_KEY_ALIAS")
                keyPassword = System.getenv("RAWLINE_KEY_PASSWORD")
            } else {
                storeFile = file("rawline-sideload.jks")
                storePassword = "rawline-sideload"
                keyAlias = "rawline"
                keyPassword = "rawline-sideload"
            }
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("release") }
        release {
            // Off on purpose (docs/DECISIONS.md): R8 problems only show at run time and nothing here can run the app. The rules
            // are ready; `-PminifyRelease=true` builds with them so assembleRelease can be proven to still work.
            isMinifyEnabled = providers.gradleProperty("minifyRelease").orNull == "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    implementation(project(":core:model"))
    implementation(project(":core:cache"))
    implementation(project(":core:data"))
    implementation(project(":feature:library"))
    implementation(project(":core:render"))
    implementation(project(":core:ml"))
    implementation(project(":feature:loupe"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:masking"))
    implementation(project(":feature:remove"))
    implementation(project(":feature:export"))
    implementation(libs.androidx.exifinterface)
    implementation(project(":feature:settings"))
    // the Studio canvas is only reachable from the debug build (DebugEntry in src/debug); release has neither the code nor the entry
    debugImplementation(project(":feature:studio"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
