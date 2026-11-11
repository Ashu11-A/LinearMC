package net.linear.command;

import static org.junit.Assert.assertEquals;

import java.util.Set;
import java.util.function.Predicate;
import net.linear.command.parse.LinearPermissions;
import net.linear.command.parse.LinearPermissions.Action;
import org.junit.Test;

/** The single permission rule every leg delegates to (docs/commands.md). */
public class LinearPermissionsTest {

    private static Predicate<String> holds(String... nodes) {
        final Set<String> set = Set.of(nodes);
        return set::contains;
    }

    private static void row(Predicate<String> has, boolean linearstats, boolean linearStats,
        boolean convert, boolean queue, boolean root) {
        assertEquals("linearstats", linearstats, LinearPermissions.allows(has, Action.LINEARSTATS));
        assertEquals("linear stats", linearStats, LinearPermissions.allows(has, Action.LINEAR_STATS));
        assertEquals("convert", convert, LinearPermissions.allows(has, Action.CONVERT));
        assertEquals("queue", queue, LinearPermissions.allows(has, Action.QUEUE));
        assertEquals("root", root, LinearPermissions.allows(has, Action.ROOT));
    }

    @Test
    public void baseGrantsTheWholeLinearFamilyButNotLinearstats() {
        row(holds(LinearPermissions.BASE), false, true, true, true, true);
    }

    @Test
    public void statsNodeGrantsBothStatsSurfacesOnly() {
        row(holds(LinearPermissions.STATS), true, true, false, false, true);
    }

    @Test
    public void convertNodeAloneReachesConvert() {
        row(holds(LinearPermissions.CONVERT), false, false, true, false, true);
    }

    @Test
    public void queueNodeAloneReachesQueue() {
        row(holds(LinearPermissions.QUEUE), false, false, false, true, true);
    }

    @Test
    public void noNodeNoAccess() {
        row(holds(), false, false, false, false, false);
    }
}
