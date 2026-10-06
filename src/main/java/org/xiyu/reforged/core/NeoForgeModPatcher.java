package org.xiyu.reforged.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Patches NeoForge JARs before Forge's own mod-folder scanner sees them.
 *
 * <p>The injected {@code META-INF/mods.toml} is a lowcode discovery descriptor:
 * Forge can record the mod id, but it will not execute the NeoForge entrypoint.
 * ReForged still loads the real mod from {@code neoforge.mods.toml} later.</p>
 *
 * <p>Since the placeholder jar is the only part of a NeoForge mod that lives on
 * the game layer (TRANSFORMER classloader), it now also carries the mod's own
 * Mixin payload — configs, mixin classes and their reference closure — so that
 * Sponge Mixin can patch vanilla classes on behalf of the NeoForge mod (see
 * {@link NeoMixinExtractor}).</p>
 */
public final class NeoForgeModPatcher {

    private static final Logger LOGGER;
    private static final byte[] DEFAULT_PACK_MCMETA = """
            {
              "pack": {
                "description": "ReForged NeoForge mod resources",
                "pack_format": 32
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

    static {
        Logger tempLogger;
        try {
            tempLogger = LogUtils.getLogger();
        } catch (Throwable t) {
            tempLogger = null;
        }
        LOGGER = tempLogger;
    }

    private NeoForgeModPatcher() {}

    private static void log(String msg) {
        if (LOGGER != null) LOGGER.info(msg);
        else System.out.println(msg);
    }

    private static void warn(String msg, Throwable t) {
        if (LOGGER != null) LOGGER.warn(msg, t);
        else {
            System.err.println(msg);
            if (t != null) t.printStackTrace();
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: NeoForgeModPatcher <modsDir>");
            System.exit(1);
        }
        Path modsDir = Path.of(args[0]);
        int patched = patchAll(modsDir);
        log("[ReForged] NeoForgeModPatcher: " + patched + " NeoForge mod(s) patched in " + modsDir);
    }

    public static int patchAll(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return 0;

        int count = 0;
        try (var stream = Files.list(modsDir)) {
            for (Path jar : stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                if (patchIfNeeded(jar)) count++;
                else if (requiresPatching(jar)) {
                    throw new IllegalStateException("Required NeoForge preprocessing failed for " + jar);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot complete NeoForge preprocessing in " + modsDir, e);
        }
        return count;
    }

    /**
     * Forge's early service scanner may already hold the input JAR open on Windows.
     * Startup therefore discovers patched copies, never replacing those open inputs.
     */
    public static synchronized List<Path> prepareForDiscovery(Path modsDir) {
        if (!Files.isDirectory(modsDir)) return List.of();
        List<Path> prepared = new ArrayList<>();
        String rulesIdentity = null;
        try (var files = Files.list(modsDir)) {
            for (Path input : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                try (JarFile jar = new JarFile(input.toFile())) {
                    JarEntry neo = jar.getJarEntry("META-INF/neoforge.mods.toml");
                    if (neo == null) continue;
                    JarEntry forge = jar.getJarEntry("META-INF/mods.toml");
                    if (forge != null) {
                        if (isReForgedDiscoveryDescriptor(jar, forge)
                                && !Files.isRegularFile(input.resolveSibling(input.getFileName() + ".neoforge-original"))) {
                            throw new IllegalStateException("Placeholder original is missing: " + input);
                        }
                        // Existing Forge descriptors are handled by Forge's folder locator.
                        continue;
                    }
                    try (InputStream metadata = jar.getInputStream(neo)) {
                        if (ModDescriptorConverter.declaresReForgedIncompatibility(
                                new String(metadata.readAllBytes(), StandardCharsets.UTF_8))) continue;
                    }
                }
                String digest = sha256(input);
                if (rulesIdentity == null) rulesIdentity = discoveryRulesIdentity();
                String cacheIdentity = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((digest + ":" + rulesIdentity).getBytes(StandardCharsets.UTF_8)));
                Path cache = modsDir.toAbsolutePath().normalize().getParent()
                        .resolve(".reforged/discovery/v3-" + cacheIdentity);
                Files.createDirectories(cache);
                Path generated = cache.resolve(input.getFileName());
                try (FileChannel channel = FileChannel.open(cache.resolve("prepare.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                    Path receipt = generated.resolveSibling(generated.getFileName() + ".complete");
                    if (!validDiscoveryCache(generated, receipt, digest, rulesIdentity)) {
                        Files.deleteIfExists(receipt);
                        Files.copy(input, generated, StandardCopyOption.REPLACE_EXISTING);
                        if (!digest.equals(sha256(generated)) || !patchIfNeeded(generated)) {
                            throw new IllegalStateException("Cannot prepare required discovery artifact for " + input);
                        }
                        Path backup = generated.resolveSibling(generated.getFileName() + ".neoforge-original");
                        if (!digest.equals(sha256(backup))) throw new IOException("Discovery backup digest mismatch: " + backup);
                        Path temporaryReceipt = receipt.resolveSibling(receipt.getFileName() + ".tmp");
                        Files.writeString(temporaryReceipt, discoveryReceipt(digest, rulesIdentity, sha256(generated)),
                                StandardCharsets.UTF_8);
                        moveAtomically(temporaryReceipt, receipt);
                    }
                    if (!digest.equals(sha256(input))) throw new IOException("NeoForge input changed during discovery: " + input);
                }
                prepared.add(generated);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prepare NeoForge discovery artifacts from " + modsDir, e);
        }
        return List.copyOf(prepared);
    }

    private static boolean validDiscoveryCache(Path generated, Path receipt, String inputDigest, String rulesIdentity) {
        try {
            Path backup = generated.resolveSibling(generated.getFileName() + ".neoforge-original");
            return Files.isRegularFile(receipt) && isReForgedDiscoveryJar(generated)
                    && inputDigest.equals(sha256(backup))
                    && Files.readString(receipt, StandardCharsets.UTF_8)
                            .equals(discoveryReceipt(inputDigest, rulesIdentity, sha256(generated)));
        } catch (IOException invalidCache) {
            return false;
        }
    }

    private static String discoveryReceipt(String inputDigest, String rulesIdentity, String outputDigest) {
        return "format=1\ninput=" + inputDigest + "\nrules=" + rulesIdentity + "\noutput=" + outputDigest + "\n";
    }

    private static String discoveryRulesIdentity() throws Exception {
        Path source = Path.of(NeoForgeModPatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(source)) return sha256(source);
        if (!Files.isDirectory(source)) throw new IOException("Cannot identify discovery converter artifact: " + source);
        // Gradle's manual patch command runs compiled classes/resources in a directory.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(source.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    public static synchronized boolean patchIfNeeded(Path jarPath) {
        Path lockPath = jarPath.resolveSibling(jarPath.getFileName() + ".reforged.lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return patchIfNeededLocked(jarPath);
        } catch (Exception e) {
            warn("[ReForged] Could not acquire patch lock for " + jarPath.getFileName(), e);
            return false;
        }
    }

    // false means either not applicable or failed; startup must distinguish them.
    private static boolean requiresPatching(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            JarEntry neo = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neo == null) return false;
            JarEntry forge = jar.getJarEntry("META-INF/mods.toml");
            if (forge != null) return isReForgedDiscoveryDescriptor(jar, forge);
            try (InputStream input = jar.getInputStream(neo)) {
                return !ModDescriptorConverter.declaresReForgedIncompatibility(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static boolean patchIfNeededLocked(Path jarPath) {
        // Cleanup is inside the per-JAR lock: another launcher may be writing it.
        try {
            Files.deleteIfExists(jarPath.resolveSibling(jarPath.getFileName() + ".tmp"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clean interrupted patch for " + jarPath, e);
        }
        Path backup = jarPath.resolveSibling(jarPath.getFileName() + ".neoforge-original");
        boolean currentIsPlaceholder = isReForgedDiscoveryJar(jarPath);
        if (currentIsPlaceholder && !Files.exists(backup)) {
            warn("[ReForged] Refusing to rebuild a placeholder without its original backup: "
                    + jarPath.getFileName(), null);
            return false;
        }

        // A current JAR without a ReForged marker is a new source, even when an
        // old backup with the same file name exists. This prevents A -> B updates
        // from silently rebuilding B's placeholder from A.
        Path sourcePath = currentIsPlaceholder ? backup : jarPath;
        boolean sourceIsBackup = currentIsPlaceholder;
        if (!Files.exists(sourcePath)) return false;
        final String sourceDigest;
        try {
            sourceDigest = sha256(sourcePath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot fingerprint NeoForge input " + sourcePath, e);
        }

        String neoContent;
        byte[][] entryData;
        String[] entryNames;
        boolean hasPackMeta = false;
        NeoMixinExtractor.Result mixinPayload;

        try (JarFile jar = new JarFile(sourcePath.toFile())) {
            JarEntry neoEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neoEntry == null) return false;

            JarEntry existingForgeDescriptor = jar.getJarEntry("META-INF/mods.toml");
            if (existingForgeDescriptor != null && !isReForgedDiscoveryDescriptor(jar, existingForgeDescriptor)) {
                return false;
            }

            try (InputStream is = jar.getInputStream(neoEntry)) {
                neoContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (ModDescriptorConverter.declaresReForgedIncompatibility(neoContent)) {
                log("[ReForged] Skipping mod that declares ReForged incompatible: "
                        + jarPath.getFileName());
                if (sourceIsBackup) {
                    restoreOriginal(jarPath, backup);
                }
                return false;
            }

            // Extract the mod's own mixin payload (configs + class closure) so it
            // can be applied by Forge's Mixin environment via the placeholder jar.
            NeoMixinExtractor.Result payload;
            try {
                payload = NeoMixinExtractor.extract(jar, neoContent,
                        msg -> log("[ReForged] [" + jarPath.getFileName() + "] " + msg));
            } catch (Throwable t) {
                warn("[ReForged] Mixin extraction failed for " + jarPath.getFileName()
                        + " — refusing to create a placeholder without its required payload", t);
                return false;
            }
            mixinPayload = payload;

            var entries = jar.entries();
            var nameList = new ArrayList<String>();
            var dataList = new ArrayList<byte[]>();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (shouldDropFromForgePlaceholder(entryName)) {
                    continue;
                }
                // Mixin payload entries are written separately with rewritten content.
                if (mixinPayload.mixinConfigs().containsKey(entryName)
                        || mixinPayload.classFiles().containsKey(entryName)
                        || mixinPayload.extraResources().containsKey(entryName)) {
                    continue;
                }

                if ("pack.mcmeta".equals(entryName)) {
                    hasPackMeta = true;
                }

                nameList.add(entryName);
                if (entry.isDirectory()) {
                    dataList.add(new byte[0]);
                    continue;
                }

                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] bytes = is.readAllBytes();
                    if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entryName)) {
                        bytes = sanitizeManifest(bytes, mixinPayload.configNames());
                    }
                    dataList.add(bytes);
                }
            }
            entryNames = nameList.toArray(new String[0]);
            entryData = dataList.toArray(new byte[0][]);
        } catch (Exception e) {
            warn("[ReForged] Failed to read JAR: " + jarPath.getFileName(), e);
            return false;
        }

        String forgeContent = ModDescriptorConverter.convertForForgeDiscovery(neoContent);
        log("[ReForged] Patching NeoForge mod: " + jarPath.getFileName()
                + (mixinPayload.isEmpty() ? "" : " (+mixin payload)"));

        Path tempJar = jarPath.resolveSibling(jarPath.getFileName() + ".tmp");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(tempJar.toFile()))) {
            boolean wroteManifest = false;
            for (int i = 0; i < entryNames.length; i++) {
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entryNames[i])) {
                    wroteManifest = true;
                }
                out.putNextEntry(new JarEntry(entryNames[i]));
                if (entryData[i].length > 0) {
                    out.write(entryData[i]);
                }
                out.closeEntry();
            }

            // Jars without a manifest still need one if a mixin payload exists,
            // because Mixin discovers configs via the MixinConfigs attribute.
            if (!wroteManifest && !mixinPayload.isEmpty()) {
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().putValue("MixinConfigs",
                        String.join(",", mixinPayload.configNames()));
                out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                manifest.write(out);
                out.closeEntry();
            }

            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(forgeContent.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            if (!hasPackMeta) {
                out.putNextEntry(new JarEntry("pack.mcmeta"));
                out.write(DEFAULT_PACK_MCMETA);
                out.closeEntry();
            }

            // ── Mixin payload ──
            for (var entry : mixinPayload.mixinConfigs().entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (var entry : mixinPayload.classFiles().entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
            for (var entry : mixinPayload.extraResources().entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (Exception e) {
            warn("[ReForged] Failed to write patched JAR: " + jarPath.getFileName(), e);
            return false;
        }

        try {
            if (!sourceDigest.equals(sha256(sourcePath))) {
                throw new IOException("NeoForge input changed during patching: " + sourcePath);
            }
            if (!sourceIsBackup) {
                archivePreviousBackup(backup);
                Files.copy(jarPath, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomically(tempJar, jarPath);
            log("[ReForged] Patched: " + jarPath.getFileName()
                    + " (sourceSha256=" + sourceDigest + ")");
            return true;
        } catch (Exception e) {
            warn("[ReForged] Failed to replace JAR: " + jarPath.getFileName(), e);
            return false;
        }
    }

    private static boolean isReForgedDiscoveryJar(Path jarPath) {
        if (!Files.isRegularFile(jarPath)) return false;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry forgeEntry = jar.getJarEntry("META-INF/mods.toml");
            return forgeEntry != null && isReForgedDiscoveryDescriptor(jar, forgeEntry);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void archivePreviousBackup(Path backup) throws IOException {
        if (!Files.exists(backup)) return;
        String oldHash = sha256(backup);
        Path archived = backup.resolveSibling(backup.getFileName() + "." + oldHash);
        if (!Files.exists(archived)) {
            moveAtomically(backup, archived);
        } else {
            Files.deleteIfExists(backup);
        }
    }

    private static void restoreOriginal(Path jarPath, Path backup) throws IOException {
        Path temp = jarPath.resolveSibling(jarPath.getFileName() + ".optout.tmp");
        Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING);
        moveAtomically(temp, jarPath);
        log("[ReForged] Restored opted-out NeoForge JAR: " + jarPath.getFileName());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static boolean shouldDropFromForgePlaceholder(String entryName) {
        String lower = entryName.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("meta-inf/services/")) {
            // Keep ordinary ServiceLoader declarations so placeholder-side mod
            // classes (mixin closure) can self-initialize on the TRANSFORMER
            // loader. Drop launch-level services that would let the mod hook
            // into ModLauncher/Mixin bootstrap.
            return lower.startsWith("meta-inf/services/cpw.mods.")
                    || lower.startsWith("meta-inf/services/org.spongepowered.")
                    || lower.startsWith("meta-inf/services/net.minecraftforge.")
                    || lower.startsWith("meta-inf/services/net.neoforged.neoforgespi.")
                    || lower.startsWith("meta-inf/services/javax.annotation.");
        }
        return "meta-inf/mods.toml".equals(lower)
                || lower.endsWith(".class")
                || lower.startsWith("meta-inf/jarjar/")
                || lower.startsWith("meta-inf/accesstransformer")
                || lower.endsWith(".mixins.json")
                || lower.endsWith(".mixin.json")
                || lower.endsWith(".refmap.json");
    }

    private static boolean isReForgedDiscoveryDescriptor(JarFile jar, JarEntry entry) {
        try (InputStream is = jar.getInputStream(entry)) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return content.contains(ModDescriptorConverter.REFORGED_DISCOVERY_MARKER);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sanitizeManifest(byte[] input, List<String> mixinConfigNames) {
        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(input));
            Attributes attrs = manifest.getMainAttributes();
            attrs.remove(new Attributes.Name("MixinConfigs"));
            attrs.remove(new Attributes.Name("TweakClass"));
            attrs.remove(new Attributes.Name("TweakOrder"));
            if (mixinConfigNames != null && !mixinConfigNames.isEmpty()) {
                attrs.putValue("MixinConfigs", String.join(",", mixinConfigNames));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            manifest.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            warn("[ReForged] Failed to sanitize NeoForge jar manifest; copying original manifest", e);
            return input;
        }
    }
}
