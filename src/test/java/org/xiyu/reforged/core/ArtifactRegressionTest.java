package org.xiyu.reforged.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactRegressionTest {
    @TempDir Path dir;

    static Path jar(Path path, String toml, String value) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            if (toml != null) {
                out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
                out.write(toml.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            out.putNextEntry(new JarEntry("version.txt"));
            out.write(value.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return path;
    }
    static String metadata(String id, String version) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n";
    }
    static String read(Path path, String entry) throws Exception {
        try (var jar = new JarFile(path.toFile()); var in = jar.getInputStream(jar.getJarEntry(entry))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    Path backup(Path path) { return path.resolveSibling(path.getFileName() + ".neoforge-original"); }

    @Test void sameNameUpgradePreservesNewInputAndArchivesOldBackup() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        byte[] originalA = Files.readAllBytes(path);
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        assertArrayEquals(originalA, Files.readAllBytes(backup(path)));
        jar(path, metadata("fixture", "2"), "B");
        byte[] originalB = Files.readAllBytes(path);
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        assertEquals("B", read(path, "version.txt"));
        assertArrayEquals(originalB, Files.readAllBytes(backup(path)));
        try (var files = Files.list(dir)) {
            Path archived = files.filter(p -> p.getFileName().toString().startsWith("fixture.jar.neoforge-original.")).findFirst().orElseThrow();
            assertArrayEquals(originalA, Files.readAllBytes(archived));
        }
    }
    @Test void intentionalDeletionDoesNotResurrectBackup() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        Files.delete(path);
        assertEquals(0, NeoForgeModPatcher.patchAll(dir));
        assertFalse(Files.exists(path));
        assertTrue(Files.exists(backup(path)));
    }
    @Test void missingBackupCannotRebuildPlaceholder() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        Files.delete(backup(path));
        byte[] placeholder = Files.readAllBytes(path);
        assertFalse(NeoForgeModPatcher.patchIfNeeded(path));
        assertArrayEquals(placeholder, Files.readAllBytes(path));
        assertThrows(IllegalStateException.class, () -> NeoForgeModPatcher.patchAll(dir));
    }
    @Test void interruptedTempDoesNotReplaceUserUpdate() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        Files.writeString(dir.resolve("fixture.jar.tmp"), "incomplete");
        jar(path, metadata("fixture", "2"), "B");
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        assertEquals("B", read(path, "version.txt"));
        assertFalse(Files.exists(dir.resolve("fixture.jar.tmp")));
    }
    @Test void optOutSkipsPatchingAndRuntimeDiscovery() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1") +
                "[[dependencies.fixture]]\nmodId=\"reforged\"\ntype=\"incompatible\"\nversionRange=\"[1,)\"\n", "A");
        byte[] input = Files.readAllBytes(path);
        assertFalse(NeoForgeModPatcher.patchIfNeeded(path));
        assertArrayEquals(input, Files.readAllBytes(path));
        assertTrue(NeoJarDiscovery.discoverNeoForgeJars(dir).isEmpty());
    }
    @Test void optOutRestoresPatchedOriginal() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        assertTrue(NeoForgeModPatcher.patchIfNeeded(path));
        jar(backup(path), metadata("fixture", "1") + "[[dependencies.fixture]]\nmodId=\"reforged\"\ntype=\"incompatible\"\n", "excluded");
        byte[] original = Files.readAllBytes(backup(path));
        assertFalse(NeoForgeModPatcher.patchIfNeeded(path));
        assertArrayEquals(original, Files.readAllBytes(path));
    }
    @Test void concurrentPatchingNeverCorruptsSource() throws Exception {
        Path path = jar(dir.resolve("fixture.jar"), metadata("fixture", "1"), "A");
        byte[] original = Files.readAllBytes(path);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> NeoForgeModPatcher.patchAll(dir));
            var b = executor.submit(() -> NeoForgeModPatcher.patchAll(dir));
            a.get(); b.get();
        }
        assertEquals("A", read(path, "version.txt"));
        assertArrayEquals(original, Files.readAllBytes(backup(path)));
    }
    @Test void startupDiscoveryDoesNotReplaceOpenInput() throws Exception {
        Path mods=Files.createDirectory(dir.resolve("mods"));
        Path input=jar(mods.resolve("fixture.jar"),metadata("fixture","1"),"A");
        byte[] original=Files.readAllBytes(input);
        try (var opened=new JarFile(input.toFile())) {
            var generated=NeoForgeModPatcher.prepareForDiscovery(mods);
            assertEquals(1,generated.size()); assertNotEquals(input,generated.getFirst());
            assertTrue(read(generated.getFirst(),"META-INF/mods.toml").contains(ModDescriptorConverter.REFORGED_DISCOVERY_MARKER));
            assertArrayEquals(original,Files.readAllBytes(input));
            assertNotNull(opened.getJarEntry("META-INF/neoforge.mods.toml"));
            assertEquals(generated,NeoForgeModPatcher.prepareForDiscovery(mods));
        }
    }
    @Test void discoveryCacheTracksUpgradesAndDoesNotResurrectDeletedInputs() throws Exception {
        Path mods=Files.createDirectory(dir.resolve("mods"));
        Path input=jar(mods.resolve("fixture.jar"),metadata("fixture","1"),"A");
        Path first=NeoForgeModPatcher.prepareForDiscovery(mods).getFirst();
        jar(input,metadata("fixture","2"),"B");
        Path second=NeoForgeModPatcher.prepareForDiscovery(mods).getFirst();
        assertNotEquals(first,second); assertEquals("B",read(second,"version.txt"));
        assertEquals("B",read(input,"version.txt"));
        Files.delete(input); assertTrue(NeoForgeModPatcher.prepareForDiscovery(mods).isEmpty());
    }
    @Test void authorExcludedInputDoesNotGenerateCachedPlaceholder() throws Exception {
        Path mods=Files.createDirectory(dir.resolve("mods"));
        Path input=jar(mods.resolve("fixture.jar"),metadata("fixture","1")+
                "[[dependencies.fixture]]\nmodId=\"reforged\"\ntype=\"incompatible\"\n","A");
        byte[] original=Files.readAllBytes(input);
        assertTrue(NeoForgeModPatcher.prepareForDiscovery(mods).isEmpty());
        assertArrayEquals(original,Files.readAllBytes(input));
    }

    @Test void corruptedCacheWithIntactMarkerIsRebuiltFromInput() throws Exception {
        Path mods = Files.createDirectory(dir.resolve("mods"));
        Path input = jar(mods.resolve("fixture.jar"), metadata("fixture", "1"), "original");
        byte[] original = Files.readAllBytes(input);
        Path generated = NeoForgeModPatcher.prepareForDiscovery(mods).getFirst();
        String marker = read(generated, "META-INF/mods.toml");
        try (var out = new JarOutputStream(Files.newOutputStream(generated))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(marker.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new JarEntry("version.txt"));
            out.write("corrupted".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        assertEquals(generated, NeoForgeModPatcher.prepareForDiscovery(mods).getFirst());
        assertEquals("original", read(generated, "version.txt"));
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    @Test void invalidOrMissingReceiptAndBackupTriggerRebuild() throws Exception {
        Path mods = Files.createDirectory(dir.resolve("mods"));
        Path input = jar(mods.resolve("fixture.jar"), metadata("fixture", "1"), "original");
        byte[] original = Files.readAllBytes(input);
        Path generated = NeoForgeModPatcher.prepareForDiscovery(mods).getFirst();
        Path receipt = generated.resolveSibling("fixture.jar.complete");
        for (int damage = 0; damage < 4; damage++) {
            if (damage == 0) Files.delete(receipt);
            if (damage == 1) Files.writeString(receipt, "incomplete");
            if (damage == 2) Files.delete(backup(generated));
            if (damage == 3) Files.writeString(backup(generated), "corrupted");
            assertEquals(generated, NeoForgeModPatcher.prepareForDiscovery(mods).getFirst());
            assertArrayEquals(original, Files.readAllBytes(backup(generated)));
            assertTrue(Files.readString(receipt).startsWith("format=1\ninput="));
            assertEquals("original", read(generated, "version.txt"));
            assertArrayEquals(original, Files.readAllBytes(input));
        }
    }
}
