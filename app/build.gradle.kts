plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Which ABIs to package. CI passes the matrix it actually built
// (-PrixAbis=arm64-v8a or a comma list); the default keeps a local build sane.
val rixAbis: List<String> = ((findProperty("rixAbis") as String?) ?: "arm64-v8a")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "com.rix.vpn"
    compileSdk = 35
    // Pinned so AGP finds the NDK that CI installs under $ANDROID_HOME/ndk/.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.rix.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            // Driven by -PrixAbis so the ABI list here can never drift from the
            // matrix in .github/workflows/android-rix.yml. Shipping an ABI whose
            // libaether.so was not built would install fine and crash on launch.
            abiFilters += rixAbis
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cFlags += listOf("-O2", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            // Debug signing on purpose: the artifact is installed by hand, and a
            // release keystore must never live in CI secrets for this project.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}