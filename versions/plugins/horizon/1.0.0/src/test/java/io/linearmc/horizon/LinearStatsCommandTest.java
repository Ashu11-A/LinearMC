package io.linearmc.horizon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.linear.command.LinearPermissions;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.Test;

/**
 * Contract for the standalone {@code /linearstats} command: strict
 * {@code linear.command.linearstats} permission, core-owned panel, and
 * world-name tab completion.
 */
public class LinearStatsCommandTest {

    private static final class Harness {
        final List<String> lines = new ArrayList<>();
        final CommandSender sender;
        final CommandSourceStack stack;
        final LinearStatsCommand command;
        final JavaPlugin plugin;

        Harness(boolean permitted) {
            InvocationHandler senderHandler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sendMessage":
                        if (args != null) {
                            for (Object a : args) {
                                if (a instanceof String s) {
                                    lines.add(s);
                                } else if (a instanceof String[] arr) {
                                    lines.addAll(List.of(arr));
                                }
                            }
                        }
                        return null;
                    case "hasPermission":
                        return permitted;
                    case "isOp":
                        return permitted;
                    case "getName":
                        return "tester";
                    case "isPermissionSet":
                        return true;
                    default:
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) {
                            return false;
                        }
                        return null;
                }
            };
            sender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CommandSender.class}, senderHandler);
            stack = (CommandSourceStack) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{CommandSourceStack.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getSender")) {
                        return sender;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
            Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return "TestServer";
                    }
                    if (method.getName().equals("getWorlds")) {
                        return List.of();
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
            plugin = new JavaPlugin(
                new JavaPluginLoader(server),
                new PluginDescriptionFile(
                    "LinearMC", "1.0.0", "io.linearmc.horizon.LinearPlugin"),
                new java.io.File("build/tmp-test-plugin"),
                new java.io.File("build/tmp-test-plugin.jar")) {
            };
            command = new LinearStatsCommand(plugin);
        }
    }

    @Test
    public void permissionStringExact() {
        assertEquals("linear.command.linearstats", new Harness(true).command.permission());
    }

    @Test
    public void executeSendsPanelWhenPermitted() {
        Harness h = new Harness(true);
        h.command.execute(h.stack, new String[]{});
        assertFalse(h.lines.isEmpty(), "stats should send at least one line");
        String first = h.lines.get(0);
        assertTrue(first.contains("no Linear folders tracked")
            || first.contains("Linear stats"),
            "unexpected first line: " + first);
    }

    @Test
    public void executeWithFilterStillResponds() {
        Harness h = new Harness(true);
        h.command.execute(h.stack, new String[]{"world"});
        assertFalse(h.lines.isEmpty(), "filtered stats should send at least one line");
    }

    @Test
    public void deniedWithoutStatsPermission() {
        Harness h = new Harness(false);
        h.command.execute(h.stack, new String[]{});
        assertEquals(LinearPermissions.denied("linearstats"), h.lines.get(0));
    }

    @Test
    public void suggestEmptyWorldsAndExtraArgs() {
        Harness h = new Harness(true);
        Collection<String> none = h.command.suggest(h.stack, new String[]{""});
        assertTrue(none.isEmpty());
        assertTrue(h.command.suggest(h.stack, new String[]{"a", "b"}).isEmpty());
        assertTrue(h.command.onTabComplete(h.sender, null, "linearstats",
            new String[]{"a", "b"}).isEmpty());
    }
}
