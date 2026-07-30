plugins {
    java
    id("com.gradleup.shadow") version "9.3.0"
}

group = "com.valerin"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.h2database:h2:2.2.224")

    // compileOnly does not propagate to the test classpath, so paper-api needs to be re-declared
    // here purely so the tests can compile against types like ItemStack/Storage.PageRecord. Tests
    // never actually instantiate a real ItemStack (Paper's Material/item registry requires a live
    // server even for `new ItemStack(Material, amount)`) - they use null/ItemStack[] arrays.
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    test {
        useJUnitPlatform()
    }
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
        dependsOn("apiJar")
    }

    // Lightweight, dependency-free jar containing only the public com.valerin.venderchest.api
    // package (interfaces, DTOs, events) - no internal classes, no shaded libraries. Intended for
    // other plugins (e.g. vAntiDupe) to depend on as `compileOnly` without pulling in HikariCP,
    // the JDBC drivers, or H2. See docs/VANTIDUPE_API.md.
    register<Jar>("apiJar") {
        archiveClassifier.set("api")
        from(sourceSets.main.get().output) {
            include("com/valerin/venderchest/api/**")
        }
    }
}
