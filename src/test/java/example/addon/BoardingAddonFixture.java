package example.addon;

import com.mahghuuuls.mountcollection.api.BoardingSupport;
import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.api.ProviderFailure;
import com.mahghuuuls.mountcollection.api.ProviderResult;
import com.mahghuuuls.mountcollection.api.RegistrationProfile;
import com.mahghuuuls.mountcollection.api.SeatEnvelope;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Compile fixture: optional boarding needs no imports from Mount Collection internals. */
public final class BoardingAddonFixture implements MountProvider, BoardingSupport {
    @Override public ResourceLocation getProviderId() { return new ResourceLocation("example:boarding"); }
    @Override public boolean supports(Entity entity) { return false; }
    @Override public ProviderResult<RegistrationProfile> validateRegistration(Entity entity, UUID owner) {
        return ProviderResult.failure(ProviderFailure.UNSUPPORTED);
    }
    @Override public ProviderResult<SeatEnvelope> describeBoarding(Entity mount, UUID rider) {
        // A real provider must first validate its exact native type and boarding rules.
        return ProviderResult.failure(ProviderFailure.UNSUPPORTED);
    }
}
