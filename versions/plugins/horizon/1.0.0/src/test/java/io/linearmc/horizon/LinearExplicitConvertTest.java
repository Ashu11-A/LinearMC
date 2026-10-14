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
import net.linear.LinearRegionConverter;
import net.linear.command.ConvertJob;
import net.linear.command.LinearAction;
import net.linear.command.parse.LinearCommandParser;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.Test;

/**
 * Explicit any-to-any dispatch contract: {@code --from/--to/--grid} parses,
 * completes, and routes {@code --execute} to the quiesce-gated worker (never
 * the submit-and-hang executor path), and {@code /linear help} states the
 * Pumpkin rule. Server-free (proxy Bukkit surface, like
 * {@link LinearBrigadierTest}).
 */
public class LinearExplicitConvertTest {

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
            Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getName":
                            return "TestServer";
                        case "getWorld":
                            return null;
                        case "getWorlds":
                            return List.of();
                        case "getPlayerExact":
                            return null;
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            return null;
                    }
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
    public void explicitFlagsParseToExplicitAction() throws Exception {
        LinearCommandParser parser = new LinearCommandParser();
        LinearAction.ExplicitConvertAction full = (LinearAction.ExplicitConvertAction) parser.parse(
            new String[]{"convert", "world", "--from", "pumpkin", "--to", "v1",
                "--grid", "2", "--level", "3", "--threads", "2", "--execute"});
        assertEquals("world", full.world());
        assertEquals(LinearRegionConverter.ConvertFormat.PUMPKIN, full.from());
        assertEquals(LinearRegionConverter.ConvertFormat.V1, full.to());
        assertEquals(2, full.grid());
        assertEquals(3, full.level());
        assertEquals(2, full.threads());
        assertFalse(full.dryRun());

        // --from defaults to auto (detect); dry-run stays the default.
        LinearAction.ExplicitConvertAction planned = (LinearAction.ExplicitConvertAction) parser.parse(
            new String[]{"convert", "world", "--to", "blinear"});
        assertEquals(LinearRegionConverter.ConvertFormat.AUTO, planned.from());
        assertEquals(LinearRegionConverter.ConvertFormat.B_LINEAR, planned.to());
        assertEquals(0, planned.grid());
        assertTrue(planned.dryRun());
    }

    @Test
    public void tabCompletionOffersExplicitFlagsAndValues() {
        Harness h = new Harness();
        Collection<String> flags = h.command.suggest(h.stack, new String[]{"convert", "world", "--"});
        assertTrue(flags.contains("--to-linear"), "legacy flags stay: " + flags);
        assertTrue(flags.contains("--execute"), "legacy flags stay: " + flags);
        assertTrue(flags.contains("--from"), "explicit flags surface: " + flags);
        assertTrue(flags.contains("--to"), "explicit flags surface: " + flags);
        assertTrue(flags.contains("--grid"), "explicit flags surface: " + flags);

        Collection<String> fromValues =
            h.command.suggest(h.stack, new String[]{"convert", "world", "--from", ""});
        assertTrue(fromValues.contains("pumpkin"), "from values: " + fromValues);
        assertTrue(fromValues.contains("auto"), "from values: " + fromValues);

        Collection<String> gridValues =
            h.command.suggest(h.stack, new String[]{"convert", "world", "--grid", ""});
        assertTrue(gridValues.contains("2"), "grid values: " + gridValues);

        // Value slots never gain flag names.
        Collection<String> levelValues =
            h.command.suggest(h.stack, new String[]{"convert", "world", "--level", ""});
        assertFalse(levelValues.contains("--from"), "value slot stays clean: " + levelValues);
    }

    @Test
    public void helpStatesPumpkinRule() {
        Harness h = new Harness();
        h.command.execute(h.stack, new String[]{});
        assertTrue(h.lines.contains(LinearCommand.PUMPKIN_RULE_LINE),
            "help must state the Pumpkin rule: " + h.lines);
    }

    @Test
    public void explicitDryRunPlansThroughExecutor() {
        Harness h = new Harness();
        int before = LinearCommand.queue().list().size();
        h.command.execute(h.stack,
            new String[]{"convert", "dryrun-explicit-horizon", "--from", "pumpkin", "--to", "v1"});
        assertEquals(before + 1, LinearCommand.queue().list().size());
        ConvertJob job = LinearCommand.queue().list().get(
            LinearCommand.queue().list().size() - 1);
        assertTrue(job.isExplicit());
        assertEquals(LinearRegionConverter.ConvertFormat.PUMPKIN, job.fromFormat());
        assertEquals(LinearRegionConverter.ConvertFormat.V1, job.toFormat());
        assertTrue(job.dryRun());
        assertTrue(h.lines.get(0).contains("Practice run, nothing changed"),
            "dry-run plans only: " + h.lines);
    }

    @Test
    public void explicitExecuteUnknownWorldNeverQueuesForever() {
        Harness h = new Harness();
        int before = LinearCommand.queue().list().size();
        h.command.execute(h.stack, new String[]{"convert", "no-such-world-explicit", "--from",
            "pumpkin", "--to", "v1", "--execute"});
        assertTrue(h.lines.get(0).contains("Unknown world 'no-such-world-explicit'"),
            "unknown world is refused up front: " + h.lines);
        assertEquals(before, LinearCommand.queue().list().size(),
            "no submit-and-hang job may queue for an unresolvable world");
    }
}
