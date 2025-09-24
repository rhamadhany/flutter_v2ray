pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath!=null ) { "flutter.sdk is not set!"}
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()

    }

    plugins {
        id("com.android.library") version "8.9.1"
        id("org.jetbrains.kotlin.android") version "2.1.0"
    }
}
include(":src")
rootProject.name = "flutter_v2ray"
