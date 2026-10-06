package org.xiyu.reforged.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.xiyu.reforged.core.ArtifactRegressionTest.*;

class PreflightRegressionTest {
    @TempDir Path dir;
    final NeoForgeCompatibilityPreflight.Environment env = new NeoForgeCompatibilityPreflight.Environment(
            "1.21", "51.0.33", 21, "SERVER", Map.of());
    Path input(String id, String deps) throws Exception {
        return jar(dir.resolve(id + ".jar"), metadata(id, "1.0.0") + deps, id);
    }
    String dep(String owner, String id, String type, String range, String extra) {
        return "[[dependencies." + owner + "]]\nmodId=\"" + id + "\"\ntype=\"" + type + "\"\nversionRange=\"" + range + "\"\n" + extra;
    }
    @Test void missingRequiredDependencyBlocksBeforeExecution() throws Exception {
        Path a = input("first", dep("first", "missing", "required", "[1,)", ""));
        var error = assertThrows(IllegalStateException.class, () -> NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a), env));
        assertTrue(error.getMessage().contains("missing"));
    }
    @Test void optionalAndOppositeSideDependenciesDoNotBlock() throws Exception {
        Path a = input("first", dep("first", "missing", "optional", "[1,)", "") +
                dep("first", "clientonly", "required", "[1,)", "side=\"CLIENT\"\n"));
        assertEquals(List.of(a), NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a), env));
    }
    @Test void minecraftMismatchBlocks() throws Exception {
        Path a = input("first", dep("first", "minecraft", "required", "[1.21.1,1.22)", ""));
        assertThrows(IllegalStateException.class, () -> NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a), env));
    }
    @Test void boundedAndExactVersionsPass() throws Exception {
        Path a = input("first", dep("first", "minecraft", "required", "[1.21,1.22)", "") +
                dep("first", "java", "required", "[21,)", "") + dep("first", "forge", "required", "51.0.33", ""));
        assertEquals(List.of(a), NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a), env));
    }
    @Test void incompatibleVersionBlocksOnlyWhenRangeMatches() throws Exception {
        Path b = input("second", "");
        Path a = input("first", dep("first", "second", "incompatible", "[1,2)", ""));
        assertThrows(IllegalStateException.class, () -> NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a,b), env));
        jar(a, metadata("first", "1") + dep("first", "second", "incompatible", "[2,3)", ""), "A");
        assertEquals(2, NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a,b), env).size());
    }
    @Test void dependencyOrderingIsIndependentOfDiscoveryOrder() throws Exception {
        Path a = input("first", dep("first", "second", "required", "[1,)", "ordering=\"AFTER\"\n"));
        Path b = input("second", "");
        assertEquals(List.of(b,a), NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a,b), env));
        assertEquals(List.of(b,a), NeoForgeCompatibilityPreflight.validateAndOrder(List.of(b,a), env));
    }
    @Test void orderingCycleBlocks() throws Exception {
        Path a = input("first", dep("first", "second", "required", "[1,)", "ordering=\"AFTER\"\n"));
        Path b = input("second", dep("second", "first", "required", "[1,)", "ordering=\"AFTER\"\n"));
        assertThrows(IllegalStateException.class, () -> NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a,b), env));
    }
    @Test void duplicateModIdsBlock() throws Exception {
        Path a = input("first", "");
        Path b = jar(dir.resolve("other.jar"), metadata("first", "2"), "B");
        assertThrows(IllegalStateException.class, () -> NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a,b), env));
    }
    @Test void malformedMetadataIsReportedAsFailure() throws Exception {
        Path a = jar(dir.resolve("bad.jar"), "[[mods]\nbad", "A");
        assertTrue(NeoForgeCompatibilityPreflight.analyze(List.of(a),env).getFirst().hasFailure());
    }
    @Test void plainNestedLibraryDoesNotNeedModMetadata() throws Exception {
        Path a = jar(dir.resolve("lib.jar"), null, "library");
        assertEquals(List.of(a), NeoForgeCompatibilityPreflight.validateAndOrder(List.of(a),env));
    }
}
