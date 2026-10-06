package org.xiyu.reforged.core;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractModProvider;
import net.minecraftforge.forgespi.locating.IModLocator;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

/** Adds the isolated runtime artifact embedded in ReForged's early-service JAR. */
public final class ReForgedModLocator extends AbstractModProvider implements IModLocator {
    public static final String RUNTIME_ENTRY="META-INF/reforged/reforged-runtime.jar";
    @Override public String name() { return "reforged_mod"; }
    @Override public List<ModFileOrException> scanMods() {
        Path directory=FMLPaths.MODSDIR.get();
        if (!Files.isDirectory(directory)) return List.of();
        List<ModFileOrException> found=new ArrayList<>();
        for (Path prepared : NeoForgeModPatcher.prepareForDiscovery(directory)) found.add(createMod(prepared));
        try (var paths=Files.list(directory)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                byte[] runtime;
                try (JarFile jar=new JarFile(path.toFile())) {
                    var entry=jar.getJarEntry(RUNTIME_ENTRY);
                    if (entry==null) continue;
                    try (var input=jar.getInputStream(entry)) { runtime=input.readAllBytes(); }
                }
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(runtime));
                Path root=directory.getParent().resolve(".reforged/runtime"); Files.createDirectories(root);
                Path extracted=root.resolve(hash+".jar");
                try (FileChannel lock=FileChannel.open(root.resolve(hash+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                     FileLock ignored=lock.lock()) {
                    if (!Files.isRegularFile(extracted) || !Arrays.equals(Files.readAllBytes(extracted),runtime)) {
                        Path temp=Files.createTempFile(root,hash,".tmp");
                        try {
                            Files.write(temp,runtime);
                            try { Files.move(temp,extracted,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                            catch (AtomicMoveNotSupportedException e) { Files.move(temp,extracted,StandardCopyOption.REPLACE_EXISTING); }
                        } finally { Files.deleteIfExists(temp); }
                    }
                }
                found.add(createMod(extracted));
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot locate ReForged runtime artifact",e); }
        return found;
    }
}
