package com.mahghuuuls.mountcollection.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mahghuuuls.mountcollection.integration.inhibited.InhibitedStatus;
import com.mahghuuuls.mountcollection.api.MountCharacteristics;
import com.mahghuuuls.mountcollection.api.MountTrait;
import com.mahghuuuls.mountcollection.api.PlacementProfile;
import com.mahghuuuls.mountcollection.lifecycle.ContextualOutcome;
import com.mahghuuuls.mountcollection.policy.ConfiguredFilter;
import com.mahghuuuls.mountcollection.policy.FilterMode;
import com.mahghuuuls.mountcollection.policy.RecallPolicy;
import com.mahghuuuls.mountcollection.policy.ValidatedMountConfig;
import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

final class RecallPolicyTest {

    @Test
    void denialsFollowApprovedPrecedence() {
        RecallPolicy policy = new RecallPolicy();
        ValidatedMountConfig denied = config(FilterMode.WHITELIST, FilterMode.WHITELIST);
        MountRecord blocked = record(MountCondition.INTEGRITY_BLOCKED);

        assertEquals(ContextualOutcome.Status.INTEGRITY_CONFLICT,
                policy.evaluate(
                        blocked, false, true, 0, InhibitedStatus.AFFECTED, 20L, denied));
        assertEquals(ContextualOutcome.Status.PROVIDER_UNAVAILABLE,
                policy.evaluate(
                        record(MountCondition.PROVIDER_UNAVAILABLE), false, true, 0,
                        InhibitedStatus.AFFECTED, 20L, denied));
        assertEquals(ContextualOutcome.Status.PASSENGER_PRESENT,
                policy.evaluate(
                        record(MountCondition.LIVING), true, true, 0,
                        InhibitedStatus.AFFECTED, 20L, denied));
        assertEquals(ContextualOutcome.Status.SUMMON_DISALLOWED,
                policy.evaluate(
                        record(MountCondition.LIVING), true, false, 0,
                        InhibitedStatus.AFFECTED, 20L, denied));
    }

    @Test
    void inhibitedAndCooldownApplyAfterFiltersAndZeroCooldownAllowsRecall() {
        RecallPolicy policy = new RecallPolicy();
        ValidatedMountConfig allowed = config(FilterMode.BLACKLIST, FilterMode.BLACKLIST);
        MountRecord record = record(MountCondition.LIVING);

        assertEquals(ContextualOutcome.Status.INHIBITED,
                policy.evaluate(
                        record, true, false, 0, InhibitedStatus.AFFECTED, 20L, allowed));
        assertEquals(ContextualOutcome.Status.COOLDOWN,
                policy.evaluate(
                        record, true, false, 0, InhibitedStatus.UNAFFECTED, 20L, allowed));
        assertNull(policy.evaluate(
                record, true, false, 0, InhibitedStatus.UNAVAILABLE, 0L, allowed));
    }

    @Test
    void flyingOverrideDeniesBeforeFiltersAndCooldownWithoutChangingGroundMounts() {
        RecallPolicy policy = new RecallPolicy();
        ValidatedMountConfig denied = config(
                FilterMode.BLACKLIST, FilterMode.BLACKLIST, true);
        MountRecord flying = record(
                MountCondition.LIVING,
                new MountCharacteristics(
                        PlacementProfile.WATER, EnumSet.of(MountTrait.FLYING)));

        assertEquals(ContextualOutcome.Status.SUMMON_DISALLOWED,
                policy.evaluate(flying, true, false, 0,
                        InhibitedStatus.AFFECTED, 20L, denied));
        assertNull(policy.evaluate(record(MountCondition.LIVING), true, false, 0,
                InhibitedStatus.UNAFFECTED, 0L, denied));
    }

    @Test
    void existingRecoveryIgnoresRecoveryEnablementButHonorsCurrentRecallPolicy() {
        RecallPolicy policy = new RecallPolicy();
        MountRecord ready = recoveryRecord(MountCharacteristics.solidGround());
        ValidatedMountConfig recoveryDisabled = new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                200L, 4, 16, false, false, 0L, true, false);

        assertNull(policy.evaluateRecovery(
                ready, true, 0, InhibitedStatus.UNAFFECTED, 0L, recoveryDisabled));
        assertEquals(ContextualOutcome.Status.PROVIDER_UNAVAILABLE,
                policy.evaluateRecovery(
                        ready, false, 0, InhibitedStatus.UNAFFECTED, 0L,
                        recoveryDisabled));
        assertEquals(ContextualOutcome.Status.SUMMON_DISALLOWED,
                policy.evaluateRecovery(
                        ready, true, 0, InhibitedStatus.UNAFFECTED, 0L,
                        config(FilterMode.WHITELIST, FilterMode.BLACKLIST)));

        MountRecord flying = recoveryRecord(new MountCharacteristics(
                PlacementProfile.SOLID_GROUND, EnumSet.of(MountTrait.FLYING)));
        assertEquals(ContextualOutcome.Status.SUMMON_DISALLOWED,
                policy.evaluateRecovery(
                        flying, true, 0, InhibitedStatus.UNAFFECTED, 0L,
                        config(FilterMode.BLACKLIST, FilterMode.BLACKLIST, true)));
    }

    private static ValidatedMountConfig config(FilterMode summoning, FilterMode dimensions) {
        return config(summoning, dimensions, false);
    }

    private static ValidatedMountConfig config(
            FilterMode summoning, FilterMode dimensions, boolean flyingDisabled) {
        return new ValidatedMountConfig(
                new ConfiguredFilter<>(FilterMode.BLACKLIST, Collections.emptySet()),
                new ConfiguredFilter<>(summoning, Collections.emptySet()),
                new ConfiguredFilter<>(dimensions, Collections.emptySet()),
                200L, 4, 16, flyingDisabled, true, 6000L, true, false);
    }

    private static MountRecord record(MountCondition condition) {
        return record(condition, MountCharacteristics.solidGround());
    }

    private static MountRecord record(
            MountCondition condition, MountCharacteristics characteristics) {
        return new MountRecord(
                MountId.create(), UUID.randomUUID(),
                new ResourceLocation("mountcollection:vanilla"),
                new ResourceLocation("minecraft:horse"), "minecraft:horse", 1, 1L,
                UUID.randomUUID(), new LastKnownEvidence(0, 0.0D, 64.0D, 0.0D),
                condition, condition == MountCondition.LIVING ? null : "test", characteristics, 0,
                new NBTTagCompound(), null);
    }

    private static MountRecord recoveryRecord(MountCharacteristics characteristics) {
        UUID sourceId = UUID.randomUUID();
        LastKnownEvidence evidence = new LastKnownEvidence(0, 0.0D, 64.0D, 0.0D);
        RecoveryState recovery = new RecoveryState(
                sourceId, evidence, new com.mahghuuuls.mountcollection.api.ProviderPayload(
                        1, new NBTTagCompound()), 0L, 0L);
        return new MountRecord(
                MountId.create(), UUID.randomUUID(),
                new ResourceLocation("mountcollection:vanilla"),
                new ResourceLocation("minecraft:horse"), "minecraft:horse", 1, 1L,
                null, null, MountCondition.READY_FOR_RECALL, null, characteristics, 0,
                new NBTTagCompound(), recovery, null);
    }
}
