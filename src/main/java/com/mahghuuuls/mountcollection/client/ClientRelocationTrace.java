package com.mahghuuuls.mountcollection.client;

import com.mahghuuuls.mountcollection.diagnostics.RelocationTraceLog;
import com.mahghuuuls.mountcollection.network.RelocationMessage;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

/** One explicitly armed native-client observation, confined to its connection/world/player. */
final class ClientRelocationTrace extends CommandBase {
    private static final long LIFETIME_NANOS = 45_000_000_000L;
    private final BooleanSupplier development;
    private final LongSupplier clock;
    private final Consumer<String> output;
    private RelocationTraceLog log;
    private UUID target;
    private Object connection, world, player;
    private long started;
    private int ticks;
    private RelocationMessage captured;

    ClientRelocationTrace() {
        this(net.minecraftforge.fml.relauncher.FMLLaunchHandler::isDeobfuscatedEnvironment,
                System::nanoTime, line -> org.apache.logging.log4j.LogManager.getLogger("mountcollection").info(line));
    }

    ClientRelocationTrace(BooleanSupplier development, LongSupplier clock, Consumer<String> output) {
        this.development = development;
        this.clock = clock;
        this.output = output;
    }

    @Override public String getName() { return "mcrelocationtrace"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public String getUsage(ICommandSender sender) { return "/mcrelocationtrace <entity-uuid|clear>"; }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!development.getAsBoolean()) { throw new CommandException("Development observation is unavailable."); }
        if (args.length != 1) { throw new CommandException(getUsage(sender)); }
        if ("clear".equals(args[0])) { clear(); }
        else {
            UUID uuid;
            try { uuid = UUID.fromString(args[0]); }
            catch (IllegalArgumentException invalid) { throw new CommandException("Expected an entity UUID."); }
            Minecraft client = Minecraft.getMinecraft();
            if (!arm(client.getConnection(), client.world, client.player, uuid)) {
                throw new CommandException("Join a world before arming an observation.");
            }
        }
        sender.sendMessage(new TextComponentString(target == null ? "Relocation observation cleared."
                : "Relocation observation armed for one matching packet (45 seconds)."));
    }

    boolean arm(Object connection, Object world, Object player, UUID target) {
        clear();
        if (!development.getAsBoolean() || connection == null || world == null || player == null || target == null) {
            return false;
        }
        this.connection = connection;
        this.world = world;
        this.player = player;
        this.target = target;
        started = clock.getAsLong();
        log = new RelocationTraceLog(development, output);
        return true;
    }

    void clear() {
        target = null; connection = null; world = null; player = null;
        captured = null; log = null; ticks = 0;
    }

    private boolean current(Minecraft client) {
        if (target == null) { return false; }
        if (!development.getAsBoolean() || clock.getAsLong() - started >= LIFETIME_NANOS
                || client.getConnection() != connection || client.world != world || client.player != player) {
            clear(); return false;
        }
        return true;
    }

    void before(Minecraft client, RelocationMessage message, Object source) {
        safely(() -> {
            if (!current(client) || source != connection || !target.equals(message.entity())) { return; }
            if (captured != null) {
                snapshot(client, "SUPERSEDED"); clear(); return;
            }
            captured = message;
            snapshot(client, "BEFORE");
        });
    }

    void outcome(Minecraft client, RelocationMessage message, String phase) {
        safely(() -> {
            if (!current(client) || captured != message) { return; }
            snapshot(client, phase);
            if (!"APPLIED".equals(phase)) { clear(); }
        });
    }

    void tick(Minecraft client) {
        safely(() -> {
            if (!current(client) || captured == null) { return; }
            ++ticks;
            Entity entity = client.world.getEntityByID(captured.entityId());
            if (entity == null || !target.equals(entity.getUniqueID())) {
                snapshot(client, "ENTITY_LOST"); clear(); return;
            }
            // Native boat interpolation lasts ten ticks; these finite checkpoints expose a sweep.
            if (ticks <= 12 || ticks == 40 || ticks == 160) { snapshot(client, "END_SAMPLE_" + ticks); }
            if (ticks >= 160) { clear(); }
        });
    }

    private void snapshot(Minecraft client, String phase) {
        log.record(() -> "side=CLIENT phase=" + phase + " " + RelocationTraceLog.packet(captured)
                + " worldTick=" + client.world.getTotalWorldTime()
                + " player={" + clientPose(client.player) + "} mount={"
                + clientPose(client.world.getEntityByID(captured.entityId())) + "}");
    }

    private static String clientPose(Entity entity) {
        if (entity == null) { return "absent"; }
        // Forge removes these interpolation coordinates on the physical server.
        return RelocationTraceLog.pose(entity) + " encoded="
                + entity.serverPosX + "," + entity.serverPosY + "," + entity.serverPosZ;
    }

    private void safely(Runnable observation) {
        try { observation.run(); }
        catch (RuntimeException | LinkageError unavailable) { clear(); }
    }
}
