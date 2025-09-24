group = "com.github.blueboytm.flutter_v2ray"
version = "1.0"

buildscript {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
        namespace = "com.github.blueboytm.flutter_v2ray"
        compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        minSdk = 21
    }

    sourceSets {
        named("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("com.rhamadhany:libv2ray:1.0.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

}