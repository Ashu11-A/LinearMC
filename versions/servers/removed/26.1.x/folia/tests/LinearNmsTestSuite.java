package net.linear;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

/**
 * L3-T1 (SUM): executes the NMS-light Linear unit tests inside the default
 * tasks.test run.
 *
 * <p>Background: folia-server restricts tasks.test to TestSuite classes only,
 * and every upstream suite selects just the org.bukkit, io.papermc.paper and
 * com.destroystokyo.paper packages. Without this suite, the net.minecraft and
 * net.linear tests compile but never execute. The tests themselves carry no
 * environment tag and need only the two-line NMS bootstrap in BeforeAll
 * (see L3-T1 BOOTSTRAP.md), so a plain suite with no tag filter is sufficient
 * and does not disturb the upstream suites.
 */
@Suite(failIfNoTests = false)
@SuiteDisplayName("Linear NMS-light unit tests (no server, no datapacks)")
@SelectPackages({
    "net.minecraft.world.level.chunk.storage",
    "net.minecraft.util.worldupdate",
    "net.linear"
})
public class LinearNmsTestSuite {
}
