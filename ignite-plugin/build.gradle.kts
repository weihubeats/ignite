plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.weihubeats"
version = "1.0.9"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

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
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")
    implementation("net.datafaker:datafaker:2.0.2")
    implementation("commons-net:commons-net:3.9.0")

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
            Version 1.0.9:
            - Eliminate 180s busy-wait polling; attach uses phased detection (process-exit + port check)
            - Remove redundant Telnet probe in connection health check
            - Pre-extract Arthas JAR at IDE startup for faster first-run
            - Fix enum index out-of-bounds in mock data generator
            - Fix telnet socket leak on command retry
            - Add persistence for last-run record across IDE restart
            - Improve method resolution with parameter type matching

            Version 1.0.8:
            - Fix Spring @Service invocation via ApplicationContext (Spring Boot compatible)
            - Support JDK dynamic proxy beans (getBean by interface fallback)
            - Async Arthas commands wait for acceptance before disconnect
            
            Version 1.0.6:
            - Fixed instance method invocation for Spring Beans and MQ Consumers
            - Private/protected methods now use getDeclaredMethod + setAccessible
            - Removed unsafe new-instance fallback; prefer Spring getBean and heap instances
            
            Version 1.0.2:
            - Fixed Connection refused issue with retry mechanism
            - Auto-attach on project startup for already running processes
            - Improved connection health check and automatic reconnection
            - Faster method execution with pre-established connections
            
            Version 1.0.0:
            - Support older IDEA versions (from 2023.2+).
        """.trimIndent()
    }
}


