package com.mahghuuuls.mountcollection.forge;

import com.mahghuuuls.mountcollection.persistence.LastKnownEvidence;
import com.mahghuuuls.mountcollection.persistence.MountId;
import com.mahghuuuls.mountcollection.persistence.MountRecord;
import com.mahghuuuls.mountcollection.persistence.MountRepository;
import com.mahghuuuls.mountcollection.persistence.TransferOperation;
import com.mahghuuuls.mountcollection.persistence.TransferPhase;
import com.mojang.authlib.GameProfile;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

final class MountCollectionCommand extends CommandBase {

    private final MountCollectionServices services;

    MountCollectionCommand(MountCollectionServices services) {
        this.services = services;
    }

    @Override
    public String getName() {
        return "mountcollection";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mountcollection inspect <player <name>|mount <mount-id>>"
                + " | /mountcollection dev <status|clear|fault <journal_ack|candidate_intent_fatal|source_intent_fatal|fence_post_drain>|pause <stable-phase>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] arguments)
            throws CommandException {
        if (arguments.length >= 2 && "dev".equals(arguments[0])) {
            executeDevelopment(sender, arguments);
            return;
        }
        if (arguments.length != 3 || !"inspect".equals(arguments[0])) {
            throw new WrongUsageException(getUsage(sender));
        }
        if (!services.getActiveConfig()
                .map(config -> config.isDetailedDiagnosticsEnabled())
                .orElse(false)) {
            sender.sendMessage(new TextComponentString(
                    "Mount Collection detailed diagnostics are disabled."));
            return;
        }
        MountRepository repository = services.getActiveRepository()
                .orElseThrow(() -> new CommandException("Mount Collection repository is unavailable."));
        if ("player".equals(arguments[1])) {
            inspectPlayer(server, sender, repository, arguments[2]);
            return;
        }
        if ("mount".equals(arguments[1])) {
            inspectMount(sender, repository, arguments[2]);
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    private void executeDevelopment(ICommandSender sender, String[] arguments)
            throws CommandException {
        TransferDevelopmentControls controls = services.getDevelopmentControls();
        if (!controls.isAvailable()) {
            throw new CommandException("Mount Collection development controls are unavailable.");
        }
        if (arguments.length == 2 && "status".equals(arguments[1])) {
            sendDevelopmentStatus(sender, controls);
            return;
        }
        if (arguments.length == 2 && "clear".equals(arguments[1])) {
            controls.clear();
            sendDevelopmentStatus(sender, controls);
            return;
        }
        if (arguments.length == 3 && "fault".equals(arguments[1])) {
            if ("candidate_intent_fatal".equals(arguments[2])) {
                armFatalBoundary(sender, controls, TransferPhase.CANDIDATE_SPAWN_INTENT);
                return;
            }
            if ("source_intent_fatal".equals(arguments[2])) {
                armFatalBoundary(sender, controls, TransferPhase.SOURCE_REMOVAL_INTENT);
                return;
            }
            TransferDevelopmentControls.Fault fault;
            if ("journal_ack".equals(arguments[2])) {
                fault = TransferDevelopmentControls.Fault.JOURNAL_ACKNOWLEDGEMENT;
            } else if ("fence_post_drain".equals(arguments[2])) {
                fault = TransferDevelopmentControls.Fault.PHYSICAL_FENCE_POST_DRAIN;
            } else {
                throw new CommandException("Unknown Mount Collection development fault.");
            }
            if (!controls.armFault(fault)) {
                throw new CommandException("Mount Collection development fault was not armed.");
            }
            sendDevelopmentStatus(sender, controls);
            return;
        }
        if (arguments.length == 3 && "pause".equals(arguments[1])) {
            com.mahghuuuls.mountcollection.persistence.TransferPhase phase;
            try {
                phase = com.mahghuuuls.mountcollection.persistence.TransferPhase.valueOf(
                        arguments[2].toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new CommandException("Unknown Mount Collection transfer phase.");
            }
            if (phase == com.mahghuuuls.mountcollection.persistence.TransferPhase.INTEGRITY_BLOCKED
                    || !controls.armPause(phase)) {
                throw new CommandException("Mount Collection transfer phase cannot be paused.");
            }
            sendDevelopmentStatus(sender, controls);
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    private static void armFatalBoundary(
            ICommandSender sender,
            TransferDevelopmentControls controls,
            TransferPhase phase) throws CommandException {
        if (!controls.armFatalAfterIntent(phase)) {
            throw new CommandException("Mount Collection fatal boundary was not armed.");
        }
        sendDevelopmentStatus(sender, controls);
    }

    private static void sendDevelopmentStatus(
            ICommandSender sender, TransferDevelopmentControls controls) {
        sender.sendMessage(new TextComponentString(
                "Mount Collection development controls " + controls.describe()));
    }

    private static void inspectPlayer(
            MinecraftServer server,
            ICommandSender sender,
            MountRepository repository,
            String playerName) throws CommandException {
        GameProfile profile = server.getPlayerProfileCache().getGameProfileForUsername(playerName);
        if (profile == null || profile.getId() == null) {
            throw new CommandException("Unknown player profile.");
        }
        MountRepository.CollectionInspection inspection = repository.inspectCollection(profile.getId());
        long deadline = repository.getRecallCooldownDeadline(profile.getId());
        long activeTick = repository.getActiveTick();
        long remaining = deadline > activeTick ? deadline - activeTick : 0L;
        sender.sendMessage(new TextComponentString(
                "Mount Collection player=" + bounded(profile.getName())
                        + " records=" + inspection.getCount()
                        + " selected=" + inspection.getSelectedMountId()
                                .map(MountId::toString)
                                .orElse("none")
                        + " playerRevision=" + inspection.getRevision()
                        + " storeRevision=" + repository.getStoreRevision()
                        + " activeTick=" + activeTick
                        + " cooldownDeadline=" + deadline
                        + " cooldownRemaining=" + remaining));
    }

    private static void inspectMount(
            ICommandSender sender, MountRepository repository, String rawMountId)
            throws CommandException {
        MountId mountId;
        try {
            mountId = MountId.parse(rawMountId);
        } catch (RuntimeException exception) {
            throw new CommandException("Invalid Mount ID.");
        }
        Optional<MountRecord> found = repository.find(mountId);
        if (!found.isPresent()) {
            throw new CommandException("Mount record not found.");
        }
        MountRecord record = found.get();
        LastKnownEvidence lastKnown = record.getLastKnown();
        Optional<TransferOperation> transfer = repository.findTransferByMount(mountId);
        sender.sendMessage(new TextComponentString(
                "Mount Collection mount=" + record.getMountId()
                        + " owner=" + record.getOwnerId()
                        + " provider=" + record.getProviderId()
                        + " entity=" + record.getEntityTypeId()
                        + " condition=" + record.getCondition()
                        + " ordinal=" + record.getFallbackOrdinal()
                        + " order=" + record.getRegistrationOrder()
                        + " physical=" + String.valueOf(record.getPhysicalEntityId())
                        + (lastKnown == null
                                ? " lastKnown=none"
                                : " lastKnown=" + lastKnown.getDimensionId()
                                        + ":" + lastKnown.getX()
                                        + "," + lastKnown.getY()
                                        + "," + lastKnown.getZ())
                        + (transfer.isPresent()
                                ? " transfer=" + transfer.get().getOperationId()
                                        + ":" + transfer.get().getPhase()
                                        + ":candidate=" + transfer.get().getCandidateEntityId()
                                        + (transfer.get().getIntegrityReason() == null
                                                ? ""
                                                : ":reason=" + bounded(
                                                        transfer.get().getIntegrityReason()))
                                : " transfer=none")));
    }

    @Override
    public List<String> getTabCompletions(
            MinecraftServer server,
            ICommandSender sender,
            String[] arguments,
            BlockPos targetPos) {
        if (arguments.length == 1) {
            return getListOfStringsMatchingLastWord(arguments, "inspect");
        }
        if (arguments.length == 2 && "inspect".equals(arguments[0])) {
            return getListOfStringsMatchingLastWord(arguments, "player", "mount");
        }
        if (arguments.length == 3 && "player".equals(arguments[1])) {
            return getListOfStringsMatchingLastWord(arguments, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }

    private static String bounded(String value) {
        String safe = String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }
}
