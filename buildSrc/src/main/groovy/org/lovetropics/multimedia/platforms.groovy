package org.lovetropics.multimedia

import org.gradle.api.file.Directory

enum NativeBuildPlatform {
    WINDOWS("windows", Os.WINDOWS, Arch.X64),
    WINDOWS_ARM64("windows-arm64", Os.WINDOWS, Arch.ARM64),
    MACOS("macos", Os.MACOS, Arch.X64),
    MACOS_ARM64("macos-arm64", Os.MACOS, Arch.ARM64),
    LINUX("linux", Os.LINUX, Arch.X64),
    LINUX_ARM64("linux-arm64", Os.LINUX, Arch.ARM64),

    final String classifier
    final Os os
    final Arch arch

    NativeBuildPlatform(final String classifier, final Os os, final Arch arch) {
        this.classifier = classifier
        this.os = os
        this.arch = arch
    }

    static NativeBuildPlatform detect(final String buildArch) {
        final def os = Os.detect()
        final def arch = Arch.detect(buildArch)
        final def platform = values().find { it.os == os && it.arch == arch }
        if (platform == null) {
            throw new IllegalStateException("Unsupported platform: $os, $arch")
        }
        return platform
    }

    File getArtifactFile(final Directory root, final String libraryName, final String libraryVersion) {
        return root.file("bin/$libraryName-$libraryVersion-native-${classifier}.jar").asFile
    }
}

enum Os {
    WINDOWS,
    MACOS,
    LINUX,

    static Os detect() {
        final def osName = System.getProperty("os.name").toLowerCase(Locale.ROOT)
        if (osName.contains("win")) {
            return WINDOWS
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            return MACOS
        } else if (osName.contains("linux") || osName.contains("unix")) {
            return LINUX
        }
        throw new IllegalStateException("Unrecognized operating system: $osName")
    }
}

enum Arch {
    X86,
    X64,
    ARM64,

    static Arch detect(String archName) {
        archName = archName.toLowerCase(Locale.ROOT)
        if (archName.contains("arm64") || archName.contains("aarch64")) {
            return ARM64
        } else if (archName.contains("64")) {
            return X64
        } else if (archName.contains("86")) {
            return X86
        }
        throw new IllegalStateException("Unrecognized architecture: $archName")
    }
}
