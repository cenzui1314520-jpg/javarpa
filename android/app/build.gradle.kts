plugins {
    id("com.android.application")
}

android {
    namespace = "com.rpa.engine"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.rpa.engine"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // 开启混淆收缩，避免 OkHttp/Rhino 全量暴露被零成本逆向
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.7.14")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 扫码配置：纯 Java、无 androidx/appcompat 依赖，minSdk 24 与本工程对齐
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
