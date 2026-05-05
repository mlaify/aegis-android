plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.aegis.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.aegis.android"
        // minSdk 26 (Android 8) — DataStore + Compose require modern APIs and
        // the JNA Android variant we use to load the AegisFFI .so libs needs
        // a recent libc. minSdk 26 also matches what cargo-ndk's --platform
        // 26 invocation produces.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // We ship .so libs for these ABIs only. Building for other ABIs
        // would require regenerating libaegis_ffi.so via build-jni.sh
        // with the matching --target.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // The JNI .so libs are staged into src/main/jniLibs/ by
    // ../scripts/build-jni.sh. Including the directory explicitly keeps
    // the path machine-readable when the directory is missing on a fresh
    // clone (Android Gradle Plugin warns otherwise).
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            // The UniFFI Kotlin bindings load libaegis_ffi via JNA.
            // On the JVM unit-test classpath JNA needs the host
            // architecture's library — `../aegis-ffi/target/release/` is
            // populated by ../scripts/build-jni.sh.
            it.systemProperty(
                "jna.library.path",
                rootProject.layout.projectDirectory.dir("../aegis-ffi/target/release").asFile.absolutePath,
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // UniFFI's generated Kotlin bindings load the .so libs through JNA.
    // The `@aar` classifier pulls JNA's Android-flavored variant which
    // bundles the JNA native shim for each ABI; without it the bindings
    // throw UnsatisfiedLinkError at runtime.
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // Plain JNA on the JVM-only unit-test classpath, so the bindings can
    // load the host's native lib (built by build-jni.sh's macOS slice
    // when running on Apple Silicon dev machines, or by CI on linux-x64).
    testImplementation(libs.jna)
}
