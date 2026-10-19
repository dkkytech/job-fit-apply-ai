plugins {
    // Kotlin 2.x (unlike the other services' 1.9): the MCP Kotlin SDK is built against it.
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
    id("jacoco")
}

group = "com.jd"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.1"
val mcpVersion = "0.15.0"

dependencies {
    // MCP server (Streamable HTTP over Ktor) — the model-facing tool surface.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // MIME for reply drafts: parse a Gmail draft, swap its text, keep its attachments byte-exact.
    implementation("org.eclipse.angus:angus-mail:2.0.4")

    // Apply (Phase 4): drive jobbot-browser over CDP; registrable domains for credential keys.
    implementation("com.microsoft.playwright:playwright:1.63.0")
    implementation("com.google.guava:guava:33.5.0-jre")

    // Action table (idempotency, audit, undo records).
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // Configuration
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation("io.ktor:ktor-client-cio:$ktorVersion")
}

application {
    mainClass.set("com.jd.jobbot.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    systemProperty("dotenv.file", System.getProperty("dotenv.file", ".env"))
}

tasks.test {
    useJUnitPlatform()
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

kotlin {
    jvmToolchain(21)
}
