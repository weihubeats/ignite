plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.weihubeats"
version = "1.1.0"

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
        create("IC", "2025.2.4")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.3")
    implementation("net.datafaker:datafaker:2.4.2")
    implementation("commons-net:commons-net:3.11.1")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {

        version = project.version.toString()
        id = "io.github.weihubeats.ignite-plugin"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        description = file("src/main/resources/META-INF/description.html").readText()

        changeNotes = """
            Version 1.1.0:
            - Fix ServiceImpl method invocation under JDK dynamic proxies (interface-first bean probing)
            - Display method return values and exceptions in Ignite Console
            - Support plain (non-Spring) classes via heap instance lookup
            - Per-PID Arthas ports: debug multiple services simultaneously without conflicts
            - Telnet connection reuse, socket read timeout and cancellable progress
            - Typed Map/enum/multi-dim array argument conversion; scalar roots preserved
            - Fix enum mock off-by-one; fix ToolWindow console leak; auto-attach only application runs
            - Save parameter history only after successful dispatch
            - Add GitHub Actions CI: PR tests, weekly plugin verification, tag-driven releases

            Version 1.0.9:
            - Eliminate 180s busy-wait polling; phased detection with interrupt support
            - Pre-extract Arthas JAR at IDE startup for faster first-run
            - Fix enum index out-of-bounds, 0-arg constructor skipping
            - Fix telnet socket leak on command retry
            - Register IgniteAutoAttacher and NotificationGroup in plugin.xml
            - Use DateTimeFormatter instead of SimpleDateFormat
            - Persist last-run record across IDE restarts with parameter type matching

            Version 1.0.8:
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

    pluginVerification {
        ides {
            // 与 sinceBuild=243 对应的最低支持线 + 当前开发版本；
            // CI 的 plugin-verification workflow 每周跑 verifyPlugin 及时发现新版 IDE 的 API 变更
            create("IC", "2024.3")
            create("IC", "2025.2.4")
        }
    }
}


