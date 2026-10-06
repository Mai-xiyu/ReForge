package org.xiyu.reforged.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class NestedDependencyRegressionTest {
    @TempDir Path dir;

    byte[] library(String value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes)) {
            var entry = new JarEntry("library.txt"); entry.setTime(0);
            output.putNextEntry(entry); output.write(value.getBytes(StandardCharsets.UTF_8)); output.closeEntry();
        }
        return bytes.toByteArray();
    }

    Path parent(String name, String child, byte[] bytes, boolean metadata) throws Exception {
        Path path = dir.resolve(name);
        String entry = "META-INF/jarjar/" + child;
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(entry)); output.write(bytes); output.closeEntry();
            if (metadata) {
                output.putNextEntry(new JarEntry("META-INF/jarjar/metadata.json"));
                output.write(("{\"jars\":[{\"path\":\"" + entry + "\",\"identifier\":{\"group\":\"fixture\",\"artifact\":\"library\"}}]}").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    Path modJar(String name, String resource) throws Exception {
        Path path = dir.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("resource.txt"));
            output.write(resource.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    void inIsolatedCache(org.junit.jupiter.api.function.Executable operation) throws Throwable {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try { operation.execute(); } finally { System.setProperty("user.dir", previous); }
    }

    @Test void sameCoordinateDifferentContentRejectsBothInputOrders() throws Throwable {
        Path a = parent("a.jar", "library-1.jar", library("A"), true);
        Path b = parent("b.jar", "library-2.jar", library("B"), true);
        inIsolatedCache(() -> {
            assertNull(NeoModClassLoader.createClassLoader(List.of(a,b), getClass().getClassLoader(), null));
            assertNull(NeoModClassLoader.createClassLoader(List.of(b,a), getClass().getClassLoader(), null));
        });
    }

    @Test void sameFilenameWithoutMetadataCannotSilentlySelectFirst() throws Throwable {
        Path a = parent("a.jar", "library.jar", library("A"), false);
        Path b = parent("b.jar", "library.jar", library("B"), false);
        inIsolatedCache(() -> {
            assertNull(NeoModClassLoader.createClassLoader(List.of(a,b), getClass().getClassLoader(), null));
            assertNull(NeoModClassLoader.createClassLoader(List.of(b,a), getClass().getClassLoader(), null));
        });
    }

    @Test void identicalContentDeduplicatesWithSameIdentityInEitherOrder() throws Throwable {
        byte[] library = library("shared");
        Path a = parent("a.jar", "library-1.jar", library, true);
        Path b = parent("b.jar", "library-2.jar", library, true);
        inIsolatedCache(() -> {
            String selected = null;
            for (var paths : List.of(List.of(a,b), List.of(b,a))) {
                List<Path> extracted = new ArrayList<>();
                try (var loader = NeoModClassLoader.createClassLoader(paths, getClass().getClassLoader(), extracted)) {
                    assertNotNull(loader); assertEquals(1, extracted.size());
                    assertArrayEquals(library, Files.readAllBytes(extracted.getFirst()));
                    if (selected != null) assertEquals(selected, extracted.getFirst().getFileName().toString());
                    selected = extracted.getFirst().getFileName().toString();
                    try (var input = loader.getResourceAsStream("library.txt")) {
                        assertNotNull(input); assertEquals("shared", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        });
    }

    @Test void corruptEmbeddedLibraryRejectsIncompleteClassLoader() throws Throwable {
        Path path = parent("broken.jar", "library.jar", new byte[]{0,1,2}, false);
        inIsolatedCache(() -> assertNull(NeoModClassLoader.createClassLoader(List.of(path), getClass().getClassLoader(), null)));
    }

    @Test void corruptedExtractionWithCompletionMarkerIsRebuilt() throws Throwable {
        Path path = modJar("mod.jar", "original");
        inIsolatedCache(() -> {
            try (var loader = NeoModClassLoader.createClassLoader(List.of(path), getClass().getClassLoader(), null)) {
                assertNotNull(loader);
                try (var input = loader.getResourceAsStream("resource.txt")) {
                    assertEquals("original", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            Path extractedRoot = dir.resolve(".reforged/extracted");
            Path extracted;
            try (var entries = Files.list(extractedRoot)) {
                extracted = entries.filter(Files::isDirectory).findFirst().orElseThrow();
            }
            Files.writeString(extracted.resolve("resource.txt"), "corrupted");
            try (var loader = NeoModClassLoader.createClassLoader(List.of(path), getClass().getClassLoader(), null)) {
                assertNotNull(loader);
                try (var input = loader.getResourceAsStream("resource.txt")) {
                    assertEquals("original", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        });
    }

    @Test void missingResourceExtraFileAndInvalidReceiptAreRepaired() throws Throwable {
        Path source = modJar("mod.jar", "original");
        byte[] original = Files.readAllBytes(source);
        inIsolatedCache(() -> {
            Path cache;
            try (var loader = NeoModClassLoader.createClassLoader(List.of(source), getClass().getClassLoader(), null)) {
                assertNotNull(loader);
                cache = Path.of(loader.getURLs()[0].toURI());
            }
            for (int damage = 0; damage < 3; damage++) {
                if (damage == 0) Files.delete(cache.resolve("resource.txt"));
                if (damage == 1) Files.writeString(cache.resolve("injected.txt"), "not from source");
                if (damage == 2) Files.writeString(cache.resolve(".reforged-complete"), "incomplete");
                try (var loader = NeoModClassLoader.createClassLoader(List.of(source), getClass().getClassLoader(), null)) {
                    assertNotNull(loader);
                    assertEquals(cache, Path.of(loader.getURLs()[0].toURI()));
                    try (var input = loader.getResourceAsStream("resource.txt")) {
                        assertEquals("original", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    assertNull(loader.findResource("injected.txt"));
                    assertArrayEquals(original, Files.readAllBytes(source));
                }
            }
        });
    }

    @Test void extractionFailureDoesNotFallBackToJarResourceUrls() throws Throwable {
        Path source = modJar("mod.jar", "original");
        inIsolatedCache(() -> {
            Files.createDirectory(dir.resolve(".reforged"));
            Files.writeString(dir.resolve(".reforged/extracted"), "blocked directory");
            assertNull(NeoModClassLoader.createClassLoader(List.of(source), getClass().getClassLoader(), null));
        });
    }

    @Test void escapingAndReservedArchiveEntriesBlockExtraction() throws Throwable {
        inIsolatedCache(() -> {
            for (String entry : List.of("../escaped.txt", ".reforged-complete", ".reforged-complete.tmp")) {
                Path source = dir.resolve("invalid.jar");
                try (var output = new JarOutputStream(Files.newOutputStream(source))) {
                    output.putNextEntry(new JarEntry(entry));
                    output.write("invalid".getBytes(StandardCharsets.UTF_8)); output.closeEntry();
                }
                assertNull(NeoModClassLoader.createClassLoader(List.of(source), getClass().getClassLoader(), null));
                assertFalse(Files.exists(dir.resolve(".reforged/extracted/escaped.txt")));
            }
        });
    }
}
