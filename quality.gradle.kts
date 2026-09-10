// Standalone CLIs avoid coupling analyzer compiler plugins to the app's Kotlin compiler.
val ktlint by configurations.creating {
    attributes.attribute(org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
        objects.named(org.gradle.api.attributes.Bundling.SHADOWED))
}
val detektCli by configurations.creating
dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:1.7.1")
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

tasks.register<JavaExec>("formatReport") {
    group = "verification"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--reporter=baseline,output=build/reports/format.xml", "${workingDir.absolutePath.replace('\\', '/')}/app/src/**/*.kt", "${workingDir.absolutePath.replace('\\', '/')}/benchmark/src/**/*.kt")
    isIgnoreExitValue = true
    doLast { check(executionResult.get().exitValue in 0..1) { "ktlint failed to run" } }
}

tasks.register<JavaExec>("complexityCheck") {
    group = "verification"
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args("--input", "app/src/main/java,benchmark/src/main/java", "--config", "config/quality/detekt.yml",
        "--baseline", "config/quality/detekt-baseline.xml", "--report", "txt:build/reports/complexity.txt")
}

tasks.register<JavaExec>("formatNewCode") {
    group = "formatting"
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format")
    args(providers.gradleProperty("formatFiles").orElse("__no_files_selected__").get().split(",").map { file(it).absolutePath.replace('\\', '/') })
}

tasks.register<JavaExec>("recordFormatBaseline") {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = file(providers.gradleProperty("analysisRoot").orElse(".").get())
    args("--reporter=baseline,output=${rootProject.file("config/quality/ktlint-baseline.xml")}", "${workingDir.absolutePath.replace('\\', '/')}/app/src/**/*.kt", "${workingDir.absolutePath.replace('\\', '/')}/benchmark/src/**/*.kt")
    isIgnoreExitValue = true
}

tasks.register<JavaExec>("recordComplexityBaseline") {
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    workingDir = file(providers.gradleProperty("analysisRoot").orElse(".").get())
    args("--input", "app/src/main/java", "--config", rootProject.file("config/quality/detekt.yml").path,
        "--create-baseline", "--baseline", rootProject.file("config/quality/detekt-baseline.xml").path)
}

tasks.register<Exec>("formatCheck") {
    group = "verification"
    dependsOn("formatReport")
    commandLine("python", "tools/check_format_ratchet.py")
}
