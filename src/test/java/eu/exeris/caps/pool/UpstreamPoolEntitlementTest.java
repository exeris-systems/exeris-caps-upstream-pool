package eu.exeris.caps.pool;

import eu.exeris.kernel.spi.contract.EntitlementClass;
import eu.exeris.kernel.spi.contract.EntitlementRequirement;
import eu.exeris.kernel.tck.contract.AbstractEntitlementRequirementTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("exeris-caps-upstream-pool: EntitlementRequirement")
class UpstreamPoolEntitlementTest extends AbstractEntitlementRequirementTck {

    @Override
    protected Class<? extends EntitlementRequirement> providerClass() {
        return UpstreamPoolEntitlement.class;
    }

    @Test
    @DisplayName("ServiceLoader discovers the provider from the cap's own service registration")
    void serviceLoaderDiscoversTheProvider() {
        List<Class<? extends EntitlementRequirement>> types = ServiceLoader.load(EntitlementRequirement.class)
                .stream()
                .map(ServiceLoader.Provider::type)
                .toList();

        assertThat(types).contains(UpstreamPoolEntitlement.class);
    }

    @Test
    @DisplayName("It requires exactly this cap's own capability identifier")
    void requiresOnlyItsOwnCapability() {
        assertThat(new UpstreamPoolEntitlement().requiredCapabilities()).containsExactly("exeris-caps-upstream-pool");
        assertThat(UpstreamPoolEntitlement.CAPABILITY_ID).isEqualTo("exeris-caps-upstream-pool");
    }

    @Test
    @DisplayName("It declares the COMMERCIAL entitlement class, stable across calls")
    void entitlementClassIsCommercialAndStable() {
        UpstreamPoolEntitlement entitlement = new UpstreamPoolEntitlement();
        EntitlementClass first = entitlement.entitlementClass();

        assertThat(first).isEqualTo(EntitlementClass.COMMERCIAL);
        assertThat(entitlement.entitlementClass()).isSameAs(first);
        assertThat(new UpstreamPoolEntitlement().entitlementClass()).isEqualTo(EntitlementClass.COMMERCIAL);
    }
}
