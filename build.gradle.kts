plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.valerin"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.h2database:h2:2.2.224")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    processResources {
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        relocate("org.sqlite", "com.valerin.venderchest.libs.sqlite")
        relocate("com.mysql", "com.valerin.venderchest.libs.mysql")
        relocate("com.zaxxer.hikari", "com.valerin.venderchest.libs.hikari")
        // H2 is NOT relocated: it uses internal resource paths (org/h2/res/) that
        // Shadow can't patch in string constants, causing ClassLoader failures at runtime.

        // SQLite nativo: mantener solo Linux x86_64 y Windows x86_64
        exclude("org/sqlite/native/Linux-Android/**")
        exclude("org/sqlite/native/Linux-Musl/**")
        exclude("org/sqlite/native/Linux/aarch64/**")
        exclude("org/sqlite/native/Linux/arm/**")
        exclude("org/sqlite/native/Linux/armv6/**")
        exclude("org/sqlite/native/Linux/armv7/**")
        exclude("org/sqlite/native/Linux/ppc64/**")
        exclude("org/sqlite/native/Linux/x86/**")
        exclude("org/sqlite/native/Mac/**")
        exclude("org/sqlite/native/FreeBSD/**")
        exclude("org/sqlite/native/Windows/aarch64/**")
        exclude("org/sqlite/native/Windows/x86/**")
    }
    build {
        dependsOn(shadowJar)
    }
}
