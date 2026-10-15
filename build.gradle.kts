plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.cyclonedx.bom") version "3.5.1"
}

group = "ru.course"
version = "1.0.0"

// Patch vulnerable runtime libraries within the current Boot 3.5 / Java 17 line.
extra["jackson-bom.version"] = "2.21.7"
extra["tomcat.version"] = "10.1.61"
extra["postgresql.version"] = "42.7.14"
extra["log4j2.version"] = "2.25.5"

repositories { mavenCentral() }
kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking { lockAllConfigurations() }
tasks.test { useJUnitPlatform() }
tasks.bootJar { archiveFileName.set("helpdesk.jar") }
tasks.jar { enabled = false }

// Runtime-only SBOM: test tooling is not part of the delivered application.
tasks.cyclonedxDirectBom {
    includeConfigs = listOf("runtimeClasspath")
    includeLicenseText = false
}
