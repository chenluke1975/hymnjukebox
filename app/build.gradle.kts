import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: if a keystore properties file exists (kept OUTSIDE git),
// the release build is signed with it. Unsigned builds (e.g. F-Droid) work without it.
val keystoreProps = Properties()
val keystorePropsFile = listOf(
    File("/workspace/keys/hymnjukebox-keystore.properties"),
    rootProject.file("keystore.properties"),
).firstOrNull { it.isFile }
keystorePropsFile?.inputStream()?.use { keystoreProps.load(it) }

android {
    namespace = "io.github.chenluke1975.hymnjukebox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.chenluke1975.hymnjukebox"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    if (keystorePropsFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
            if (keystorePropsFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
