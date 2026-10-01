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
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.Test;

/**
 * Brigadier façade contract: {@link LinearCommand} routes
 * {@code execute}/{@code suggest} through the existing
 * Bukkit surface with no server running.
 */
public class LinearBrigadierTest {

    private static final class Harness {
        final List<String> lines = new ArrayList<>();
        final CommandSender sender;
        final CommandSourceStack stack;
        final LinearCommand command;

        Harness() {
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
                        return true;
                    case "isOp":
                        return true;
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
            // Server-free plugin instance: a real loader (backed by a
            // dynamic Server proxy) satisfies the JavaPlugin constructor's
            // loader dereference. getServer() returns the proxy, whose
            // getWorlds() null falls back to empty via the adapter's
            // RuntimeException catch — no server ever boots.
            Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return "TestServer";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
            JavaPlugin plugin = new JavaPlugin(
                new JavaPluginLoader(server),
                new PluginDescriptionFile(
                    "LinearMC", "1.0.0", "io.linearmc.horizon.LinearPlugin"),
                new java.io.File("build/tmp-test-plugin"),
                new java.io.File("build/tmp-test-plugin.jar")) {
            };
            command = new LinearCommand(plugin);
        }
    }

    @Test
    public void permissionStringExact() {
        assertEquals("linear.command.linear", new Harness().command.permission());
    }

    @Test
    public void executeRoutesToParserAndExecutor() {
        Harness h = new Harness();
        h.command.execute(h.stack, new String[]{});
        assertFalse(h.lines.isEmpty(), "help should send at least one line");

        Harness bad = new Harness();
        bad.command.execute(bad.stack, new String[]{"bogus"});
        assertFalse(bad.lines.isEmpty(), "invalid usage should send the parser message");

        Harness queue = new Harness();
        queue.command.execute(queue.stack, new String[]{"queue", "list"});
        assertFalse(queue.lines.isEmpty(), "queue list should send at least one line");
    }

    @Test
    public void suggestReturnsSubcommands() {
        Harness h = new Harness();
        Collection<String> all = h.command.suggest(h.stack, new String[]{""});
        assertTrue(all.contains("stats"));
        assertTrue(all.contains("convert"));
        assertTrue(all.contains("queue"));
        assertTrue(all.contains("help"));

        Collection<String> filtered = h.command.suggest(h.stack, new String[]{"c"});
        assertTrue(filtered.contains("convert"));
        assertFalse(filtered.contains("stats"));
    }
}
