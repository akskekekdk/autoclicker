plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uvm.autoclicker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uvm.autoclicker"
        minSdk = 24
        targetSdk = 34
        // CI가 -PversionCode=<번호>로 넘겨준다. 릴리스 태그 v<번호>와 일치해야 앱 내 업데이트가 동작한다.
        val code = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionCode = code
        versionName = "1.$code"
    }

    // 업데이트가 설치되려면 항상 같은 키로 서명해야 한다. 키는 GitHub Secrets에서 주입된다.
    val keystorePath = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "autoclicker"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
