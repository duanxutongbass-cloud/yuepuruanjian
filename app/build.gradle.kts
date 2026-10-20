plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.ensemble.scorelibrary"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ensemble.scorelibrary"
        minSdk = 26
        targetSdk = 36
        versionCode = 45
        versionName = "0.10.33"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig { buildConfigField("boolean", "HTTP_TEST", "false") }
    // Temporary release history requested for the testing period. Commercial builds must set false.
    defaultConfig { buildConfigField("boolean", "TEST_RELEASE_NOTES", "true") }
    // Temporary operation records go only to the user's NAS; disabled in formal release builds.
    defaultConfig { buildConfigField("boolean", "TEST_OPERATION_LOGS", "true") }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // User chose the existing NAS channel: retain its certificate so
            // installed copies can upgrade without uninstalling their data.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "TEST_OPERATION_LOGS", "false")
            buildConfigField("boolean", "TEST_RELEASE_NOTES", "false")
        }
        create("syncTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".httptest"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "HTTP_TEST", "true")
        }
    }
    sourceSets.getByName("syncTest").java.srcDir("src/debug/java")
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    sourceSets.getByName("test").resources.srcDir("schemas")
    testOptions.unitTests.isIncludeAndroidResources = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
