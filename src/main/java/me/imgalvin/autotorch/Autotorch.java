package me.imgalvin.autotorch;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

public class Autotorch implements ModInitializer {
    public static final String MOD_ID = "auto-torch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Data Attachment for persistent player state
    public static final AttachmentType<Boolean> AUTO_TORCH_ENABLED = AttachmentRegistry.create(
            Identifier.of(MOD_ID, "enabled"),
            builder -> builder
                    .initializer(() -> true)  // Default: enabled
                    .persistent(Codec.BOOL)   // Persists across restarts
    );

    private static int tickCounter = 0;
    // Controls whether detailed debug logs are printed to the server console.
    // Can be toggled in-game using the '/autotorch debug' command (requires permission level 2).
    // Default is 'false' to keep the console clean during normal operation.
    private static boolean debugLoggingEnabled = false;

    private static boolean isAutoTorchEnabled(ServerPlayerEntity player) {
        return player.getAttachedOrElse(AUTO_TORCH_ENABLED, true);
    }

    private static void setAutoTorchEnabled(ServerPlayerEntity player, boolean enabled) {
        player.setAttached(AUTO_TORCH_ENABLED, enabled);
    }

    private static int setAutoTorchState(CommandContext<ServerCommandSource> context, boolean enabled) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        setAutoTorchEnabled(player, enabled);
        String status = enabled ? "enabled" : "disabled";
        Formatting color = enabled ? Formatting.GREEN : Formatting.YELLOW;
        context.getSource().sendFeedback(() -> Text.literal("AutoTorch (player) has been " + status + ".").formatted(color), false);
        return 1;
    }

    private static int toggleAutoTorchState(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        boolean newState = !isAutoTorchEnabled(player);
        setAutoTorchEnabled(player, newState);
        String status = newState ? "enabled" : "disabled";
        Formatting color = newState ? Formatting.GREEN : Formatting.YELLOW;
        context.getSource().sendFeedback(() -> Text.literal("AutoTorch (player) has been " + status + ".").formatted(color), false);
        return 1;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("{} has been initialised!", MOD_ID);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("autotorch").executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            boolean isEnabled = isAutoTorchEnabled(player);
                            String status = isEnabled ? "enabled" : "disabled";
                            Formatting color = isEnabled ? Formatting.GREEN : Formatting.YELLOW;
                            context.getSource().sendFeedback(() -> Text.literal("AutoTorch (player) is currently " + status + ".").formatted(color), false);
                            return 1;
                        })
                        .then(literal("on").executes(context -> setAutoTorchState(context, true)))
                        .then(literal("off").executes(context -> setAutoTorchState(context, false)))
                        .then(literal("toggle").executes(Autotorch::toggleAutoTorchState))
                        .then(literal("debug").requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK)).executes(context -> {
                            debugLoggingEnabled = !debugLoggingEnabled;
                            String status = debugLoggingEnabled ? "enabled" : "disabled";
                            Formatting color = debugLoggingEnabled ? Formatting.GREEN : Formatting.YELLOW;
                            context.getSource().sendFeedback(() -> Text.literal("AutoTorch debug logging " + status + ".").formatted(color), false);
                            return 1;
                        }))
        ));

        // Show player their AutoTorch status when they join the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            boolean isEnabled = isAutoTorchEnabled(player);
            String status = isEnabled ? "enabled" : "disabled";
            Formatting color = isEnabled ? Formatting.GREEN : Formatting.YELLOW;
            player.sendMessage(Text.literal("AutoTorch (player) is " + status + ".").formatted(color), false);
        });

        // Query this every 20 ticks (1 second)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            if (tickCounter >= 20) {
                tickCounter = 0;

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (!isAutoTorchEnabled(player)) {
                        continue;
                    }

                    // This mod should only work in the overworld. Other dimensions don't care about light levels
                    World world = player.getEntityWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        continue;
                    }

                    // Also only do this if the player is in survival mode
                    // isBlockBreakingRestricted() returns true for adventure and spectator modes
                    // isCreative() returns true for creative mode
                    if (player.interactionManager.getGameMode().isBlockBreakingRestricted() || player.isCreative()) {
                        continue;
                    }

                    // TODO: Also we should consider detecting if the player is digging down?

                    BlockPos playerPos = player.getBlockPos();
                    int blockLightLevel = world.getLightLevel(playerPos);
                    if (debugLoggingEnabled) {
                        LOGGER.info("[DEBUG] Player {} is at position {} in light level {}", player.getName().toString(), playerPos, blockLightLevel);
                    }

                    // Now lets place the torch if the light level is at 0
                    if (blockLightLevel == 0) {
                        if (debugLoggingEnabled) {
                            LOGGER.info("[DEBUG] Player {} is in darkness at position {}, attempting to place torch", player.getName().getString(), playerPos);
                        }

                        PlayerInventory inventory = player.getInventory();
                        int torchSlot = -1;

                        // Find a torch
                        for (int i = 0; i < inventory.size(); i++) {
                            if (inventory.getStack(i).getItem() == Items.TORCH) {
                                torchSlot = i;
                                break;
                            }
                        }

                        // If no torch found, skip this player
                        if (torchSlot == -1) {
                            if (debugLoggingEnabled) {
                                LOGGER.warn("Player {} has no torches!", player.getName().getString());
                            }
                            continue;
                        }

                        // Get placement position (block under player)
                        BlockPos placePos = playerPos.down();

                        // Only place if below block is solid and above is air
                        if (!world.getBlockState(placePos).isSolidBlock(world, placePos)) {
                            if (debugLoggingEnabled) {
                                LOGGER.warn("Cannot place torch: no solid block below at {}", placePos);
                            }
                            continue;
                        }

                        BlockPos torchPos = placePos.up();

                        if (world.isAir(torchPos)) {
                            if (debugLoggingEnabled) {
                                LOGGER.info("[DEBUG] Placed torch at {} and removed one from inventory", torchPos);
                            }
                            world.setBlockState(torchPos, Blocks.TORCH.getDefaultState());
                            inventory.removeStack(torchSlot, 1);
                        } else {
                            if (debugLoggingEnabled) {
                                LOGGER.warn("Cannot place torch: block not air at {}", torchPos);
                            }
                        }
                    }
                }
            }
        });
    }
}