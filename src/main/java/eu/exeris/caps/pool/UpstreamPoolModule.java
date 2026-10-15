package eu.exeris.caps.pool;

import eu.exeris.caps.pool.api.UpstreamPoolService;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;

/**
 * Capability module declaration for {@code exeris-caps-upstream-pool}.
 *
 * <p>Provides the {@link UpstreamPoolService} contract for gateway routing,
 * multi-host load balancing, and health checking.
 *
 * @since 0.1.0
 */
@CapabilityModule
@Provides(service = UpstreamPoolService.class, version = "0.1.0")
public final class UpstreamPoolModule {

    private UpstreamPoolModule() {
        // Declaration carrier only — never constructed.
    }
}
