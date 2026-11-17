import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val pythonRuntimeDir = layout.buildDirectory.dir("generated/python-runtime")
val preparePythonRuntime = tasks.register<Exec>("preparePythonRuntime") {
    group = "build setup"
    description = "Downloads and stages checksum-pinned CPython Android packages."
    val script = rootProject.file("scripts/prepare-python-runtime.py")
    val outputDir = pythonRuntimeDir.get().asFile
    inputs.file(script)
    outputs.dir(outputDir)
    workingDir(rootProject.projectDir)
    commandLine("python3", script.absolutePath, "--output", outputDir.absolutePath)
}

val releaseKeystorePath = providers.environmentVariable("ANB_RELEASE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANB_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANB_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANB_RELEASE_KEY_PASSWORD").orNull
val releaseKeystoreExists = releaseKeystorePath?.let { file(it).isFile } == true
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() } && releaseKeystoreExists

android {
    namespace = "com.anbhosting.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.anbhosting.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANB_PYTHON_RUNTIME_DIR=${pythonRuntimeDir.get().asFile.absolutePath}",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                cppFlags += "-std=c++17"
            }
        }
    }
    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    ndkVersion = "27.3.13750724"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(pythonRuntimeDir.map { it.dir("jniLibs") })
            assets.srcDir(pythonRuntimeDir.map { it.dir("assets") })
        }
    }
    androidResources {
        // CPython's standard library relies on many files beginning with underscores.
        ignoreAssetsPattern = "anb-dont-ignore-any-assets"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

tasks.named("preBuild") {
    dependsOn(preparePythonRuntime)
}
tasks.configureEach {
    if (name.startsWith("configureCMake") ||
        name.startsWith("generateJsonModel") ||
        name.startsWith("buildCMake")
    ) {
        dependsOn(preparePythonRuntime)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
