plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fk2603.wakeup"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fk2603.wakeup"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Compose は debug ビルドだと重い。Build Variants で release を選ぶと軽い版を ▶ で入れられる
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // UI プロトタイプ（lucide）のアイコンの代わり。release では R8 が使っていないものを削る
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Firebase が引き込む古い Fragment だと権限リクエストが正しく動かないので上げる
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // M5Stack の QR を読む（Google の読み取り画面を使うので、アプリにカメラの権限は要らない）
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    testImplementation("junit:junit:4.13.2")
}
