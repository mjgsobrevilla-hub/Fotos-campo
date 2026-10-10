plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// En GitHub cada compilación sube el número de versión, así el APK nuevo
// se instala encima del anterior sin borrar los datos.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "pe.campo.camara"
    compileSdk = 34

    defaultConfig {
        applicationId = "pe.campo.camara"
        minSdk = 29          // Android 10 o superior
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("campo") {
            storeFile = file("campo.keystore")
            storeType = "pkcs12"
            storePassword = "campo123"
            keyAlias = "campo"
            keyPassword = "campo123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("campo")
        }
        debug {
            signingConfig = signingConfigs.getByName("campo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
