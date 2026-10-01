package net.linear;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/** zstd supplier seams owned by {@link LinearRegionFile} (codec). */
public class CodecSupplierTest {

    @After
    public void reset() {
        LinearRegionFile.linear$setCompressionWorkersSupplier(() -> 0);
        LinearRegionFile.linear$setLongDistanceMatchingSupplier(() -> 0);
    }

    @Test
    public void supplierDefaultsMatchOldNullConfigFallbacks() {
        assertEquals(0, LinearRegionFile.linear$resolveCompressionWorkers());
        assertEquals(0, LinearRegionFile.linear$resolveLongDistanceMatching());
    }

    @Test
    public void suppliersAreLive() {
        AtomicInteger workers = new AtomicInteger(2);
        AtomicInteger ldm = new AtomicInteger(12);
        LinearRegionFile.linear$setCompressionWorkersSupplier(workers::get);
        LinearRegionFile.linear$setLongDistanceMatchingSupplier(ldm::get);
        assertEquals(2, LinearRegionFile.linear$resolveCompressionWorkers());
        assertEquals(12, LinearRegionFile.linear$resolveLongDistanceMatching());
        workers.set(4);
        assertEquals(4, LinearRegionFile.linear$resolveCompressionWorkers());
    }

    @Test
    public void throwingSupplierFallsBack() {
        LinearRegionFile.linear$setCompressionWorkersSupplier(() -> {
            throw new RuntimeException("config gone");
        });
        LinearRegionFile.linear$setLongDistanceMatchingSupplier(() -> {
            throw new RuntimeException("config gone");
        });
        assertEquals(0, LinearRegionFile.linear$resolveCompressionWorkers());
        assertEquals(0, LinearRegionFile.linear$resolveLongDistanceMatching());
    }
}
