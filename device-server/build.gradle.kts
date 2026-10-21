import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.graalvm.buildtools)
    id("io.github.ben-manes.versions") version "0.66.0"
    id("com.badoo.graalvm.metadata-generator")
}

group = "com.badoo.automation"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass = "com.badoo.automation.deviceserver.ProgramKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    implementation("org.apache.commons:commons-configuration2:2.15.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.4")

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host)

    testImplementation("org.mockito:mockito-core:5.24.0")

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.hamcrest:hamcrest-junit:2.0.0.0")
}

// Read by BuildInfo for GET /status/health; a native image has no jar manifest to carry the version.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    val buildVersion = project.version.toString()
    val gitCommit = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    inputs.property("version", buildVersion)
    outputs.dir(outputDir)
    doLast {
        val commit = gitCommit.getOrElse("").ifEmpty { "unknown" }
        outputDir.get().file("device-server-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$buildVersion\ngit_commit=$commit\n")
        }
    }
}

sourceSets.main {
    resources.srcDir(generateBuildInfo)
}

tasks.jar {
    manifest {
        attributes["Implementation-Version"] = project.version
        // Posix.kill calls libc through java.lang.foreign.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "ios-device-server"
            mainClass = "com.badoo.automation.deviceserver.ProgramKt"

            buildArgs.addAll(
                "--verbose",
                "--enable-monitoring=heapdump,jfr,threaddump",
                "--enable-native-access=ALL-UNNAMED",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+ForeignAPISupport",
                "-H:+ReportExceptionStackTraces",
                "-H:IncludeResources=logback.*\\.xml",
                "-H:IncludeResources=.*\\.properties",
                "-H:IncludeResources=.*\\.yml",
                "-H:IncludeResources=.*\\.yaml",
                "-H:+AddAllCharsets",
                "--enable-url-protocols=http,https",
                "--initialize-at-run-time=io.netty",
                "--initialize-at-run-time=io.ktor.network.selector.InterestSuspensionsMap",
                "--initialize-at-run-time=io.ktor.network.selector.SelectableBase",
                "-H:+ReportUnsupportedElementsAtRuntime"
            )
        }
    }
}
