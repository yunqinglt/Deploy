plugins {
    id("com.android.application")
}

android {
    buildFeatures { aidl = true }
    namespace = "dev.deploy"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.deploy"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
        externalNativeBuild { cmake { targets += "deploynative" } }

        // Only the ABI these devices use; LIEF will be statically linked in
        // here, so there is no reason to carry other architectures.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

// AndroidX declares this coordinate as a compile-time API dependency. The
// offline workspace contains only its POM, while the real API jar is supplied
// explicitly below.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

tasks.named("preBuild") {
    doFirst {
        check(file("src/main/assets/ghidra/AARCH64.sla").isFile) {
            "Ghidra language missing: run tools/prepare-ghidra.py in a host C++ build environment."
        }
    }
}

dependencies {
    // The AIDL contract Deploy implements and PosEdMCP binds to.
    implementation(project(":deploy-api"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    // ProfileInstaller's AndroidX futures implementation references Guava's
    // ListenableFuture interface. Keep the tiny API artifact in-tree so the
    // standalone Deploy APK remains runnable when built offline.
    implementation(files(rootProject.file("third_party/listenablefuture-1.0.jar")))
}
