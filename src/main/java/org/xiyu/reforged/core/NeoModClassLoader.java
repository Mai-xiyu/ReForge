package org.xiyu.reforged.core;

import com.mojang.logging.LogUtils;
import org.xiyu.reforged.asm.BytecodeRewriter;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Creates a child-first (parent-last) {@link URLClassLoader} for NeoForge mod JARs.
 *
 * <p>Mod jars are extracted to a cache directory and the loader works on the
 * extracted <b>directories</b> rather than the jars. This makes every resource
 * URL a plain hierarchical {@code file:} URL, which keeps common NeoForge mod
 * patterns working — e.g. {@code Paths.get(getResource(dir).toURI())} followed
 * by {@code Files.walk}/{@code URI.relativize}, which break on opaque
 * {@code jar:} URLs.</p>
 *
 * <p>Also extracts nested Jar-in-Jar (JiJ) dependencies from
 * {@code META-INF/jarjar/} inside each mod JAR and adds them
 * to the classloader so library classes are available.</p>
 */
public final class NeoModClassLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NeoModClassLoader() {}

    /**
     * Create a URLClassLoader for the NeoForge mod JARs.
     * Parent is the given classloader (game + Forge + ReForged).
     *
     * @param jars             list of NeoForge mod JAR paths
     * @param parentLoader     the parent classloader (typically the game classloader)
     * @param extractedJiJJars if non-null, extracted Jar-in-Jar paths will be added to this list
     *                         so callers can scan them for @Mod classes
     * @return the classloader, or null on failure
     */
    public static URLClassLoader createClassLoader(List<Path> jars, ClassLoader parentLoader,
                                                    List<Path> extractedJiJJars) {
        try {
            List<URL> urls = new ArrayList<>();
            // Add top-level mod JARs as extracted directories (fall back to the
            // jar itself if extraction fails).
            for (Path jar : jars) {
                Path extracted = extractJarToCache(jar);
                if (extracted != null) {
                    urls.add(extracted.toUri().toURL());
                } else {
                    urls.add(jar.toUri().toURL());
                }
            }
            // Extract Jar-in-Jar (JiJ) dependencies, recursively: libraries can
            // nest their own JiJ entries (e.g. ywzj_vehicle → simplebedrockmodel → mae).
            Path jijTemp = Files.createTempDirectory("reforged-jij-");
            jijTemp.toFile().deleteOnExit();
            java.util.Map<String, String> nestedIdentities = new java.util.HashMap<>();
            for (Path jar : jars) {
                extractJiJRecursively(jar, jijTemp, urls, extractedJiJJars, nestedIdentities, 0);
            }

            // Classes kept inside the discovery placeholder jars (mixin classes,
            // duck interfaces, their helpers) exist on the TRANSFORMER loader.
            // They must resolve parent-first here so both sides share one identity.
            Set<String> placeholderParentClasses = loadPlaceholderParentClasses(parentLoader);

            // Use a CHILD-FIRST (parent-last) classloader so that mod classes
            // are loaded from our URLs rather than from AppClassLoader (which sees
            // them on the system classpath via runtimeOnly dependencies).
            // This ensures mod classes resolve IEventBus/etc. through our parent
            // (TransformingClassLoader) rather than AppClassLoader, avoiding the
            // classloader identity mismatch that breaks constructor injection.
            return new URLClassLoader(urls.toArray(new URL[0]), parentLoader) {

                private final BytecodeRewriter bytecodeRewriter = new BytecodeRewriter();

                // Packages that must always be loaded from the parent classloader
                private static final String[] PARENT_FIRST_PREFIXES = {
                    "java.", "jdk.", "sun.", "javax.",               // JDK
                    "net.minecraft.", "com.mojang.",                 // Minecraft
                    "net.minecraftforge.", "net.neoforged.",         // Forge & NeoForge shims
                    "org.xiyu.reforged.",                            // ReForged
                    "net.blay09.mods.balm.api.entity.",             // BalmEntity API only (shared type identity)
                    "cpw.mods.",                                     // ModLauncher
                    "org.objectweb.asm.",                            // ASM
                    "org.slf4j.", "org.apache.logging.",             // Logging
                    "com.google.", "org.apache.commons.",            // Common libs
                    "io.netty.", "it.unimi.dsi.",                    // Netty & fastutil
                    "org.spongepowered.",                            // Mixin
                    "dev.engine_room.flywheel.impl.extension.",      // Flywheel PoseStackExtension
                    "dev.engine_room.flywheel.lib.transform.",       // Flywheel transform interfaces
                    "net.createmod.ponder.mixin.",                   // Ponder mixin accessor interfaces
                };

                // Specific classes that must be loaded from parent to maintain type identity
                // with classes injected via ReForged Mixins (e.g., JadeFont interface on Font)
                private static final java.util.Set<String> PARENT_FIRST_CLASSES = java.util.Set.of(
                    "snownee.jade.gui.JadeFont",
                    "snownee.jade.mixin.EntityAccess"
                );

                private boolean isParentFirst(String name) {
                    if (PARENT_FIRST_CLASSES.contains(name)) return true;
                    if (placeholderParentClasses.contains(name)) return true;
                    for (String prefix : PARENT_FIRST_PREFIXES) {
                        if (name.startsWith(prefix)) return true;
                    }
                    return false;
                }

                @Override
                public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        // 1. Check if already loaded
                        Class<?> c = findLoadedClass(name);
                        if (c != null) {
                            if (resolve) resolveClass(c);
                            return c;
                        }

                        // 2. Parent-first for framework/JDK packages
                        if (isParentFirst(name)) {
                            try {
                                return super.loadClass(name, resolve);
                            } catch (ClassNotFoundException e) {
                                // Placeholder-listed class missing on parent (stale
                                // placeholder) — fall through to child lookup.
                                if (!placeholderParentClasses.contains(name)) throw e;
                            }
                        }

                        // 3. Child-first: try our URLs first (mod classes)
                        try {
                            c = findClass(name);
                            if (resolve) resolveClass(c);
                            return c;
                        } catch (ClassNotFoundException ignored) {}

                        // 4. Fall back to parent
                        return super.loadClass(name, resolve);
                    }
                }

                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    Thread thread = Thread.currentThread();
                    ClassLoader previousContextLoader = thread.getContextClassLoader();
                    thread.setContextClassLoader(this);

                    try {
                        String resourceName = name.replace('.', '/').concat(".class");
                        URL resource = findResource(resourceName);
                        if (resource == null) throw new ClassNotFoundException(name);
                        try (InputStream is = resource.openStream()) {
                            byte[] rewritten = bytecodeRewriter.rewrite(is.readAllBytes());
                            return defineClass(name, rewritten, 0, rewritten.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    } finally {
                        thread.setContextClassLoader(previousContextLoader);
                    }
                }

                // ── Child-first resources ──────────────────────────────
                // Mod resources resolve from the extracted directories first so
                // getResource() returns hierarchical file: URLs (matching NeoForge's
                // union fs semantics) instead of the parent's opaque jar: URLs.

                @Override
                public URL getResource(String name) {
                    URL url = findResource(name);
                    return url != null ? url : super.getResource(name);
                }

                @Override
                public Enumeration<URL> getResources(String name) throws java.io.IOException {
                    List<URL> combined = new ArrayList<>();
                    Enumeration<URL> own = findResources(name);
                    while (own.hasMoreElements()) combined.add(own.nextElement());
                    ClassLoader parent = getParent();
                    if (parent != null) {
                        Enumeration<URL> inherited = parent.getResources(name);
                        while (inherited.hasMoreElements()) combined.add(inherited.nextElement());
                    }
                    return java.util.Collections.enumeration(combined);
                }
            };
        } catch (Exception e) {
            LOGGER.error("[ReForged] Failed to create classloader", e);
            return null;
        }
    }

    /**
     * Extract {@code META-INF/jarjar/*.jar} entries of a jar into {@code jijTemp},
     * then recurse into each extracted jar (bounded depth).
     */
    private static void extractJiJRecursively(Path jar, Path jijTemp, List<URL> urls,
                                              List<Path> extractedJiJJars, java.util.Map<String, String> identities, int depth) {
        if (depth > 5) {
            throw new IllegalStateException("JiJ nesting exceeds supported depth: " + jar);
        }
        List<Path> extractedHere = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (JarEntry entry : jarFile.stream().sorted(java.util.Comparator.comparing(JarEntry::getName)).toList()) {
                String name = entry.getName();
                if (name.startsWith("META-INF/jarjar/") && name.endsWith(".jar") && !entry.isDirectory()) {
                    String fileName = name.substring(name.lastIndexOf('/') + 1);
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        byte[] bytes = is.readAllBytes();
                        String digest = sha256(bytes);
                        String identity = nestedIdentity(jarFile, name, fileName);
                        String previousDigest = identities.putIfAbsent(identity, digest);
                        if (previousDigest != null) {
                            if (previousDigest.equals(digest)) continue;
                            throw new IllegalStateException("Conflicting embedded dependency " + identity
                                    + " in " + jar + "; automatic version selection is not supported");
                        }
                        Path extracted = jijTemp.resolve(digest + ".jar");
                        if (Files.exists(extracted)) {
                            continue;
                        }
                        Files.write(extracted, bytes);
                        extracted.toFile().deleteOnExit();
                        urls.add(extracted.toUri().toURL());
                        extractedHere.add(extracted);
                        if (extractedJiJJars != null) {
                            extractedJiJJars.add(extracted);
                        }
                        LOGGER.info("[ReForged] Extracted JiJ dependency: {} from {} (depth {})",
                                extracted.getFileName(), jar.getFileName(), depth);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot extract JiJ dependencies from " + jar, e);
        }
        for (Path nested : extractedHere) {
            extractJiJRecursively(nested, jijTemp, urls, extractedJiJJars, identities, depth + 1);
        }
    }

    private static String nestedIdentity(JarFile jar, String entryName, String fallback) throws java.io.IOException {
        JarEntry metadata = jar.getJarEntry("META-INF/jarjar/metadata.json");
        if (metadata == null) return fallback;
        try (InputStream input = jar.getInputStream(metadata)) {
            var entries = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("jars");
            for (var candidate : entries) {
                var item = candidate.getAsJsonObject();
                if (entryName.equals(item.get("path").getAsString())) {
                    var identifier = item.getAsJsonObject("identifier");
                    return identifier.get("group").getAsString() + ":" + identifier.get("artifact").getAsString();
                }
            }
            throw new IllegalStateException("Embedded JAR missing from JiJ metadata: " + entryName);
        }
    }

    /**
     * Read every {@code META-INF/reforged-parent-classes.txt} visible to the
     * parent (TRANSFORMER) classloader. These are written into placeholder jars
     * by {@link NeoMixinExtractor} and list classes that must keep a single
     * identity across the TRANSFORMER and NeoMod classloaders.
     */
    private static Set<String> loadPlaceholderParentClasses(ClassLoader parentLoader) {
        Set<String> result = new HashSet<>();
        try {
            Enumeration<URL> resources = parentLoader.getResources(NeoMixinExtractor.PARENT_CLASSES_ENTRY);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) {
                            result.add(line);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ReForged] Could not read parent-class list from {}: {}", url, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[ReForged] Parent-class list lookup failed: {}", e.getMessage());
        }
        if (!result.isEmpty()) {
            LOGGER.info("[ReForged] {} class(es) pinned parent-first for mixin identity", result.size());
        }
        return result;
    }

    /**
     * Extract a mod jar into a cache directory keyed by a content digest and
     * cache format version. A lock and completion marker make concurrent
     * loader creation safe.
     *
     * @return the extraction root directory, or null on failure
     */
    private static Path extractJarToCache(Path jar) {
        try {
            String digest = sha256(jar);
            String dirName = jar.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_")
                    + "-v2-" + digest;
            Path cacheRoot = Path.of(System.getProperty("user.dir", "."))
                    .resolve(".reforged").resolve("extracted");
            Path target = cacheRoot.resolve(dirName);
            Path marker = target.resolve(".reforged-complete");
            Path lockPath = cacheRoot.resolve(dirName + ".lock");
            Files.createDirectories(cacheRoot);

            try (FileChannel channel = FileChannel.open(lockPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (Files.isRegularFile(marker)) {
                    LOGGER.debug("[ReForged] Using cached extraction for {}", jar.getFileName());
                    return target;
                }

                // (Re-)extract: clear any partial leftovers first.
                if (Files.exists(target)) {
                    deleteRecursively(target);
                }
                Files.createDirectories(target);

                int files = 0;
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    var entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        Path out = target.resolve(entry.getName()).normalize();
                        if (!out.startsWith(target)) {
                            continue; // zip-slip guard
                        }
                        if (entry.isDirectory()) {
                            Files.createDirectories(out);
                            continue;
                        }
                        Files.createDirectories(out.getParent());
                        try (InputStream is = jarFile.getInputStream(entry)) {
                            Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                        files++;
                    }
                }
                Path markerTmp = target.resolve(".reforged-complete.tmp");
                Files.writeString(markerTmp, "version=2\nsha256=" + digest + "\n");
                try {
                    Files.move(markerTmp, marker, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(markerTmp, marker, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.info("[ReForged] Extracted {} ({} files) to {}", jar.getFileName(), files, target);
                return target;
            }
        } catch (Exception e) {
            LOGGER.warn("[ReForged] Extraction failed for {} — falling back to jar URL: {}",
                    jar.getFileName(), e.getMessage());
            return null;
        }
    }

    private static String sha256(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
