import org.superdro.composite.util.AndroidSdk
import org.superdro.composite.util.configureOptIns

plugins {
    alias(libs.plugins.androidLibrary)
}

// The UVC camera back end. The camera service runs in the process
// `:camera`. The native code (src/main/cpp) gives two libraries: libusb1.0.so (libusb, LGPL 2.1,
// a separate shared library) and libsuperdro_uvc.so (libuvc, BSD, and the JNI layer).

android {
    namespace = "org.superdro.platform.android.camera"
    compileSdk = AndroidSdk.COMPILE
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = AndroidSdk.MIN
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // arm64-v8a for the tablets. x86_64 so that the emulator can load the libraries.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // 16 KB pages (CMakeLists.txt also gives -Wl,-z,max-page-size=16384).
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }
    buildFeatures {
        aidl = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    testOptions {
        // The JVM unit tests use only the Kotlin parts that do not need a device.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    configureOptIns()
}

dependencies {
    api(projects.core.camera)

    implementation(libs.androidx.core)
    implementation(libs.arbor)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
}
