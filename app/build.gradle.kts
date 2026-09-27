plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.localserver"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.localserver"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 只保留現代手機常見架構，armeabi-v7a 給少數舊機型
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // libnode.so 是用共享版 C++ 執行期（libc++_shared.so）編譯的，
                // 這裡宣告一致，AGP 才會自動把 libc++_shared.so 一起打包進 apk，
                // 不然執行期會找不到這個檔案，dlopen 直接失敗閃退。
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            // 把預先編譯好的 libnode.so 一起打包進 apk
            jniLibs.srcDirs("libnode/bin")
        }
    }

    packaging {
        jniLibs {
            // 注意：AGP 這個命名反直覺——false 才是「現代、不壓縮、直接從 apk mmap 讀取」，
            // true 是舊式「壓縮後安裝時解壓縮」，壓縮幾十 MB 的 libnode.so 很吃記憶體，容易 OOM。
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // 讀寫使用者透過系統資料夾選擇器授權的資料夾
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 輕量內建 HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
