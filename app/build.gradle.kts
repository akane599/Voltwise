@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apk.dist)
    alias(libs.plugins.kotlin.parcelize)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.set(
            listOf(
                "androidx.compose.material3.ExperimentalMaterial3Api",
                "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "androidx.compose.foundation.ExperimentalFoundationApi",
                "androidx.compose.foundation.layout.ExperimentalLayoutApi"
            )
        )
    }
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "com.akane.voltwise"
        minSdk = 26
        targetSdk = 36
        versionCode = 736
        versionName = "6.2.7-dev"
        manifestPlaceholders["appLabel"] = "Voltwise"

        androidResources {
            localeFilters += setOf("en", "es", "tr")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

   
    val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
    val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
    val targetAbi = providers.gradleProperty("targetAbi").orNull

    splits {
        abi {
            isEnable = enableApkSplits
            reset()
            if (enableApkSplits) {
                if (targetAbi != null) {
                    include(targetAbi)
                } else {
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
            isUniversalApk = includeUniversalApk && enableApkSplits
        }
    }

    signingConfigs {
        val previewVariables = listOf("PREVIEW_KEYSTORE_PATH", "PREVIEW_STORE_PASSWORD", "PREVIEW_KEY_ALIAS", "PREVIEW_KEY_PASSWORD")
        if (previewVariables.any { !System.getenv(it).isNullOrBlank() }) {
            require(previewVariables.all { !System.getenv(it).isNullOrBlank() }) {
                "Preview signing requires all four PREVIEW_* signing variables; see docs/BUILD_AND_INSTALL.md"
            }
            create("preview") {
                storeFile = file(System.getenv("PREVIEW_KEYSTORE_PATH"))
                storePassword = System.getenv("PREVIEW_STORE_PASSWORD")
                keyAlias = System.getenv("PREVIEW_KEY_ALIAS")
                keyPassword = System.getenv("PREVIEW_KEY_PASSWORD")
            }
        }
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("Long", "BUILD_TIME", "0L")
            isShrinkResources = true
        }
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Voltwise Debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            manifestPlaceholders["appLabel"] = "Voltwise Preview"
            signingConfig = signingConfigs.findByName("preview") ?: signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "com.akane.voltwise"

    testOptions {
        // Required by the screenshot suite: layoutlib resolves themes/layouts from compiled resources.
        unitTests.isIncludeAndroidResources = true
        // Compose Preview Screenshot Testing as an AGP test suite: @PreviewTest previews live in src/screenshotTest.
        screenshotTests.create("screenshotTest") {
            engineVersion = libs.versions.screenshot.get()
            targetVariants.add("debug")
            dependencies {
                implementation(libs.androidx.ui.tooling)
                // @Preview for the @PreviewTest functions; main has no previews.
                implementation(libs.androidx.ui.tooling.preview)
                implementation(libs.screenshot.validation.api)
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// The screenshot suite renders and diffs ~200 previews in one test JVM; the default heap runs out.
tasks.withType<Test>().configureEach {
    if (name.endsWith("ScreenshotTestDefaultTestSuite")) maxHeapSize = "2g"
}

apkDist {
    artifactNamePrefix = "batstats"
    // Keep distribution copies separate from AGP artifacts consumed by device tests/install tasks.
    distDirectory.set(layout.buildDirectory.dir("outputs/distribution"))
}

androidComponents {
    beforeVariants(selector().withBuildType("preview")) { variant ->
        // AGP 9 defaults host tests to the instrumentation build type (debug).
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

// Screenshot suites render in the host test JVM: pin timezone/locale so formatted dates match on every machine.
tasks.withType<Test>().configureEach {
    if (name.contains("ScreenshotTest")) {
        systemProperty("user.timezone", "UTC")
        systemProperty("user.language", "en")
        systemProperty("user.country", "US")
    }
}

// Configure all tasks that are instances of AbstractArchiveTask
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    ksp(libs.androidx.room.compiler)
    implementation(libs.core.ktx)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.runtime)

    implementation(libs.kmp.settings.core)
    implementation(libs.kmp.settings.ui.compose)
    ksp(libs.kmp.settings.ksp)

    // Material 3 (Compose); the XML theme parent is the platform's Theme.Material, so no MDC library.
    implementation(libs.material3.android)

    // Compose dependencies
    // Settings UI otherwise upgrades Material3 alone to an alpha with an incompatible Style ABI.
    // Its Material3 references are available in the stable BOM; enforce the same set in app/tests.
    val composeBom = enforcedPlatform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Shizuku
    implementation(libs.api)
    implementation(libs.provider)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // JVM regression tests and device integration/UI checks.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Compose's transitive Espresso 3.5.0 uses InputManager.getInstance, removed on API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.androidx.ui.tooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.kotlinx.coroutines.android)
}
