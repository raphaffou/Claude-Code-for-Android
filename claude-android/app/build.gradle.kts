import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinAndroid)
    base
}

base {
    archivesName.set("claude-messenger")
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simplemobiletools.smsmessenger"
        minSdk = 23
        targetSdk = 34
        versionName = "1.0"
        versionCode = 1
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    namespace = "com.simplemobiletools.smsmessenger"

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
}
