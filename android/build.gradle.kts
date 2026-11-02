import org.gradle.api.GradleException

plugins {
    id("com.android.application")
}

// Release signing is opt-in. Never put credentials or keystores in this repository.
val personalGame = providers.gradleProperty("personalGame").orElse("false").get().toBoolean()
val gameApplicationId = providers.gradleProperty("gameApplicationId").orElse("com.midoali.atari").get()
val gameLabel = providers.gradleProperty("gameLabel").orElse("@string/app_name").get()

val signingEnvironmentNames = listOf(
    "ATARI_KEYSTORE_PATH",
    "ATARI_KEYSTORE_PASSWORD",
    "ATARI_KEY_ALIAS",
    "ATARI_KEY_PASSWORD",
)
val signingEnvironment = signingEnvironmentNames.associateWith {
    providers.environmentVariable(it).orNull.orEmpty()
}
val signingRequested = signingEnvironment.values.any { it.isNotBlank() }
val releaseSigningConfigured = signingEnvironment.values.all { it.isNotBlank() }
if (signingRequested && !releaseSigningConfigured) {
    val missing = signingEnvironment.filterValues { it.isBlank() }.keys
    throw GradleException("Incomplete release signing configuration. Missing: ${missing.joinToString()}")
}
if (releaseSigningConfigured) {
    val keystore = file(signingEnvironment.getValue("ATARI_KEYSTORE_PATH")).canonicalFile
    if (!keystore.isFile || keystore.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        throw GradleException("ATARI_KEYSTORE_PATH must point to an existing keystore outside the repository.")
    }
}

android {
    namespace = "com.midoali.atari"
    ndkVersion = "27.0.12077973"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = gameApplicationId
        manifestPlaceholders["appLabel"] = gameLabel
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
        buildConfigField("boolean", "PERSONAL_GAME", personalGame.toString())
    }

    externalNativeBuild {
        cmake {
            path = file("../core/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(signingEnvironment.getValue("ATARI_KEYSTORE_PATH"))
                storePassword = signingEnvironment.getValue("ATARI_KEYSTORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("ATARI_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("ATARI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86" } // Instrumentation on the 32-bit emulator.
            externalNativeBuild { cmake { arguments += "-DSEGA_TEST_API=ON" } }
            applicationIdSuffix = if (personalGame) "" else ".debug"
            versionNameSuffix = if (personalGame) "-umk3" else "-debug"
            // AGP supplies the local Android debug signing configuration.
        }
        release {
            versionNameSuffix = if (personalGame) "-umk3" else ""
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    // ROMs are excluded by default. Enable packaging only for files you may distribute.
    val bundleRomsValue = providers.gradleProperty("bundleRoms").orElse("false").get()
    if (bundleRomsValue !in setOf("true", "false")) {
        throw GradleException("bundleRoms must be either true or false.")
    }
    val bundledRomsRequested = bundleRomsValue == "true"
    val generatedAssets = layout.buildDirectory.dir("generated/atari/assets")
    val prepareBundledRoms = tasks.register<Sync>("prepareBundledRoms") {
        into(generatedAssets.map { it.dir("roms") })
        if (bundledRomsRequested) {
            from(rootProject.layout.projectDirectory.dir("roms")) {
                include("*")
                exclude { element ->
                    !element.file.isFile ||
                        element.file.name.equals("README.md", ignoreCase = true) ||
                        element.file.extension.lowercase() !in setOf("a26", "bin", "a78", "md", "gen", "smd")
                }
            }
        }
        doFirst {
            if (bundledRomsRequested) {
                val roms = rootProject.file("roms").listFiles().orEmpty().filter {
                    it.isFile && it.extension.lowercase() in setOf("a26", "bin", "a78", "md", "gen", "smd")
                }
                if (roms.isEmpty()) {
                    throw GradleException("bundleRoms=true requires at least one .a26, .bin, .a78, .md, .gen, or .smd file in roms/.")
                }
            }
        }
    }
    // Register the asset root, not the task's individual output files (which would flatten roms/).
    sourceSets.getByName("main").assets.srcDir(prepareBundledRoms.map { generatedAssets.get() })
    tasks.named("preBuild") {
        dependsOn(prepareBundledRoms)
    }
}

base {
    archivesName = "sega-android"
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "A signed release requires ATARI_KEYSTORE_PATH, ATARI_KEYSTORE_PASSWORD, " +
                    "ATARI_KEY_ALIAS, and ATARI_KEY_PASSWORD. See docs/BUILD_APK.md. " +
                    "For an installable development build, use exportDebugApk instead.",
            )
        }
    }
}

// One APK containing arm64-v8a, armeabi-v7a and x86_64 native cores.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }
        tasks.register<Copy>("export${capitalizedName}Apk") {
            group = "distribution"
            description = "Build and export the $variantName APK to artifacts/apk/."
            dependsOn("assemble$capitalizedName")
            if (variantName == "release") {
                dependsOn(verifyReleaseSigning)
            }
            from(layout.buildDirectory.file("outputs/apk/$variantName/sega-android-$variantName.apk"))
            into(rootProject.layout.projectDirectory.dir("artifacts/apk"))
            rename { "sega-android-$variantName.apk" }
            doFirst {
                if (variantName == "release" && !releaseSigningConfigured) {
                    throw GradleException("Release signing is not configured; see verifyReleaseSigning.")
                }
            }
            doLast {
                val exported = rootProject.file("artifacts/apk/sega-android-$variantName.apk")
                check(exported.isFile && exported.length() > 0) { "APK export failed: $exported" }
                logger.lifecycle("Exported APK: ${exported.absolutePath}")
            }
        }
    }
}

// Refuse to produce an unsigned release: every exported release must be installable.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}
