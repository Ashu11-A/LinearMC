package io.linearmc.horizon;

import io.canvasmc.horizon.HorizonLoader;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jspecify.annotations.NonNull;

/**
 * Earliest plugin hook (before the server initializes): pushes
 * {@code config.yml} into the core file layer so the pre-plugin startup
 * conversion ({@code DedicatedServerMixin}) and the first world storages use
 * the configured format, per-world overrides and level, like the forks.
 */
@SuppressWarnings("UnstableApiUsage")
public class LinearBootstrapper implements PluginBootstrap {

    @Override
    public void bootstrap(@NonNull BootstrapContext context) {
        HorizonLoader.LOGGER.info("LinearMC Horizon plugin BOOTSTRAP init");
        try {
            LinearConfigLoader.push(LinearConfigLoader.loadOrCreate(context.getDataDirectory()));
        } catch (Exception e) {
            // Fail open: without file values the core resolves ANVIL (or the
            // -Dlinearmc.* sysprops), and onEnable retries the push.
            HorizonLoader.LOGGER.warn("LinearMC: could not read config.yml at bootstrap: " + e);
        }
    }
}
