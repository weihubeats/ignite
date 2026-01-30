pluginManagement {
    repositories {

        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }

        // 3. 官方插件源
        gradlePluginPortal()
    }
}

rootProject.name = "ignite"

include("ignite-plugin")