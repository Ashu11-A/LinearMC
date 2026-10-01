package net.linear;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Class warm-up: loads listed classes, tolerates missing ones. */
public class LinearWarmupTest {

    @Test
    public void loadsPresentClasses() {
        final LinearWarmup.Result result = LinearWarmup.touch(
            LinearWarmup.class,
            "net.linear.ChunkKey",
            "net.linear.RegionFileFormat");
        assertEquals(2, result.loaded());
        assertEquals(0, result.skipped());
        assertTrue(result.millis() >= 0);
    }

    @Test
    public void skipsMissingAndBlank() {
        final LinearWarmup.Result result = LinearWarmup.touch(
            LinearWarmup.class,
            "net.linear.NoSuchClass",
            null,
            "  ",
            "net.linear.ChunkKey");
        assertEquals(1, result.loaded());
        assertEquals(3, result.skipped());
    }

    @Test
    public void nullArrayIsEmpty() {
        final LinearWarmup.Result result =
            LinearWarmup.touch(LinearWarmup.class, (String[]) null);
        assertEquals(0, result.loaded());
        assertEquals(0, result.skipped());
    }
}
