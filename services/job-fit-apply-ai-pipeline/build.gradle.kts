plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("jacoco")
    id("io.qameta.allure") version "2.11.2"
}

group = "com.jd"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // HTTP Client
    implementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")

    // Gmail API
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250331-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.35.0")

    // PDF Generation
    implementation("org.apache.pdfbox:pdfbox:3.0.1")

    // DOCX reading
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")

    // Jakarta Mail for Gmail
    implementation("com.sun.mail:javax.mail:1.6.2")

    // Playwright for PDF rendering and LinkedIn scraping.
    // driver-bundle includes the Playwright CLI and browser binaries (~150 MB).
    implementation("com.microsoft.playwright:playwright:1.42.0")

    // Configuration
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // CLI
    implementation("info.picocli:picocli:4.7.5")
    implementation("info.picocli:picocli-spring-boot-starter:4.7.5")

    // Rich console output
    implementation("org.jline:jline:3.24.1")
    implementation("org.fusesource.jansi:jansi:2.4.1")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.22")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("io.qameta.allure:allure-junit5:2.27.0")
    testImplementation("io.qameta.allure:allure-attachments:2.27.0")
}

application {
    mainClass.set("com.jd.pipeline.cli.Main")
}

tasks.named<JavaExec>("run") {
    // Propagate -Ddotenv.file=<path> from Gradle JVM to the application JVM so that
    // ./gradlew run -Ddotenv.file=.env.speed works as expected.
    systemProperty("dotenv.file", System.getProperty("dotenv.file", ".env"))
    // Forward stdin so interactive prompts (e.g. --reauth) can read user input.
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")

    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco"))
    }

    sourceDirectories.setFrom(files(listOf("src/main/kotlin")))
    classDirectories.setFrom(files(listOf("build/classes/kotlin/main")))
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/test.exec")
    })
}

allure {
    version.set("2.27.0")
    useJUnit5 {
        adapterVersion.set("2.27.0")
    }
}

kotlin {
    jvmToolchain(21)
}