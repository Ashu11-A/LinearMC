package net.linear;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Protection alert wording follows the caller: halt text only when the caller halts. */
public class ProtectionAlertTest {

    private final List<String> messages = new java.util.ArrayList<>();
    private Handler capture;

    @Before
    public void attach() {
        final Logger logger = Logger.getLogger(LinearRegionConverter.class.getName());
        this.capture = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(this.capture);
    }

    @After
    public void detach() {
        Logger.getLogger(LinearRegionConverter.class.getName()).removeHandler(this.capture);
        messages.clear();
    }

    private static LinearRegionConverter.ConversionSummary summary() {
        return new LinearRegionConverter.ConversionSummary(1, 1, 0, 1, List.of(
            new LinearRegionConverter.FileResult(
                Path.of("r.0.0.mca"), Path.of("r.0.0.linear"),
                LinearRegionConverter.Status.FAILED, "boom")));
    }

    @Test
    public void forwardHaltWordingFollowsFlag() {
        LinearRegionConverter.logProtectionAlert(Path.of("region"), summary(), true);
        assertTrue(messages.get(messages.size() - 1).contains("Server halts for inspection"));
        LinearRegionConverter.logProtectionAlert(Path.of("region"), summary(), false);
        final String live = messages.get(messages.size() - 1);
        assertFalse(live.contains("Server halts for inspection"));
        assertTrue(live.contains("keeps running"));
    }

    @Test
    public void reverseHaltWordingFollowsFlag() {
        LinearRegionConverter.logReverseProtectionAlert(Path.of("region"), summary(), true);
        assertTrue(messages.get(messages.size() - 1).contains("Server halts for inspection"));
        LinearRegionConverter.logReverseProtectionAlert(Path.of("region"), summary(), false);
        final String live = messages.get(messages.size() - 1);
        assertFalse(live.contains("Server halts for inspection"));
        assertTrue(live.contains("keeps running"));
    }
}
