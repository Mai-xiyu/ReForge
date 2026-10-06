package org.xiyu.reforged.core;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Earliest ReForged hook.
 *
 * <p>Forge validates every jar in {@code mods/} before normal mods are constructed.
 * NeoForge-only jars therefore need a tiny Forge-readable descriptor before the
 * rest of ReForged can dynamically load them. This transformation service runs
 * before Forge mod discovery and patches those jars with a lowcode metadata stub.</p>
 */
public final class ReForgedTransformationService implements ITransformationService {

    private static final Logger LOGGER = createLogger();
    public static final String NAME = "reforged";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        logInfo("[ReForged] TransformationService initializing");
        patchModsFolderForForgeDiscovery();

        try {
            var registry = org.xiyu.reforged.asm.MappingRegistry.getInstance();
            logInfo("[ReForged] Loaded " + registry.getDirectCount()
                    + " direct + " + registry.getShimCount() + " shim mappings");
        } catch (Throwable t) {
            throw new IllegalStateException("Required ReForged mappings could not initialize", t);
        }
    }

    @Override
    public void onLoad(IEnvironment environment, Set<String> otherServices) throws IncompatibleEnvironmentException {
        // No launch-plugin interaction is required; bytecode rewriting happens in NeoModClassLoader.
    }

    @Override
    public List<ITransformer> transformers() {
        return List.of();
    }

    public static boolean isNeoForgeModJar(Path jarPath) {
        if (!jarPath.toString().endsWith(".jar")) return false;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.getEntry("META-INF/neoforge.mods.toml") != null) {
                return true;
            }
            if (jar.getEntry("META-INF/mods.toml") != null) {
                var entry = jar.getEntry("META-INF/mods.toml");
                try (var is = jar.getInputStream(entry)) {
                    String content = new String(is.readAllBytes());
                    return content.contains("neoforge") || content.contains("NeoForge");
                }
            }
        } catch (Exception e) {
            logDebug("[ReForged] Could not inspect JAR: " + jarPath + " - " + e.getMessage());
        }
        return false;
    }

    public static List<Path> discoverNeoForgeJars(Path gameDir) {
        Path neoModsDir = gameDir.resolve("neoforge-mods");
        if (!java.nio.file.Files.isDirectory(neoModsDir)) {
            logInfo("[ReForged] No neoforge-mods/ directory found at " + neoModsDir);
            return List.of();
        }
        try (Stream<Path> files = java.nio.file.Files.list(neoModsDir)) {
            List<Path> jars = files.filter(ReForgedTransformationService::isNeoForgeModJar).toList();
            logInfo("[ReForged] Discovered " + jars.size() + " NeoForge mod JAR(s) in " + neoModsDir);
            return jars;
        } catch (Exception e) {
            logWarn("[ReForged] Failed to scan neoforge-mods/", e);
            return List.of();
        }
    }

    private static void patchModsFolderForForgeDiscovery() {
        try {
            Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path modsDir = gameDir.resolve("mods");
            int patched = NeoForgeModPatcher.prepareForDiscovery(modsDir).size();
            excludeRawNeoForgeInputs(modsDir);
            if (patched > 0) {
                logInfo("[ReForged] Patched " + patched + " NeoForge jar(s) in " + modsDir);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Required early NeoForge preprocessing failed", t);
        }
    }

    /**
     * Forge 51.0.33's folder locator reads this exclusion list on every scan.
     * It must not also parse raw NeoForge-only descriptors after our locator
     * supplies their prepared counterparts. The automatic fmlloader module
     * permits this version-bound reflective adapter; a changed layout fails
     * startup explicitly instead of accepting duplicate/invalid mod files.
     */
    @SuppressWarnings("unchecked")
    private static void excludeRawNeoForgeInputs(Path modsDir) throws Exception {
        if (!java.nio.file.Files.isDirectory(modsDir)) return;
        var field = net.minecraftforge.fml.loading.ModDirTransformerDiscoverer.class.getDeclaredField("found");
        field.setAccessible(true);
        var excluded = (List<cpw.mods.modlauncher.api.NamedPath>) field.get(null);
        try (var files = java.nio.file.Files.list(modsDir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    if (jar.getJarEntry("META-INF/neoforge.mods.toml") == null
                            || jar.getJarEntry("META-INF/mods.toml") != null) continue;
                }
                Path normalized = path.toAbsolutePath().normalize();
                if (excluded.stream().noneMatch(p -> java.util.Arrays.asList(p.paths()).contains(normalized))) {
                    excluded.add(new cpw.mods.modlauncher.api.NamedPath("reforged-prepared-input", normalized));
                }
            }
        }
    }

    private static Logger createLogger() {
        try {
            return LogUtils.getLogger();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logInfo(String message) {
        if (LOGGER != null) LOGGER.info(message);
        else System.out.println(message);
    }

    private static void logDebug(String message) {
        if (LOGGER != null) LOGGER.debug(message);
    }

    private static void logWarn(String message, Throwable t) {
        if (LOGGER != null) LOGGER.warn(message, t);
        else {
            System.err.println(message);
            if (t != null) t.printStackTrace();
        }
    }
}
