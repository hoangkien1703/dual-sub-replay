plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = 52
val appVersionName = "1.3.7"
val releaseStoreFile = providers.environmentVariable("ANDROID_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
val testReleaseSigning = providers.gradleProperty("testReleaseSigning").orNull == "true"
val previewVersionCode = providers.gradleProperty("previewVersionCode").orNull?.toIntOrNull()
val previewVersionNameSuffix = providers.gradleProperty("previewVersionNameSuffix").orNull.orEmpty()
val previewApplicationIdSuffix = providers.gradleProperty("previewApplicationIdSuffix").orNull.orEmpty()

// "full" (default) ships Google ML Kit translation; "fdroid" builds only free
// software for F-Droid. Each variant adds its own src/<distribution>/java.
val distribution = providers.gradleProperty("distribution").orNull ?: "full"
if (distribution !in setOf("full", "fdroid")) {
    throw GradleException("-Pdistribution must be full or fdroid, not $distribution.")
}
val isFdroidBuild = distribution == "fdroid"

if (requireReleaseSigning && !hasReleaseSigning) {
    throw GradleException(
        "Release signing requires ANDROID_RELEASE_STORE_FILE, " +
            "ANDROID_RELEASE_STORE_PASSWORD, ANDROID_RELEASE_KEY_ALIAS, and " +
            "ANDROID_RELEASE_KEY_PASSWORD.",
    )
}

// The exact artifact the app downloads, packaged into the device-test APK so the device test
// installs and loads it without network.
val japaneseDictionary by configurations.creating { isTransitive = false }

abstract class JapaneseTestDictionary : DefaultTask() {
    @get:InputFiles
    abstract val artifact: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        artifact.singleFile.copyTo(output.file("japanese-dictionary.jar").get().asFile, overwrite = true)
    }
}

val japaneseTestDictionary by tasks.registering(JapaneseTestDictionary::class) {
    artifact.from(japaneseDictionary)
    output.set(layout.buildDirectory.dir("generated/japanese-test-dictionary"))
}

// The F-Droid build's licence screen shows the licence files of the native translation engine,
// read from the submodules so they always match the compiled code. A missing file fails the build.
val engineLicenses = listOf(
    "Bergamot translator (mozilla/translations), MPL-2.0" to "translations/LICENSE",
    "Marian NMT, MIT" to "translations/inference/marian-fork/LICENSE.md",
    "ssplit-cpp, Apache-2.0" to "translations/inference/3rd_party/ssplit-cpp/LICENSE.md",
    "PCRE2, BSD-3-Clause with the PCRE2 exception" to "pcre2/LICENCE",
) + listOf(
    "SentencePiece" to "sentencepiece/LICENSE",
    "ruy" to "ruy/LICENSE",
    "cpuinfo" to "ruy/third_party/cpuinfo/LICENSE",
    "intgemm" to "intgemm/LICENSE",
    "faiss" to "faiss/LICENSE",
    "yaml-cpp" to "yaml-cpp/LICENSE",
    "pathie-cpp" to "pathie-cpp/LICENSE",
    "simd_utils" to "simd_utils/LICENSE",
    "CLI11" to "CLI/LICENSE",
    "spdlog" to "spdlog/LICENSE",
    "phf" to "phf/LICENSE",
    "cnpy" to "cnpy/LICENSE",
    "mio" to "mio/LICENSE",
    "zstr" to "zstr/LICENSE",
    "zlib" to "zlib/README",
).map { (name, path) -> "$name (bundled with Marian)" to "translations/inference/marian-fork/src/3rd_party/$path" }

abstract class EngineLicenseNotice : DefaultTask() {
    @get:Input
    abstract val titles: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val sections = titles.get().zip(licenseFiles.files.toList()) { title, file ->
            "===== $title =====\n\n${file.readText().trim()}"
        }
        output.file("licenses/distribution.txt").get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "TRANSLATION ENGINE\n\nThis build translates with Mozilla's Bergamot engine, built from source. " +
                    "Its components and their licences follow.\n\n" + sections.joinToString("\n\n\n") + "\n",
            )
        }
    }
}

