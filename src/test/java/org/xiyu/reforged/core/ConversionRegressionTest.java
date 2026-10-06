package org.xiyu.reforged.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.xiyu.reforged.asm.BytecodeRewriter;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversionRegressionTest {
    @TempDir Path dir;
    static byte[] classBytes(String name) {
        ClassWriter writer=new ClassWriter(0);
        writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);
        var constructor=writer.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD,0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1,1); constructor.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    @Test void failedRewriteCannotMasqueradeAsOriginalBytes() {
        BytecodeRewriter rewriter=new BytecodeRewriter(); byte[] invalid={0,1,2,3};
        var result=rewriter.rewriteWithStatus(invalid);
        assertEquals(BytecodeRewriter.RewriteStatus.FAILED,result.status());
        assertNotNull(result.failure()); assertThrows(IllegalStateException.class, () -> rewriter.rewrite(invalid));
        assertTrue(rewriter.rewriteWithStatus(classBytes("fixture/Valid")).succeeded());
    }
    @Test void classLoadSuccessAndFailureRestoreThreadContext() throws Exception {
        Path jar=dir.resolve("classes.jar");
        try (var out=new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture/Loaded.class")); out.write(classBytes("fixture/Loaded")); out.closeEntry();
            out.putNextEntry(new JarEntry("fixture/Invalid.class")); out.write(new byte[]{0,1,2}); out.closeEntry();
        }
        String previous=System.getProperty("user.dir"); System.setProperty("user.dir",dir.toString());
        ClassLoader context=Thread.currentThread().getContextClassLoader();
        try (var loader=NeoModClassLoader.createClassLoader(List.of(jar),getClass().getClassLoader(),new ArrayList<>())) {
            assertNotNull(loader); assertNotNull(loader.loadClass("fixture.Loaded"));
            assertSame(context,Thread.currentThread().getContextClassLoader());
            assertThrows(IllegalStateException.class, () -> loader.loadClass("fixture.Invalid"));
            assertSame(context,Thread.currentThread().getContextClassLoader());
        } finally { System.setProperty("user.dir",previous); }
    }
    @Test void contentChangeWithSameNameSizeAndTimestampDoesNotUseOldCache() throws Exception {
        Path jar=ArtifactRegressionTest.jar(dir.resolve("same.jar"),null,"A");
        String previous=System.getProperty("user.dir"); System.setProperty("user.dir",dir.toString());
        var timestamp=Files.getLastModifiedTime(jar); long size=Files.size(jar);
        try {
            try (var loader=NeoModClassLoader.createClassLoader(List.of(jar),getClass().getClassLoader(),null)) {
                assertEquals("A",new String(loader.getResourceAsStream("version.txt").readAllBytes()));
            }
            ArtifactRegressionTest.jar(jar,null,"B"); Files.setLastModifiedTime(jar,timestamp); assertEquals(size,Files.size(jar));
            try (var loader=NeoModClassLoader.createClassLoader(List.of(jar),getClass().getClassLoader(),null)) {
                assertEquals("B",new String(loader.getResourceAsStream("version.txt").readAllBytes()));
            }
        } finally { System.setProperty("user.dir",previous); }
    }
    Path mixinJar(String config,String toml) throws Exception {
        Path jar=dir.resolve("mixin.jar");
        try (var out=new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml")); out.write(toml.getBytes()); out.closeEntry();
            if (config != null) {
                out.putNextEntry(new JarEntry("fixture.mixins.json")); out.write(config.getBytes()); out.closeEntry();
                out.putNextEntry(new JarEntry("fixture/TestMixin.class")); out.write(classBytes("fixture/TestMixin")); out.closeEntry();
            }
        }
        return jar;
    }
    @Test void mixinRequiredAndInjectorContractArePreserved() throws Exception {
        String toml=ArtifactRegressionTest.metadata("fixture","1") + "[[mixins]]\nconfig=\"fixture.mixins.json\"\n";
        Path path=mixinJar("{\"required\":true,\"package\":\"fixture\",\"mixins\":[\"TestMixin\"],\"injectors\":{\"defaultRequire\":2}}",toml);
        try (var jar=new JarFile(path.toFile())) {
            var payload=NeoMixinExtractor.extract(jar,toml,s -> {});
            var json=com.google.gson.JsonParser.parseString(payload.mixinConfigs().get("fixture.mixins.json")).getAsJsonObject();
            assertTrue(json.get("required").getAsBoolean()); assertEquals(2,json.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
        }
    }
    @Test void missingDeclaredMixinCannotGeneratePlaceholder() throws Exception {
        String toml=ArtifactRegressionTest.metadata("fixture","1") + "[[mixins]]\nconfig=\"fixture.mixins.json\"\n";
        Path path=mixinJar(null,toml); byte[] original=Files.readAllBytes(path);
        assertFalse(NeoForgeModPatcher.patchIfNeeded(path)); assertArrayEquals(original,Files.readAllBytes(path));
        assertFalse(Files.exists(path.resolveSibling("mixin.jar.neoforge-original")));
        assertThrows(IllegalStateException.class, () -> NeoForgeModPatcher.patchAll(dir));
    }
}
