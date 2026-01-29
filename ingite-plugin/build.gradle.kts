plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.weihubeats"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

    }

    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("net.datafaker:datafaker:2.0.2")
    implementation("commons-net:commons-net:3.9.0")
    implementation("org.apache.commons:commons-lang3:3.12.0")

}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