val engineLicenseNotice by tasks.registering(EngineLicenseNotice::class) {
    titles.set(engineLicenses.map { it.first })
    licenseFiles.from(engineLicenses.map { "src/fdroid/cpp/${it.second}" })
    output.set(layout.buildDirectory.dir("generated/engine-licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(japaneseTestDictionary, JapaneseTestDictionary::output)
        }
        if (isFdroidBuild) {
            variant.sources.assets?.addGeneratedSourceDirectory(engineLicenseNotice, EngineLicenseNotice::output)
        }
    }
}

android {
    namespace = "com.kienhoang.dualsubreplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kienhoang.dualsubreplay"
        minSdk = 26
        targetSdk = 36
        versionCode = previewVersionCode ?: appVersionCode
        versionName = appVersionName + previewVersionNameSuffix

        // Safe Browsing sends URL checks to Google, so the F-Droid build turns it off.
        val safeBrowsing = (!isFdroidBuild).toString()
        buildConfigField("boolean", "WEBVIEW_SAFE_BROWSING", safeBrowsing)
        manifestPlaceholders["webViewSafeBrowsing"] = safeBrowsing

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/$distribution/java")
            assets.srcDir("src/$distribution/assets")
        }
        if (isFdroidBuild) {
            // Device test for the native engine; CI fills the assets with
            // tools/fetch_bergamot_test_models.py.
            getByName("androidTest") {
                kotlin.srcDir("src/fdroidAndroidTest/java")
                assets.srcDir("build/fdroid-test-models")
            }
        }
    }

    if (isFdroidBuild) {
        // F-Droid rejects the encrypted dependency blob AGP adds to the signing block.
        dependenciesInfo {
            includeInApk = false
            includeInBundle = false
        }

        // Mozilla's Bergamot translator, built from the src/fdroid/cpp submodules.
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                path = file("src/fdroid/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        defaultConfig {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    // Marian is unusably slow unoptimised, so debug builds use Release too.
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
                    targets += "dualsub_bergamot"
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("production") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            if (previewApplicationIdSuffix.isNotBlank()) {
                applicationIdSuffix = previewApplicationIdSuffix
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (previewApplicationIdSuffix.isNotBlank()) {
                applicationIdSuffix = previewApplicationIdSuffix
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("production")
            } else if (testReleaseSigning) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
            isMinifyEnabled = !providers.gradleProperty("profileGeneration").isPresent
            isShrinkResources = isMinifyEnabled
            matchingFallbacks += "release"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            // Kuromoji's 13 MB dictionary is downloaded on first Japanese use (JapaneseDictionaryStore).
            "com/atilika/kuromoji/ipadic/*.bin",
        )
        // Both Kuromoji jars ship the same license, notice and contributor files.
        resources.pickFirsts += setOf(
            "META-INF/CONTRIBUTORS.md",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE.md",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true

        managedDevices {
            localDevices {
                create("pixel2Api36") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))

    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    if (!isFdroidBuild) {
        implementation("com.google.mlkit:translate:17.0.3")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Japanese morphological analysis (MeCab IPADIC dictionary), Apache-2.0, pure Java.
    // The APK keeps only its code; the dictionary files are excluded from packaging below.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    japaneseDictionary("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Put screenshot evidence alongside the reports already uploaded by CI.
val collectUiEvidence by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/managed_device_android_test_additional_output"))
    from(layout.buildDirectory.dir("intermediates/managed_device_android_test_additional_output"))
    include("**/*.png")
    into(layout.buildDirectory.dir("reports/androidTests/managedDevice/ui-evidence"))
}
tasks.matching { it.name == "pixel2Api36DebugAndroidTest" }.configureEach {
    finalizedBy(collectUiEvidence)
}
