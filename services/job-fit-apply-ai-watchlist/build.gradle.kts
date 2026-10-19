plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("jacoco")
}

group = "com.jd"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // JSON (ATS feed responses + bridge submit) and YAML (employers.yaml). HTTP uses the JDK's
    // java.net.http client; RSS uses the JDK's XML parser.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")

    // HTML → text for job descriptions (Greenhouse/Workday return HTML; RSS detail pages).
    implementation("org.jsoup:jsoup:1.17.2")

    // Configuration
    implementation("io.github.cdimascio:dotenv-java:3.0.2")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.22")
}

application {
    mainClass.set("com.jd.watchlist.cli.Main")
}

tasks.named<JavaExec>("run") {
    systemProperty("dotenv.file", System.getProperty("dotenv.file", ".env"))
}

tasks.test {
    useJUnitPlatform()
    // Files some tests read from disk rather than the classpath: a change to one must rerun the
    // tests instead of leaving the task up to date.
    inputs.files("docker-entrypoint.sh", "README.md", "../../docker-compose.yml").withPropertyName("testedFiles")
    inputs.dir("contract").withPropertyName("contract")
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

jacoco {
    toolVersion = "0.8.11"
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
