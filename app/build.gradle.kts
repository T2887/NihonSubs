plugins {
    id("com.android.application")
}

android {
    namespace = "com.nihonsubs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nihonsubs"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 只支持 64 位 arm：模型与二进制都是 arm64 交叉编译的
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 可执行二进制以 lib*.so 形式放在 jniLibs，安装后才允许 exec
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
