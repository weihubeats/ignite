
group = "io.github.weihubeats"
version = "1.0.0"

subprojects {
    // 在这里应用 Java 插件即可，它是内置的，直接用名字
    apply(plugin = "java")

    repositories {
        // 建议保留阿里云镜像以加快下载
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}