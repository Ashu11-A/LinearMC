package io.linearmc.horizon;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.linear.ChunkKey;
import net.minecraft.world.level.ChunkPos;

/**
 * Version-proof {@code ChunkPos} coordinate extraction (multi-version axis #1).
 *
 * <p>Mojang changed {@code ChunkPos} from {@code public final int} fields
 * (1.21.x and earlier) to a record with {@code x()}/{@code z()} accessors
 * (26.x), and the long helpers changed too ({@code toLong()} is gone on
 * 26.x; {@code pack()} does not exist on 1.21.x) — so NO single direct call
 * links on both lines (observed: every Linear write on 1.21.11 died with
 * {@code NoSuchMethodError: ChunkPos.x()}).
 *
 * <p>This probe resolves ONE accessor pair per JVM boot (method first for
 * the record line, field fallback for the fields line) and converts through
 * {@link ChunkKey#of(int, int)}, whose layout is core-owned and identical on
 * every version. If neither shape exists the class fails to initialize with
 * a {@link LinkageError}: fail-LOUD, never a silent wrong-coordinate write
 * (coordinates must never be guessed — a wrong key corrupts chunk addressing).
 */
public final class ChunkPosCoords {

    private static final MethodHandle GET_X = resolve("x");
    private static final MethodHandle GET_Z = resolve("z");

    private ChunkPosCoords() {
    }

    /** Packs {@code pos} into the core {@link ChunkKey} layout. */
    public static long toKey(final ChunkPos pos) {
        try {
            return ChunkKey.of((int) GET_X.invokeExact(pos), (int) GET_Z.invokeExact(pos));
        } catch (final Throwable failed) {
            throw new LinkageError("LinearMC: ChunkPos coordinate access failed", failed);
        }
    }

    private static MethodHandle resolve(final String name) {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            // Record line (26.x): accessor method x()/z().
            return lookup.findVirtual(ChunkPos.class, name, MethodType.methodType(int.class));
        } catch (final NoSuchMethodException | IllegalAccessException notRecord) {
            try {
                // Fields line (1.21.x): public final int x/z; read via getter handle.
                return lookup.unreflectGetter(ChunkPos.class.getField(name));
            } catch (final NoSuchFieldException | IllegalAccessException neither) {
                throw new LinkageError(
                    "LinearMC: ChunkPos has neither method " + name + "() nor field " + name
                        + " — unsupported mappings line", neither);
            }
        }
    }
}
