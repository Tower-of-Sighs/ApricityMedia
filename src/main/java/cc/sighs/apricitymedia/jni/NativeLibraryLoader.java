package cc.sighs.apricitymedia.jni;

import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static cc.sighs.apricitymedia.ApricityMedia.MC_VERSION;

public final class NativeLibraryLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static final String DEV_JNI_PATH = "F:/code/mcmod/project/apricitymeida/jni/";
    private static final String NATIVE_RESOURCE_DIR = "META-INF/apricitymedia/natives/";
    private static final String SYS_PROP = "apricitymedia.native.dir";
    private static final String ENV_VAR = "APRICITYMEDIA_NATIVE_DIR";
    private static final String[] WINDOWS_RUNTIME_CANDIDATES = {
            "apwinpthread_01.dll",
            "libwinpthread-1.dll",
            "libva.dll",
            "libva_win32.dll",
            "libdav1d-7.dll"
    };
    private static final String[] WINDOWS_PRELOAD_CANDIDATES = {
            "apwinpthread_01.dll",
            "libwinpthread-1.dll",
            "libva.dll",
            "libva_win32.dll",
            "libdav1d-7.dll"
    };

    private static volatile boolean loaded = false;
    private static volatile String loadError = "";

    private NativeLibraryLoader() {}

    public static boolean ensureLoaded() {
        if (loaded) return true;
        synchronized (NativeLibraryLoader.class) {
            if (loaded) return true;
            try {
                load();
                loaded = true;
                return true;
            } catch (Exception e) {
                loadError = e.getMessage();
                LOGGER.error("Failed to load native FFmpeg libraries", e);
                return false;
            }
        }
    }

    public static String getLoadError() {
        return loadError;
    }

    // ---------------------------------------------------------------
    //  Loading
    // ---------------------------------------------------------------

    private static void load() throws IOException {
        String platform = detectPlatform();

        // 1. System property
        Path dir = fromSysProp();
        if (dir != null && Files.isDirectory(dir)) {
            LOGGER.info("Using native dir from system property {}: {}", SYS_PROP, dir);
            loadFromDir(dir, platform);
            return;
        }

        // 2. Environment variable
        dir = fromEnvVar();
        if (dir != null && Files.isDirectory(dir)) {
            LOGGER.info("Using native dir from env var {}: {}", ENV_VAR, dir);
            loadFromDir(dir, platform);
            return;
        }

        // 3. Dev mode: jni/<zip> from hardcoded project path
        if (!FMLEnvironment.production && loadFromDevJni(platform)) {
            return;
        }

        // 4. Production: jar resource
        loadFromJar(platform);
    }

    // ---------------------------------------------------------------
    //  Source resolution
    // ---------------------------------------------------------------

    private static Path fromSysProp() {
        String value = System.getProperty(SYS_PROP);
        return value != null && !value.isBlank() ? Path.of(value) : null;
    }

    private static Path fromEnvVar() {
        String value = System.getenv(ENV_VAR);
        return value != null && !value.isBlank() ? Path.of(value) : null;
    }

    private static boolean loadFromDevJni(String platform) throws IOException {
        String zipName = String.format("ffmpeg-jni-mc-%s-%s.zip", MC_VERSION, platform);
        Path zipFile = Path.of(DEV_JNI_PATH, zipName);
        if (!Files.isRegularFile(zipFile)) return false;

        LOGGER.info("Dev mode: loading natives from {}", zipFile);
        Path extractDir = getExtractDir(platform);
        try (InputStream in = Files.newInputStream(zipFile)) {
            extractAndLoad(in, extractDir, platform);
        }
        return true;
    }

    // ---------------------------------------------------------------
    //  Jar extraction (production)
    // ---------------------------------------------------------------

    private static void loadFromJar(String platform) throws IOException {
        Path extractDir = getExtractDir(platform);
        String zipPath = NATIVE_RESOURCE_DIR + platform + ".zip";
        try (InputStream in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(zipPath)) {
            if (in == null) {
                throw new IOException("Native library zip not found in jar: " + zipPath);
            }
            extractAndLoad(in, extractDir, platform);
        }
    }

    // ---------------------------------------------------------------
    //  Shared extract + load
    // ---------------------------------------------------------------

    private static void extractAndLoad(InputStream zipStream, Path destDir, String platform) throws IOException {
        destDir = destDir.normalize();
        Path tempZip = Files.createTempFile("apricitymedia-natives-", ".zip");
        try {
            Files.copy(zipStream, tempZip, StandardCopyOption.REPLACE_EXISTING);

            String zipHash = sha256Hex(tempZip);
            Path hashFile = destDir.resolve("hash.txt");

            if (Files.isRegularFile(hashFile)) {
                String existingHash = Files.readString(hashFile).trim();
                if (zipHash.equals(existingHash) && allLibsExist(destDir, platform)) {
                    LOGGER.info("Native libraries already extracted to {}", destDir);
                    loadFromDir(destDir, platform);
                    return;
                }
                // Cache invalid — files missing, re-extract
                Files.deleteIfExists(hashFile);
            }

            // Clean and re-extract
            if (Files.isDirectory(destDir)) {
                try (var files = Files.list(destDir)) {
                    files.forEach(f -> {
                        try { Files.deleteIfExists(f); }
                        catch (Exception ignored) {}
                    });
                }
            }
            Files.createDirectories(destDir);

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    Path outFile = destDir.resolve(entry.getName());
                    if (!outFile.normalize().startsWith(destDir)) continue;
                    Files.createDirectories(outFile.getParent());
                    Files.copy(zis, outFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Files.writeString(hashFile, zipHash);
            LOGGER.info("Extracted native libraries to {}", destDir);
            loadFromDir(destDir, platform);
        } finally {
            try { Files.deleteIfExists(tempZip); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------
    //  Platform-specific loading
    // ---------------------------------------------------------------

    private static void loadFromDir(Path dir, String platform) throws IOException {
        // On Windows: copy missing MSYS2 runtime DLLs into the extract dir.
        // FFmpeg DLLs were built with MinGW and may link libgcc/libwinpthread/
        // libssp/libiconv dynamically. Windows resolves dependencies by looking
        // in the loading DLL's own directory first — so copying them there works
        // regardless of PATH. Once CI rebuilds with -static-libgcc this is a no-op.
        if (platform.startsWith("windows")) {
            copyMsys2Runtimes(dir);
            // Some JVM/launcher combinations don't resolve dependent DLLs from the
            // target module directory reliably. Preload runtimes by absolute path.
            preloadWindowsRuntimes(dir);
        }

        String[] libs;
        if (platform.startsWith("windows")) {
            libs = new String[]{
                "avutil-60.dll", "swresample-6.dll", "swscale-9.dll",
                "avcodec-62.dll", "avformat-62.dll", "apricitymedia-jni.dll"
            };
        } else if (platform.startsWith("linux") || platform.startsWith("android")) {
            libs = new String[]{
                "libavutil.so.60", "libswresample.so.6", "libswscale.so.9",
                "libavcodec.so.62", "libavformat.so.62", "libapricitymedia-jni.so"
            };
        } else if (platform.startsWith("macos")) {
            libs = new String[]{
                "libavutil.60.dylib", "libswresample.6.dylib", "libswscale.9.dylib",
                "libavcodec.62.dylib", "libavformat.62.dylib", "libapricitymedia-jni.dylib"
            };
        } else {
            throw new IOException("Unsupported platform: " + platform);
        }

        for (String lib : libs) {
            Path libPath = dir.resolve(lib);
            if (!Files.isRegularFile(libPath)) {
                throw new IOException("Native library not found: " + libPath);
            }
            try {
                System.load(libPath.toAbsolutePath().toString());
                LOGGER.info("Loaded: {}", lib);
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("Failed to load " + libPath + ": " + e.getMessage(), e);
            }
        }
        LOGGER.info("Loaded native FFmpeg libraries from {}", dir);
    }

    /**
     * Copy MSYS2 MinGW runtime DLLs into the target directory so Windows can
     * resolve them alongside the FFmpeg DLLs (same-directory lookup is first
     * in the DLL search order).
     */
    private static void copyMsys2Runtimes(Path destDir) throws IOException {
        Path msys2Bin = findMsys2Bin();
        if (msys2Bin == null) {
            LOGGER.info("MSYS2 not found — assuming FFmpeg was built with static runtime");
            return;
        }

        // DLLs that MinGW-built FFmpeg may link dynamically.
        // Copy everything we find — harmless extras just sit there unused.
        for (String lib : WINDOWS_RUNTIME_CANDIDATES) {
            Path src = msys2Bin.resolve(lib);
            Path dst = destDir.resolve(lib);
            if (Files.isRegularFile(src) && !Files.isRegularFile(dst)) {
                Files.copy(src, dst);
                LOGGER.info("Copied MSYS2 runtime: {} → {}", lib, destDir.getFileName());
            }
        }
    }

    private static void preloadWindowsRuntimes(Path dir) throws IOException {
        for (String lib : WINDOWS_PRELOAD_CANDIDATES) {
            Path libPath = dir.resolve(lib);
            if (!Files.isRegularFile(libPath)) continue;
            try {
                System.load(libPath.toAbsolutePath().toString());
                LOGGER.info("Preloaded runtime DLL: {}", lib);
            } catch (UnsatisfiedLinkError e) {
                // Optional runtime preload failed. Keep going and let the real
                // FFmpeg/JNI load step report a hard error if dependency resolution
                // is still unsatisfied.
                LOGGER.warn("Runtime preload skipped for {}: {}", lib, e.getMessage());
            }
        }
    }

    private static Path findMsys2Bin() {
        String root = System.getenv("MSYS2_ROOT");
        if (root == null || root.isBlank()) {
            String msystem = System.getenv("MSYSTEM");
            if (msystem != null && msystem.contains("MINGW64")) root = "C:\\msys64";
            else if (msystem != null && msystem.contains("MINGW32")) root = "C:\\msys32";
        }
        if (root != null && !root.isBlank()) {
            Path bin = Path.of(root, "mingw64", "bin");
            if (Files.isDirectory(bin)) return bin;
            bin = Path.of(root, "mingw32", "bin");
            if (Files.isDirectory(bin)) return bin;
        }
        // Try default paths
        for (String candidate : new String[]{
            "C:\\msys64\\mingw64\\bin", "C:\\msys64\\mingw32\\bin",
            "C:\\msys32\\mingw64\\bin", "C:\\msys32\\mingw32\\bin"
        }) {
            Path bin = Path.of(candidate);
            if (Files.isDirectory(bin)) return bin;
        }
        return null;
    }

    // ---------------------------------------------------------------
    //  Paths
    // ---------------------------------------------------------------

    private static Path getExtractDir(String platform) {
        return FMLLoader.getGamePath()
                .resolve(".apricityui-video")
                .resolve("runtime")
                .resolve("natives")
                .resolve(platform)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean allLibsExist(Path dir, String platform) {
        String[] libs;
        if (platform.startsWith("windows")) {
            libs = new String[]{"apwinpthread_01.dll", "libdav1d-7.dll", "avutil-60.dll", "avcodec-62.dll", "avformat-62.dll",
                    "swresample-6.dll", "swscale-9.dll", "apricitymedia-jni.dll"};
        } else if (platform.startsWith("macos")) {
            libs = new String[]{"libavutil.60.dylib", "libavcodec.62.dylib", "libavformat.62.dylib",
                    "libswresample.6.dylib", "libswscale.9.dylib", "libapricitymedia-jni.dylib"};
        } else {
            libs = new String[]{"libavutil.so.60", "libavcodec.so.62", "libavformat.so.62",
                    "libswresample.so.6", "libswscale.so.9", "libapricitymedia-jni.so"};
        }
        for (String lib : libs) {
            if (!Files.isRegularFile(dir.resolve(lib))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------
    //  Platform detection
    // ---------------------------------------------------------------

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean isArm = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win")) return "windows-x64";
        if (os.contains("mac")) return isArm ? "macos-arm64" : "macos-x64";
        if (os.contains("linux")) {
            String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
            if (vmName.contains("dalvik") || vmName.contains("art")) return "android-arm64";
            return isArm ? "linux-arm64" : "linux-x64";
        }
        throw new IllegalStateException("Unsupported platform: " + os + " " + arch);
    }

    // ---------------------------------------------------------------
    //  Utilities
    // ---------------------------------------------------------------

    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IOException("SHA-256 not available", e); }

        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
