package io.linearmc.horizon;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Server-free {@link JavaPlugin} instances for unit tests.
 *
 * <p>Constructing a real plugin ({@code new JavaPlugin(loader, ...)}) is not
 * version-portable: the 26.1/26.2 ctor requires a set {@code Bukkit.server}
 * ({@code Bukkit.getUnsafe()}) and a {@code PluginClassLoader}-loaded class,
 * while 26.3 builds the same state through the server-free
 * {@code InternalAPIBridge}. The commands under test only ever call
 * {@link JavaPlugin#getServer()} and {@link JavaPlugin#getLogger()}, which
 * are plain field reads on every bundle, so the stub allocates the shell
 * without running any ctor and injects exactly those two fields. A future
 * bundle that renames them fails loudly here instead of deep in Paper.
 */
final class TestPluginStubs {

    /** Concrete shell; never instantiated via a ctor (see {@link #stub}). */
    static final class StubPlugin extends JavaPlugin {
    }

    private TestPluginStubs() {
    }

    static JavaPlugin stub(Logger logger, Server server) {
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            JavaPlugin plugin =
                (JavaPlugin) unsafe.allocateInstance(StubPlugin.class);
            inject(plugin, "logger", logger);
            inject(plugin, "server", server);
            return plugin;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "test harness needs JavaPlugin.{logger,server} fields", e);
        }
    }

    private static void inject(JavaPlugin plugin, String field, Object value)
        throws ReflectiveOperationException {
        Field f = JavaPlugin.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(plugin, value);
    }
}
