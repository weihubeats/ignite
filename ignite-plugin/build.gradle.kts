plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.weihubeats"
version = "1.0.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2023.3.6")
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

        version = project.version.toString()
        id = "io.github.weihubeats.ignite-plugin"
        ideaVersion {
            sinceBuild = "232"
            untilBuild = provider { null }
        }

        description = file("src/main/resources/META-INF/description.html").readText()

        changeNotes = """
            Support older IDEA versions (from 2023.2+).
        """.trimIndent()
    }
}


