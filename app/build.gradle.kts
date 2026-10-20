import java.io.InputStream
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionCode = 76
val appVersionName = "1.7.1"
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

// English and Chinese learner data: only these data files are packaged, none of the libraries' code.
val learnerData by configurations.creating { isTransitive = false }

abstract class LearnerLanguageData : DefaultTask() {
    @get:InputFiles
    abstract val artifacts: ConfigurableFileCollection

    /** Data kept in the repository (word levels), copied as they are. */
    @get:InputFiles
    abstract val bundled: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun extract() {
        // Entry in a downloaded artifact -> file the app reads from learner-data/.
        val wanted =
            mapOf(
                "dict.txt" to "zh-words.txt",
                "data/dictionary/pinyin/pinyin.txt" to "zh-pinyin.txt",
                "data/dictionary/tc/t2s.txt" to "zh-t2s.txt",
                "opennlp-en-ud-ewt-pos-1.3-2.5.4.bin" to "en-pos.zip",
            )
        val directory = output.dir("learner-data").get().asFile
        directory.mkdirs()
        val found = mutableSetOf<String>()
        artifacts.files.forEach { jar ->
            ZipFile(jar).use { zip ->
                wanted.forEach { (entry, name) ->
                    zip.getEntry(entry)?.let {
                        val target = File(directory, name)
                        zip.getInputStream(it).use { input ->
                            if (entry == "dict.txt") writeSortedWords(input, target) else target.outputStream().use(input::copyTo)
                        }
                        found += entry
                    }
                }
            }
        }
        val missing = wanted.keys - found
        if (missing.isNotEmpty()) throw GradleException("Learner data entries not found: $missing")
        val wordNet = artifacts.files.firstOrNull { jar -> ZipFile(jar).use { it.getEntry("$WORDNET/index.verb") != null } }
            ?: throw GradleException("WordNet data not found")
        ZipFile(wordNet).use { writeWordNet(it, directory) }
        bundled.files.forEach { it.copyTo(File(directory, it.name), overwrite = true) }
    }

    /**
     * WordNet's single words with their parts of speech (`run nv`: n noun, v verb, a adjective,
     * r adverb), sorted, and its irregular forms (`went v go`), for English dictionary forms.
     */
    private fun writeWordNet(
        zip: ZipFile,
        directory: File,
    ) {
        val word = Regex("[a-z][a-z'-]*")
        val parts = sortedMapOf<String, String>()
        val exceptions = mutableListOf<String>()
        listOf("noun" to 'n', "verb" to 'v', "adj" to 'a', "adv" to 'r').forEach { (file, letter) ->
            zip.getInputStream(zip.getEntry("$WORDNET/index.$file")).bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val lemma = line.substringBefore(' ')
                if (!line.startsWith(' ') && word.matches(lemma)) parts[lemma] = parts[lemma].orEmpty() + letter
            }
            zip.getInputStream(zip.getEntry("$WORDNET/$file.exc")).bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val fields = line.trim().split(' ')
                if (fields.size >= 2 && fields.all(word::matches)) exceptions += "${fields[0]} $letter ${fields.drop(1).joinToString(" ")}"
            }
        }
        File(directory, "en-words.txt").bufferedWriter(Charsets.UTF_8).use { out ->
            parts.forEach { (lemma, letters) -> out.write("$lemma $letters\n") }
        }
        File(directory, "en-exceptions.txt").bufferedWriter(Charsets.UTF_8).use { out ->
            exceptions.sorted().forEach { out.write(it); out.write("\n") }
        }
    }

    private companion object {
        const val WORDNET = "net/sf/extjwnl/data/wordnet/wn31"
    }

    /**
     * jieba's word list sorted by word (String order), so the app reads it straight into arrays.
     * Words seen fewer than 4 times are dropped: mostly rare names, and phrases such as 坐飞机 that
     * would otherwise win over their own words.
     */
    private fun writeSortedWords(
        input: InputStream,
        target: File,
    ) {
        val lines =
            input
                .bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trim().split(' ') }
                .filter { it.size >= 2 && (it[1].toIntOrNull() ?: 0) >= 4 }
                .sortedBy { it[0] }
        target.bufferedWriter(Charsets.UTF_8).use { out ->
            lines.forEach { out.write(it.joinToString(" ")); out.write("\n") }
        }
    }
}

val learnerLanguageData by tasks.registering(LearnerLanguageData::class) {
    artifacts.from(learnerData)
    bundled.from(fileTree("src/main/learner-data"))
    output.set(layout.buildDirectory.dir("generated/learner-language-data"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(learnerLanguageData, LearnerLanguageData::output)
        // Unit tests read the same files from the classpath.
        variant.hostTests.values.forEach { hostTest ->
            hostTest.sources.resources?.addGeneratedSourceDirectory(learnerLanguageData, LearnerLanguageData::output)
        }
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addGeneratedSourceDirectory(japaneseTestDictionary, JapaneseTestDictionary::output)
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // Media session and media notification only (earphone buttons); the video stays in the WebView.
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Japanese morphological analysis (MeCab IPADIC dictionary), Apache-2.0, pure Java.
    // The APK keeps only its code; the dictionary files are excluded from packaging below.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    japaneseDictionary("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Data files only (see LearnerLanguageData): English part-of-speech model (Apache-2.0), WordNet 3.1's
    // word and exception lists (WordNet licence), jieba's Chinese word list (MIT, in an Apache-2.0 jar),
    // HanLP's pinyin and character tables (Apache-2.0).
    learnerData("org.apache.opennlp:opennlp-models-pos-en:1.3.0")
    learnerData("net.sf.extjwnl:extjwnl-data-wn31:1.2")
    learnerData("com.huaban:jieba-analysis:1.0.2")
    learnerData("com.hankcs:hanlp:portable-1.8.6")
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
