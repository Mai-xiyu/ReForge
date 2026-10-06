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
}
