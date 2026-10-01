package net.linear;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.papermc.paper.configuration.GlobalConfiguration;

import org.junit.jupiter.api.Test;

/**
 * Inert global Linear keys.
 *
 * <p>NMS-light (no server, no datapacks): instantiates the config
 * {@code RegionFormat.Linear} inner class directly and exercises the
 * {@code @PostProcess} clamps. Verifies defaults preserve today's
 * behaviour (serial flush, no workers, LDM off, log off) and that no
 * {@code @Constraints.Min} annotation guards the new keys (post
 * project convention: fallbacks stay reachable via PostProcess).
 */
public class LinearGlobalConfigTest {

    private static GlobalConfiguration.RegionFormat.Linear newLinear() throws Exception {
        GlobalConfiguration outer = new GlobalConfiguration();
        GlobalConfiguration.RegionFormat regionFormat = outer.new RegionFormat();
        return regionFormat.new Linear();
    }

    private static void runPostProcess(Object linear) throws Exception {
        Method m = linear.getClass().getDeclaredMethod("postProcess");
        m.setAccessible(true);
        m.invoke(linear);
    }

    @Test
    public void defaultsPreserveTodaysBehavior() throws Exception {
        GlobalConfiguration.RegionFormat.Linear linear = newLinear();
        assertEquals(10, linear.flushFrequency, "flush-frequency default 10");
        assertEquals(1, linear.flushMaxThreads, "flush-max-threads default 1 (serial)");
        assertEquals(0, linear.compressionWorkers, "compression-workers default 0 (inert)");
        assertEquals(0, linear.longDistanceMatching, "long-distance-matching default 0 (off)");
        assertFalse(linear.logFlushBatches, "log-flush-batches default false (warn-only)");
    }

    @Test
    public void postProcessClampsPreserveBehavior() throws Exception {
        GlobalConfiguration.RegionFormat.Linear linear = newLinear();
        linear.flushFrequency = 0;
        linear.flushMaxThreads = -100;
        linear.compressionWorkers = -5;
        linear.longDistanceMatching = -1;
        linear.logFlushBatches = true; // boolean: no clamp, survives
        runPostProcess(linear);
        assertEquals(10, linear.flushFrequency, "flush-frequency <1 falls back to 10");
        assertTrue(linear.flushMaxThreads >= 1, "flush-max-threads clamps to >=1");
        assertEquals(0, linear.compressionWorkers, "compression-workers <0 falls back to 0");
        assertEquals(0, linear.longDistanceMatching, "long-distance-matching <0 falls back to 0");
        assertTrue(linear.logFlushBatches, "boolean passes through (no clamp)");
    }

    @Test
    public void serialValuesStaySerial() throws Exception {
        GlobalConfiguration.RegionFormat.Linear linear = newLinear();
        linear.flushMaxThreads = 1;
        linear.compressionWorkers = 0;
        linear.longDistanceMatching = 0;
        runPostProcess(linear);
        assertEquals(1, linear.flushMaxThreads, "<=1 stays serial");
        assertEquals(0, linear.compressionWorkers, "0 stays inert");
        assertEquals(0, linear.longDistanceMatching, "0 stays off");
    }

    @Test
    public void noMinConstraintOnLinearKeys() throws Exception {
        // Convention: no @Constraints.Min on Linear keys; validation lives
        // in @PostProcess so fallbacks are reachable.
        for (String name : new String[]{
            "flushFrequency", "flushMaxThreads",
            "compressionWorkers", "longDistanceMatching", "logFlushBatches"}) {
            Field f = GlobalConfiguration.RegionFormat.Linear.class.getDeclaredField(name);
            for (var ann : f.getAnnotations()) {
                assertFalse(ann.annotationType().getName().contains("Constraints"),
                    name + " must not carry @Constraints (found " + ann + ")");
            }
        }
    }
}
