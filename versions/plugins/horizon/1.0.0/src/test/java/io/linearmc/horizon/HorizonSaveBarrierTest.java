package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.linear.io.PlayerFileDurability;
import net.linear.save.SaveOffLease;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Horizon leg of the save barrier: the two new keys ship with the fork
 * defaults and reach the core, and the host-optional {@code save-all}
 * redirect ({@code require = 0}, so {@code MixinDescriptorsTest} skips it)
 * has its call site on every Paper dev bundle this suite runs against.
 */
public class HorizonSaveBarrierTest {

    @AfterEach
    void reset() {
        SaveOffLease.resetForTests();
        PlayerFileDurability.setEnabled(true);
    }

    @Test
    void shippedDefaultsMatchTheForks(@TempDir Path dir) throws Exception {
        PlayerFileDurability.setEnabled(false);
        SaveOffLease.setMaxDurationSeconds(7);
        LinearConfigLoader.push(LinearConfigLoader.loadOrCreate(dir));
        assertEquals(120L, SaveOffLease.maxDurationSeconds(), "save-off-max-duration default 120 s");
        assertTrue(PlayerFileDurability.enabled(), "durable-player-data default true");
    }

    @Test
    void configuredValuesReachTheCore() throws Exception {
        final YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(String.join("\n",
            "region-format:",
            "  linear:",
            "    save-off-max-duration: 0",
            "    durable-player-data: false",
            "    coupled-save:",
            "      enabled: false",
            ""));
        LinearConfigLoader.push(cfg);
        assertEquals(0L, SaveOffLease.maxDurationSeconds(), "0 disables the lease");
        assertFalse(PlayerFileDurability.enabled());

        cfg.loadFromString(String.join("\n",
            "region-format:",
            "  linear:",
            "    save-off-max-duration: -4",
            ""));
        LinearConfigLoader.push(cfg);
        assertEquals(120L, SaveOffLease.maxDurationSeconds(), "negative falls back to 120");
    }

    @Test
    void paperSaveAllCallsSaveEverythingOnThisBundle() throws Exception {
        final AtomicInteger sites = new AtomicInteger();
        try (InputStream in = getClass().getClassLoader()
            .getResourceAsStream("net/minecraft/server/commands/SaveAllCommand.class")) {
            assertTrue(in != null, "SaveAllCommand present in the dev bundle");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                        final String desc, final String sig, final String[] exceptions) {
                        if (!name.equals("saveAll")) {
                            return null;
                        }
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(final int op, final String owner, final String mName,
                                final String mDesc, final boolean itf) {
                                if (owner.equals("net/minecraft/server/MinecraftServer")
                                    && mName.equals("saveEverything") && mDesc.equals("(ZZZ)Z")) {
                                    sites.incrementAndGet();
                                }
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        assertEquals(1, sites.get(), "Paper save-all has exactly one saveEverything call to redirect");
    }

    /** Methods/constructors of {@code internalName} on this bundle, as name+descriptor. */
    private java.util.Set<String> members(final String internalName) throws Exception {
        final java.util.Set<String> out = new java.util.HashSet<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertTrue(in != null, internalName + " present in the dev bundle");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                        final String desc, final String sig, final String[] exceptions) {
                        out.add(name + desc);
                        return null;
                    }
                }, org.objectweb.asm.ClassReader.SKIP_CODE);
        }
        return out;
    }

    /** Calls from {@code owner.method} (any descriptor) to {@code callOwner.callName}. */
    private int calls(final String owner, final String method, final String callOwner, final String callName)
        throws Exception {
        final AtomicInteger sites = new AtomicInteger();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(owner + ".class")) {
            assertTrue(in != null, owner + " present in the dev bundle");
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(
                new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(final int access, final String name,
                        final String desc, final String sig, final String[] exceptions) {
                        if (!name.equals(method)) {
                            return null;
                        }
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(final int op, final String o, final String n,
                                final String d, final boolean itf) {
                                if (o.equals(callOwner) && n.equals(callName)) {
                                    sites.incrementAndGet();
                                }
                            }
                        };
                    }
                }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        }
        return sites.get();
    }

    /**
     * Region-host save-all (parity gap closed): the HEAD injector's method and
     * the {@code CommandsMixin} constructor exist with the exact descriptors
     * the mixins name (same signature on Folia/CanvasMC, checked against their
     * sources in MIXIN-CONTRACT.md); on this Paper bundle the region API does
     * not resolve and the Paper redirect path stays in charge.
     */
    @Test
    void regionHostSaveAllSitesExistAndPaperKeepsTheRedirect() throws Exception {
        assertTrue(members("net/minecraft/server/commands/SaveAllCommand")
            .contains("saveAll(Lnet/minecraft/commands/CommandSourceStack;Z)I"), "saveAll HEAD site");
        assertTrue(members("net/minecraft/commands/Commands").contains(
            "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;Z)V"),
            "Paper Brigadier Commands constructor (CommandsMixin)");
        assertFalse(HorizonRegionSaveAll.applies(), "Paper bundle: no region threads");
        assertTrue(HorizonRegionSaveAll.resolveError() != null, "Paper bundle: no RegionizedServer");
    }

    /**
     * Anvil under the coupled-save and save-all barrier: the forced storage
     * flush reaches {@code RegionFile.flush}, which must fsync the region
     * file ({@code FileChannel.force}); a region file evicted before the
     * barrier is forced by {@code close}. That is what lets docs/limitations.md
     * extend the ordering guarantee to {@code .mca} files.
     */
    @Test
    void anvilRegionFlushAndCloseForceTheFile() throws Exception {
        final String regionFile = "net/minecraft/world/level/chunk/storage/RegionFile";
        assertTrue(calls(regionFile, "flush", "java/nio/channels/FileChannel", "force") >= 1,
            "RegionFile.flush fsyncs");
        assertTrue(calls(regionFile, "close", "java/nio/channels/FileChannel", "force") >= 1,
            "RegionFile.close fsyncs");
    }
}
