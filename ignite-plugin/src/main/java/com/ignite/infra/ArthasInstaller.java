package com.ignite.infra;

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 负责 Arthas 发行包的本地管理：解压、版本判断、路径定位。
 */
final class ArthasInstaller {

    private static final Logger LOG = Logger.getInstance(ArthasInstaller.class);

    private static final String ARTHAS_BOOT_JAR = "arthas-boot.jar";
    private static final String ARTHAS_CORE_JAR = "arthas-core.jar";
    private static final String ARTHAS_AGENT_JAR = "arthas-agent.jar";
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    private static final String ARTHAS_VERSION = "4.1.8";

    // 解压互斥：IDE 启动预解压与首次运行可能并发执行
    private static final Object EXTRACT_LOCK = new Object();

    private ArthasInstaller() {}

    static String arthasVersion() {
        return ARTHAS_VERSION;
    }

    /**
     * 获取 Arthas 主目录（固定路径，避免重复下载）。
     * 使用 user.home 而非 java.io.tmpdir，确保插件进程与目标 JVM 进程（Run/Debug）
     * 看到同一路径，目标进程才能通过 --libPath 访问已解压的 native 库。
     */
    static File getArthasHomeDir() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isEmpty()) {
            userHome = System.getProperty("java.io.tmpdir");
        }
        File arthasDir = new File(userHome, ".ignite-arthas-" + ARTHAS_VERSION);

        // 确保目录存在且有权限
        if (!arthasDir.exists()) {
            arthasDir.mkdirs();
            arthasDir.setReadable(true, false);
            arthasDir.setWritable(true, true);
            arthasDir.setExecutable(true, false);
        }

        return arthasDir;
    }

    /**
     * 获取/预解压 Arthas JAR 到本地目录。
     * 在 IDE 启动阶段异步调用，避免首次用户运行时等待解压。
     */
    static File getArthasJar() throws IOException {
        File pluginDir = getArthasHomeDir();
        File arthasJar = new File(pluginDir, ARTHAS_BOOT_JAR);

        // 检查是否需要更新（通过检查 version 文件）
        File versionFile = new File(pluginDir, ".version");
        boolean needExtract = !arthasJar.exists() ||
                              !versionFile.exists() ||
                              !ARTHAS_VERSION.equals(readVersionFile(versionFile));

        if (!needExtract) {
            return arthasJar;
        }

        synchronized (EXTRACT_LOCK) {
            // 双重检查：可能在等锁期间已被其他线程解压完成
            if (arthasJar.exists() && ARTHAS_VERSION.equals(readVersionFile(versionFile))) {
                return arthasJar;
            }

            pluginDir.mkdirs();
            extractFromResources(ARTHAS_BOOT_JAR, arthasJar);
            extractFromResources(ARTHAS_CORE_JAR, new File(pluginDir, ARTHAS_CORE_JAR));
            extractFromResources(ARTHAS_AGENT_JAR, new File(pluginDir, ARTHAS_AGENT_JAR));
            extractFromResources(ARTHAS_SPY_JAR, new File(pluginDir, ARTHAS_SPY_JAR));

            // 解压 lib 目录下的 native library（vmtool 需要）
            extractNativeLib(pluginDir);

            writeVersionFile(versionFile, ARTHAS_VERSION);

            return arthasJar;
        }
    }

    static File getCoreJar() {
        return new File(getArthasHomeDir(), ARTHAS_CORE_JAR);
    }

    static File getAgentJar() {
        return new File(getArthasHomeDir(), ARTHAS_AGENT_JAR);
    }

    static File getSpyJar() {
        return new File(getArthasHomeDir(), ARTHAS_SPY_JAR);
    }

    static String getLibPath() {
        File libDir = new File(getArthasHomeDir(), "lib");
        String libFileName = getNativeLibFileName();
        return new File(libDir, libFileName).getAbsolutePath();
    }

    /**
     * 按当前 OS/arch 返回已解压的 native 库文件名（与 extractNativeLib 中一致）。
     */
    private static String getNativeLibFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac")) {
            return "libArthasJniLibrary.dylib";
        }
        if (os.contains("win")) {
            return (arch.contains("aarch64") || arch.contains("64")) ? "libArthasJniLibrary-x64.dll" : "libArthasJniLibrary-x86.dll";
        }
        if (arch.contains("aarch64") || arch.contains("arm")) {
            return "libArthasJniLibrary-aarch64.so";
        }
        return "libArthasJniLibrary-x64.so";
    }

    /**
     * 解压 native library（vmtool 需要）
     */
    private static void extractNativeLib(File pluginDir) {
        File libDir = new File(pluginDir, "lib");
        libDir.mkdirs();

        // 建议把所有支持的架构库全量释出，让 Arthas 自行匹配加载
        // 包含无后缀的 dylib（resources 中实际文件名），确保 macOS 能解压
        String[] libFiles = {
            "libArthasJniLibrary.dylib",
            "libArthasJniLibrary-x86_64.dylib",
            "libArthasJniLibrary-aarch64.dylib",
            "libArthasJniLibrary-x64.dll",
            "libArthasJniLibrary-x86.dll",
            "libArthasJniLibrary-x64.so",
            "libArthasJniLibrary-aarch64.so",
            "libArthasJniLibrary-arm.so"
        };

        for (String libFileName : libFiles) {
            File targetLib = new File(libDir, libFileName);
            try {
                // 请确保你的 IDEA 插件 resources/bin/lib/ 目录下确实包含了这些文件
                extractFromResources("lib/" + libFileName, targetLib);
            } catch (FileNotFoundException e) {
                LOG.info("Native lib not found in resources, skip: " + libFileName);
            } catch (IOException e) {
                LOG.warn("无法解压 native lib " + libFileName + ": " + e.getMessage());
            }
        }
        LOG.info("Native libs 解压完成: " + libDir.getAbsolutePath());
    }

    private static void extractFromResources(String jarName, File targetFile) throws IOException {
        Path targetPath = targetFile.toPath();
        Path tmpPath = targetPath.resolveSibling(targetFile.getName() + ".tmp"
            + Long.toHexString(System.nanoTime()));

        // 先写临时文件再原子替换，避免并发解压时读到写了一半的文件
        try (InputStream inputStream = ArthasInstaller.class.getResourceAsStream("/bin/" + jarName)) {
            if (inputStream == null)
                throw new FileNotFoundException("未找到 " + jarName);
            Files.copy(inputStream, tmpPath, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 部分文件系统不支持 ATOMIC_MOVE，退回普通替换
            Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 设置可执行权限（Unix/Linux/Mac）
        targetFile.setExecutable(true, false);
        targetFile.setReadable(true, false);
        targetFile.setWritable(true, true);
    }

    private static String readVersionFile(File versionFile) {
        try {
            return Files.readString(versionFile.toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeVersionFile(File versionFile, String version) {
        try {
            Files.writeString(versionFile.toPath(), version, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("无法写入版本文件: " + e.getMessage());
        }
    }
}
