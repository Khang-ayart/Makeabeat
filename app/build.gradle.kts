plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bandstudio.app"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.bandstudio.app"
        minSdk = 26
        targetSdk = 35
        // CI truyền số lần chạy làm versionCode (luôn tăng) để các bản cài đè lên nhau và để app so sánh phiên bản khi kiểm tra cập nhật.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.2.0-dev"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Khóa ký cố định cho mọi bản phát hành (để cài đè / cập nhật được). CI giải mã khóa từ GitHub Secrets và đặt các biến môi trường
    // này; không có khóa (máy khác, PR từ fork) thì ký bằng khóa debug để vẫn dựng được, nhưng bản đó KHÔNG cập nhật đè lên bản chính thức.
    val releaseKeystore = System.getenv("MAKEABEAT_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { File(it) }
    if (releaseKeystore != null && releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("MAKEABEAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAKEABEAT_KEY_ALIAS")
                keyPassword = System.getenv("MAKEABEAT_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        prefab = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Engine âm thanh độ trễ thấp (AAudio/OpenSL ES) của Google.
    implementation("com.google.oboe:oboe:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json thật cho unit test (android.jar chỉ có bản rỗng)
}
