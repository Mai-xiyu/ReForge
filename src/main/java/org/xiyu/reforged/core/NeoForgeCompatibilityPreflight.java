package org.xiyu.reforged.core;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.mojang.logging.LogUtils;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Metadata validation before constructors run. A pass does not certify gameplay behavior. */
public final class NeoForgeCompatibilityPreflight {
    private static final Logger LOGGER = LogUtils.getLogger();
    private NeoForgeCompatibilityPreflight() {}

    public enum Status { PASS, WARN, FAIL, UNKNOWN }
    public record Finding(String modId, String check, Status status, String detail) {}
    public record Report(Path jar, String modId, List<Finding> findings) {
        public boolean hasFailure() { return findings.stream().anyMatch(f -> f.status() == Status.FAIL); }
    }
    public record Environment(String minecraft, String forge, int javaFeature, String side,
                              Map<String, String> installedMods) {
        public Environment { installedMods = Map.copyOf(installedMods); }
    }
    private record Metadata(Path jar, Map<String, String> mods,
                            Map<String, List<UnmodifiableConfig>> dependencies) {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: NeoForgeCompatibilityPreflight <mod.jar> [...]");
            System.exit(2);
        }
        if (analyzeAndReport(Arrays.stream(args).map(Path::of).toList()).stream().anyMatch(Report::hasFailure))
            System.exit(1);
    }

    public static List<Report> analyzeAndReport(List<Path> jars) { return analyze(jars, detectEnvironment()); }
    public static Report analyzeJar(Path jar) throws IOException { return analyzeAndReport(List.of(jar)).getFirst(); }

    /** Malformed/unreadable input stays in the report as FAIL. */
    public static List<Report> analyze(List<Path> jars, Environment environment) {
        Map<Path, Metadata> metadata = new LinkedHashMap<>();
        Map<String, String> versions = new HashMap<>(environment.installedMods());
        Set<String> discovered = new HashSet<>(), duplicates = new HashSet<>();
        List<Report> reports = new ArrayList<>();
        for (Path jar : jars) {
            try {
                Metadata item = read(jar);
                metadata.put(jar, item);
                item.mods().forEach((id, version) -> {
                    if (!discovered.add(id)) duplicates.add(id);
                    versions.put(id, version);
                });
            } catch (Exception e) {
                reports.add(new Report(jar, "(unknown)", List.of(new Finding("(unknown)", "metadata",
                        Status.FAIL, Objects.toString(e.getMessage(), e.getClass().getName())))));
            }
        }
        for (Metadata item : metadata.values()) {
            List<Finding> findings = new ArrayList<>();
            for (String id : item.mods().keySet()) {
                findings.add(new Finding(id, "metadata", duplicates.contains(id) ? Status.FAIL : Status.PASS,
                        duplicates.contains(id) ? "Duplicate modId " + id : "modId=" + id));
                for (UnmodifiableConfig dep : item.dependencies().getOrDefault(id, List.of()))
                    checkDependency(id, dep, environment, versions, findings);
            }
            reports.add(new Report(item.jar(), item.mods().keySet().stream().findFirst().orElse("(library)"),
                    List.copyOf(findings)));
        }
        reports.forEach(NeoForgeCompatibilityPreflight::log);
        return List.copyOf(reports);
    }

    /** Validates dependencies and orders JARs before any entrypoint is initialized. */
    public static List<Path> validateAndOrder(List<Path> jars, Environment env) {
        List<String> failures = analyze(jars, env).stream().flatMap(r -> r.findings().stream())
                .filter(f -> f.status() == Status.FAIL).map(f -> f.modId() + " " + f.check() + ": " + f.detail()).toList();
        if (!failures.isEmpty()) throw new IllegalStateException("ReForged preflight failed: " + failures);
        Map<Path, Set<Path>> predecessors = new LinkedHashMap<>();
        Map<String, Path> owners = new HashMap<>();
        Map<Path, Metadata> metadata = new LinkedHashMap<>();
        for (Path jar : jars) {
            try {
                Metadata item = read(jar);
                metadata.put(jar, item);
                item.mods().keySet().forEach(id -> owners.put(id, jar));
                predecessors.put(jar, new LinkedHashSet<>());
            } catch (IOException e) { throw new IllegalStateException("Metadata changed after preflight: " + jar, e); }
        }
        metadata.forEach((jar, item) -> item.dependencies().values().forEach(list -> list.forEach(dep -> {
            if (!applies(dep, env.side())) return;
            Path other = owners.get(dep.<String>get("modId"));
            if (other == null || other.equals(jar)) return;
            String type = dep.getOrElse("type", "required");
            if (type.equals("incompatible") || type.equals("discouraged")) return;
            String ordering = dep.getOrElse("ordering", "NONE");
            if (ordering.equalsIgnoreCase("BEFORE")) predecessors.get(other).add(jar);
            else if (ordering.equalsIgnoreCase("AFTER")) predecessors.get(jar).add(other);
        })));
        List<Path> ordered = new ArrayList<>();
        while (!predecessors.isEmpty()) {
            Path ready = predecessors.entrySet().stream().filter(e -> e.getValue().isEmpty())
                    .map(Map.Entry::getKey).min(Comparator.comparing(Path::toString)).orElseThrow(
                            () -> new IllegalStateException("ReForged dependency ordering cycle: " + predecessors.keySet()));
            ordered.add(ready);
            predecessors.remove(ready);
            predecessors.values().forEach(set -> set.remove(ready));
        }
        return ordered;
    }

    private static void checkDependency(String owner, UnmodifiableConfig dep, Environment env,
                                        Map<String, String> versions, List<Finding> findings) {
        if (!applies(dep, env.side())) return;
        String id = dep.get("modId");
        if (id == null || id.isBlank()) {
            findings.add(new Finding(owner, "dependency", Status.FAIL, "Dependency modId is missing"));
            return;
        }
        String type = dep.getOrElse("type", dep.<Boolean>getOrElse("mandatory", true) ? "required" : "optional");
        String range = dep.getOrElse("versionRange", "");
        String current = switch (id) {
            case "minecraft" -> env.minecraft();
            case "forge" -> env.forge();
            case "java" -> Integer.toString(env.javaFeature());
            case "neoforge" -> null;
            case "reforged" -> versions.getOrDefault(id, "1.0.0");
            default -> versions.get(id);
        };
        if (id.equals("neoforge")) {
            findings.add(new Finding(owner, "neoforge-api", Status.UNKNOWN,
                    "Declared " + range + "; shim API coverage needs runtime verification"));
            return;
        }
        if (current == null || current.isBlank()) {
            boolean runtimeUnknown = Set.of("minecraft", "forge", "java").contains(id);
            findings.add(new Finding(owner, id + "-dependency", runtimeUnknown ? Status.UNKNOWN
                    : type.equals("required") ? Status.FAIL : Status.PASS,
                    "Dependency is unavailable; type=" + type + ", range=" + range));
            return;
        }
        try {
            boolean matches = matchesVersion(range, current);
            Status status = type.equals("incompatible") && matches || !matches && type.equals("required")
                    ? Status.FAIL : type.equals("discouraged") && matches ? Status.WARN : Status.PASS;
            findings.add(new Finding(owner, id + "-version", status,
                    "type=" + type + ", declared " + range + ", running " + current));
        } catch (Exception e) {
            findings.add(new Finding(owner, id + "-version", Status.FAIL, "Invalid version range: " + range));
        }
    }

    private static boolean applies(UnmodifiableConfig dep, String side) {
        String declared = dep.getOrElse("side", "BOTH");
        return side.isBlank() || declared.equalsIgnoreCase("BOTH") || declared.equalsIgnoreCase(side);
    }
    private static boolean matchesVersion(String range, String current) throws Exception {
        if (range.isBlank() || range.equals("*")) return true;
        VersionRange parsed = VersionRange.createFromVersionSpec(range);
        DefaultArtifactVersion actual = new DefaultArtifactVersion(current);
        return parsed.hasRestrictions() ? parsed.containsVersion(actual)
                : parsed.getRecommendedVersion().compareTo(actual) == 0;
    }

    private static Metadata read(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            // Nested plain libraries have no mod entrypoint.
            if (entry == null) return new Metadata(path, Map.of(), Map.of());
            UnmodifiableConfig config;
            try (var input = jar.getInputStream(entry)) {
                config = new TomlParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            String jarVersion = jar.getManifest() == null ? "" : Objects.toString(
                    jar.getManifest().getMainAttributes().getValue("Implementation-Version"), "");
            Map<String, String> mods = new LinkedHashMap<>();
            List<UnmodifiableConfig> declarations = config.getOrElse("mods", List.of());
            if (declarations.isEmpty()) throw new IOException("No [[mods]] declaration in " + path);
            for (UnmodifiableConfig mod : declarations) {
                String id = mod.get("modId");
                if (id == null || id.isBlank()) throw new IOException("Invalid modId in " + path);
                String version = mod.getOrElse("version", jarVersion);
                if (version.equals("${file.jarVersion}")) version = jarVersion;
                if (version.isBlank()) throw new IOException("Missing version for " + id);
                if (mods.putIfAbsent(id, version) != null) throw new IOException("Duplicate modId " + id);
            }
            Map<String, List<UnmodifiableConfig>> dependencies = new LinkedHashMap<>();
            UnmodifiableConfig table = config.get("dependencies");
            if (table != null) for (var dep : table.entrySet()) {
                @SuppressWarnings("unchecked") List<UnmodifiableConfig> list = (List<UnmodifiableConfig>) dep.getValue();
                dependencies.put(dep.getKey(), list);
            }
            return new Metadata(path, mods, dependencies);
        }
    }
    private static void log(Report report) {
        for (Finding f : report.findings()) {
            if (f.status() == Status.FAIL) LOGGER.error("[ReForged] Preflight {} {} {}: {}",
                    report.jar().getFileName(), f.modId(), f.check(), f.detail());
            else LOGGER.info("[ReForged] Preflight {} {} {} {}: {}", report.jar().getFileName(),
                    f.modId(), f.check(), f.status(), f.detail());
        }
    }
    public static Environment detectEnvironment() {
        String minecraft = invokeVersion("net.minecraft.SharedConstants", "getCurrentVersion", "net.minecraft.WorldVersion");
        String forge = invokeVersion("net.minecraftforge.versions.forge.ForgeVersion", "getVersion", null);
        if (forge.isBlank()) forge = System.getProperty("forge.version", "");
        Map<String, String> installed = new HashMap<>();
        String side = "";
        try {
            net.minecraftforge.fml.ModList.get().getMods().forEach(mod -> installed.put(mod.getModId(), mod.getVersion().toString()));
            side = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient() ? "CLIENT" : "SERVER";
        } catch (LinkageError | RuntimeException ignored) { /* Offline information remains UNKNOWN. */ }
        return new Environment(minecraft, forge, Runtime.version().feature(), side, installed);
    }
    private static String invokeVersion(String className, String methodName, String resultInterface) {
        try {
            Object value = Class.forName(className).getMethod(methodName).invoke(null);
            if (resultInterface != null) value = Class.forName(resultInterface).getMethod("getName").invoke(value);
            return Objects.toString(value, "");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return ""; }
    }
}
