package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.lifecycle.FatalTransferSafetyException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeBoardingTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void successUsesNativeTwoSidedAttachmentWithoutCleanup() {
        Fixture f = new Fixture(); f.environment.safe = true;
        assertTrue(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertSame(f.mount, f.rider.getRidingEntity()); assertTrue(f.mount.isPassenger(f.rider));
        assertEquals(0, f.environment.returns);
    }

    @Test void nativeMountVetoIsNotOverridden() {
        Fixture f = new Fixture(); f.rider.mountVeto = true;
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertNull(f.rider.getRidingEntity()); assertFalse(f.mount.isPassenger(f.rider));
        assertEquals(1, f.environment.returns); assertEquals(1, f.environment.syncs);
    }

    @Test void unchangedNativeVetoWithUnsafeReturnIsFatal() {
        Fixture f = new Fixture(); f.rider.mountVeto = true; f.environment.returnSafe = false;
        assertThrows(FatalTransferSafetyException.class, () -> NativeBoarding.board(f.rider, f.mount, f.environment));
        assertEquals(1, f.environment.returns);
    }

    @Test void rejectedPoseAndVetoedDismountStillRemoveBothNativeReferences() {
        Fixture f = new Fixture(); f.rider.cleanupVeto = true;
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertEquals(1, f.rider.dismounts);
        assertNull(f.rider.getRidingEntity()); assertFalse(f.mount.isPassenger(f.rider));
        assertEquals(1, f.environment.returns); assertEquals(1, f.environment.syncs);
    }

    @Test void throwingCleanupBeforeAndAfterNativeDetachIsContained() {
        for (boolean after : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.rider.cleanupThrows = true; f.rider.throwAfter = after;
            assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
            assertNull(f.rider.getRidingEntity()); assertFalse(f.mount.isPassenger(f.rider));
            assertEquals(1, f.environment.syncs);
        }
    }

    @Test void partialAttachmentReturningFalseOrThrowingIsUndone() {
        for (boolean throwing : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.rider.partial = true; f.rider.throwAfter = throwing;
            assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
            assertNull(f.rider.getRidingEntity()); assertFalse(f.mount.isPassenger(f.rider));
        }
    }

    @Test void otherPassengersAddedDuringCallbackArePreserved() {
        Fixture f = new Fixture(); EntityBoat other = new EntityBoat(f.world);
        f.environment.after = () -> assertTrue(other.startRiding(f.mount, false));
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertEquals(1, f.mount.getPassengers().size()); assertSame(other, f.mount.getPassengers().get(0));
        assertSame(f.mount, other.getRidingEntity()); assertNull(f.rider.getRidingEntity());
    }

    @Test void nominalSuccessWithEitherOneSidedLinkIsRejectedAndCleaned() {
        for (OneSided link : OneSided.values()) {
            Fixture f = new Fixture(); f.environment.safe = true; f.rider.oneSided = link;
            assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment), link.name());
            assertCleaned(f, link);
        }
    }

    @Test void eitherLinkLostDuringPostCheckCannotBecomeAccepted() {
        for (OneSided link : OneSided.values()) {
            Fixture f = new Fixture(); f.environment.safe = true;
            f.environment.after = () -> leaveOneSide(f.rider, f.mount, link);
            assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment), link.name());
            assertCleaned(f, link);
        }
    }

    @Test void rejectedOneSidedLinkWithoutPositionChangeStillRequiresReturnAndSync() {
        for (OneSided link : OneSided.values()) {
            Fixture f = new Fixture(); f.rider.oneSided = link; f.rider.partial = true;
            double x = f.rider.posX, y = f.rider.posY, z = f.rider.posZ;
            assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment), link.name());
            assertEquals(x, f.rider.posX); assertEquals(y, f.rider.posY); assertEquals(z, f.rider.posZ);
            assertCleaned(f, link);
        }
    }

    private static void assertCleaned(Fixture f, OneSided link) {
        assertNull(f.rider.getRidingEntity(), link.name());
        assertFalse(f.mount.isPassenger(f.rider), link.name());
        assertEquals(1, f.environment.returns, link.name());
        assertEquals(1, f.environment.syncs, link.name());
    }

    private enum OneSided { RIDER_ONLY, MOUNT_ONLY }

    private static void leaveOneSide(Entity rider, Entity mount, OneSided link) {
        try {
            // Corrupt real native storage independently of production's access helper. Both
            // getters must expose the intended asymmetric state before testing its rejection.
            if (link == OneSided.RIDER_ONLY) {
                java.lang.reflect.Field field = Entity.class.getDeclaredField("riddenByEntities");
                field.setAccessible(true); ((java.util.List<?>) field.get(mount)).clear();
            } else {
                java.lang.reflect.Field field = Entity.class.getDeclaredField("ridingEntity");
                field.setAccessible(true); field.set(rider, null);
            }
            assertEquals(link == OneSided.RIDER_ONLY, rider.getRidingEntity() == mount);
            assertEquals(link == OneSided.MOUNT_ONLY, mount.isPassenger(rider));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    @Test void newVehicleEnteredByCallbackIsNotDetachedOrRepositioned() {
        Fixture f = new Fixture(); EntityBoat other = new EntityBoat(f.world);
        f.environment.after = () -> {
            f.rider.dismountRidingEntity(); f.rider.resetCooldown();
            assertTrue(f.rider.startRiding(other, false));
        };
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertSame(other, f.rider.getRidingEntity()); assertTrue(other.isPassenger(f.rider));
        assertFalse(f.mount.isPassenger(f.rider)); assertEquals(0, f.environment.returns);
    }

    @Test void unsafeReturnOrSynchronizationFailureIsFatalNotOrdinaryFeedback() {
        Fixture f = new Fixture(); f.environment.returnSafe = false;
        assertThrows(FatalTransferSafetyException.class, () -> NativeBoarding.board(f.rider, f.mount, f.environment));
        Fixture g = new Fixture(); g.environment.syncThrows = true;
        assertThrows(FatalTransferSafetyException.class, () -> NativeBoarding.board(g.rider, g.mount, g.environment));
    }

    @Test void failedPreflightAndExistingRidesAreUntouched() {
        Fixture f = new Fixture(); f.environment.ready = false;
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertEquals(0, f.rider.starts);
        f.environment.ready = true; EntityBoat existing = new EntityBoat(f.world);
        assertTrue(f.rider.startRiding(existing, false));
        assertFalse(NativeBoarding.board(f.rider, f.mount, f.environment));
        assertSame(existing, f.rider.getRidingEntity());
    }

    private static final class Fixture {
        final BoardingTestWorld world = new BoardingTestWorld();
        final Rider rider = new Rider(world);
        final EntityBoat mount = new EntityBoat(world);
        final Environment environment = new Environment();
    }
    private static final class Rider extends EntityBoat {
        boolean cleanupThrows, throwAfter, partial, cleanupVeto, mountVeto; int starts, dismounts;
        OneSided oneSided;
        Rider(BoardingTestWorld world) { super(world); }
        void resetCooldown() { rideCooldown = 0; }
        public boolean startRiding(Entity mount, boolean force) {
            assertFalse(force); starts++;
            if (mountVeto) { return false; }
            boolean result = super.startRiding(mount, force);
            if (oneSided != null) { assertTrue(result); leaveOneSide(this, mount, oneSided); }
            if (partial) { if (throwAfter) { throw new IllegalStateException("attachment"); } return false; }
            return result;
        }
        public void dismountRidingEntity() {
            dismounts++;
            // Model the API result of a Forge veto. Actual event transformation is a runtime gate.
            if (cleanupVeto) { return; }
            if (cleanupThrows && !throwAfter) { throw new IllegalStateException("cleanup"); }
            super.dismountRidingEntity();
            if (cleanupThrows) { throw new IllegalStateException("cleanup after"); }
        }
    }
    private static final class Environment implements NativeBoarding.Environment {
        boolean ready = true, safe, returnSafe = true, syncThrows;
        int returns, syncs; Runnable after = () -> {};
        public boolean ready() { return ready; }
        public boolean seatedSafely() { after.run(); return safe; }
        public boolean returnSafely() { returns++; return returnSafe; }
        public void synchronize() { syncs++; if (syncThrows) { throw new IllegalStateException("sync"); } }
    }
}
