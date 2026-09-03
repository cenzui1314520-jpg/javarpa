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
    // 扫码：zxing-android-embedded 把 androidx.core 声明为 compileOnly（由宿主自带），
    // 缺了会在打开相机时 NoClassDefFoundError 闪退，必须显式引入
    implementation("androidx.core:core:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Shizuku：借 shell 权限一键开启无障碍服务，免手动进系统设置
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
