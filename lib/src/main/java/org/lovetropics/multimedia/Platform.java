package org.lovetropics.multimedia;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/* package-private */ enum Platform {
    WINDOWS("windows", Os.WINDOWS, Arch.X64),
    WINDOWS_ARM64("windows-arm64", Os.WINDOWS, Arch.ARM64),
    MACOS("macos", Os.MACOS, Arch.X64),
    MACOS_ARM64("macos-arm64", Os.MACOS, Arch.ARM64),
    LINUX("linux", Os.LINUX, Arch.X64),
    LINUX_ARM64("linux-arm64", Os.LINUX, Arch.ARM64),
    ;

    private static final String OS_NAME = System.getProperty("os.name");
    private static final String OS_ARCH = System.getProperty("os.arch");

    public final String classifier;
    private final Os os;
    private final Arch arch;

    Platform(final String classifier, final Os os, final Arch arch) {
        this.classifier = classifier;
        this.os = os;
        this.arch = arch;
    }

    public static Platform detect() {
        final Platform platform = tryDetect();
        if (platform == null) {
            throw new LinkageError("Unrecognized platform: " + OS_NAME + ", " + OS_ARCH);
        }
        return platform;
    }

    public static @Nullable Platform tryDetect() {
        final Os os = Os.detect();
        final Arch arch = Arch.detect();
        for (final Platform platform : values()) {
            if (platform.os == os && platform.arch == arch) {
                return platform;
            }
        }
        return null;
    }

    public String getLibraryPath(final String name) {
        return os.libraryPrefix + name + "-" + classifier + os.libraryExtension;
    }

    public enum Os {
        WINDOWS("", ".dll"),
        MACOS("lib", ".dylib"),
        LINUX("lib", ".so"),
        ;

        private final String libraryPrefix;
        private final String libraryExtension;

        Os(final String libraryPrefix, final String libraryExtension) {
            this.libraryPrefix = libraryPrefix;
            this.libraryExtension = libraryExtension;
        }

        public static @Nullable Os detect() {
            final String osName = OS_NAME.toLowerCase(Locale.ROOT);
            if (osName.contains("win")) {
                return WINDOWS;
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                return MACOS;
            } else if (osName.contains("linux") || osName.contains("unix")) {
                return LINUX;
            }
            return null;
        }
    }

    public enum Arch {
        X86,
        X64,
        ARM64,
        ;

        public static @Nullable Arch detect() {
            final String archName = OS_ARCH.toLowerCase(Locale.ROOT);
            if (archName.contains("arm64") || archName.equals("aarch64")) {
                return ARM64;
            } else if (archName.contains("64")) {
                return X64;
            } else if (archName.contains("86")) {
                return X86;
            }
            return null;
        }
    }
}
