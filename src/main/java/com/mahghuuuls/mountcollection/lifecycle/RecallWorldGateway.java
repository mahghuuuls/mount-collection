package com.mahghuuuls.mountcollection.lifecycle;

import com.mahghuuuls.mountcollection.api.MountProvider;
import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;

public interface RecallWorldGateway {

    LocateResult locate(EntityPlayerMP player, MountRecord record);

    boolean providerSupports(Source source, MountProvider provider);

    Optional<Destination> plan(
            EntityPlayerMP player,
            Source source,
            MountProvider provider,
            int normalRadius,
            int fallbackRadius);

    boolean commit(
            EntityPlayerMP player, Source source, Destination destination, MountProvider provider);

    final class LocateResult {
        public enum Status {
            FOUND,
            MISSING,
            INTEGRITY_CONFLICT
        }

        private final Status status;
        private final Source source;

        private LocateResult(Status status, Source source) {
            this.status = status;
            this.source = source;
        }

        public static LocateResult found(Source source) {
            return new LocateResult(Status.FOUND, Objects.requireNonNull(source, "source"));
        }

        public static LocateResult missing() {
            return new LocateResult(Status.MISSING, null);
        }

        public static LocateResult integrityConflict() {
            return new LocateResult(Status.INTEGRITY_CONFLICT, null);
        }

        public Status getStatus() {
            return status;
        }

        public Optional<Source> getSource() {
            return Optional.ofNullable(source);
        }
    }

    final class Source {
        private final UUID physicalEntityId;
        private final int dimensionId;
        private final boolean hasPassengers;
        private final Object implementationHandle;

        public Source(
                UUID physicalEntityId,
                int dimensionId,
                boolean hasPassengers,
                Object implementationHandle) {
            this.physicalEntityId = Objects.requireNonNull(physicalEntityId, "physicalEntityId");
            this.dimensionId = dimensionId;
            this.hasPassengers = hasPassengers;
            this.implementationHandle = implementationHandle;
        }

        public UUID getPhysicalEntityId() {
            return physicalEntityId;
        }

        public int getDimensionId() {
            return dimensionId;
        }

        public boolean hasPassengers() {
            return hasPassengers;
        }

        public Object getImplementationHandle() {
            return implementationHandle;
        }
    }

    final class Destination {
        private final LastKnownEvidence evidence;

        public Destination(LastKnownEvidence evidence) {
            this.evidence = Objects.requireNonNull(evidence, "evidence");
        }

        public LastKnownEvidence getEvidence() {
            return evidence;
        }
    }
}
