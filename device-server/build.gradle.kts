import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.21")
    }
}

apply(plugin = "org.jetbrains.kotlin.jvm")

plugins {
    application
}

repositories {
    jcenter()
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/ktor")
}

dependencies {
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.1.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.squareup.okhttp3:okhttp:3.9.1")
    implementation("io.ktor:ktor-server-netty:0.9.1")
    implementation("io.ktor:ktor-features:0.9.1")
    implementation("io.ktor:ktor-jackson:0.9.1")
    implementation("io.ktor:ktor-auth:0.9.1")

    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("net.logstash.logback:logstash-logback-encoder:4.11")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.9.2")

    implementation("net.java.dev.jna:jna:4.5.1")
    implementation("com.zaxxer:nuprocess:1.1.3")

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("junit:junit:4.12")
    testImplementation("com.nhaarman:mockito-kotlin:1.6.0")
    testImplementation("org.mockito:mockito-core:2.25.1")
    testImplementation("org.mockito:mockito-inline:2.25.1")
    testImplementation("org.hamcrest:hamcrest-junit:2.0.0.0")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

allprojects {
    group = "com.badoo.automation"
    version = "1.0-SNAPSHOT"
}

val mainClassName = "com.badoo.automation.deviceserver.ProgramKt"
application.mainClassName = mainClassName

tasks.withType<KotlinCompile> {
    kotlinOptions.suppressWarnings = false
    kotlinOptions.jvmTarget = "1.8"
}

val jar: Jar by tasks
jar.manifest.attributes["Main-Class"] = mainClassName
jar.from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
