plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.rawline.core.model"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // the copy rules test (docs/COPY.md) reads the repository's resources, Kotlin literals and docs
        unitTests.all {
            it.systemProperty("repo.root", rootDir.path)
            it.systemProperty("res.dir", File(rootDir, "core/ui/src/main/res/values").path)
            // the test reads files outside this module, so they are inputs: an edit anywhere in user facing text must re-run it
            it.inputs.files(fileTree(rootDir) {
                include("**/*.kt", "**/strings*.xml", "docs/*.md", "docs/copy-allow.txt")
                exclude("**/build/**", "**/.gradle/**", ".git/**", ".android-sdk/**", ".claude/**", "dist/**", "docs/backlog/**")
            }).withPropertyName("copySources").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
