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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public class Autotorch implements ModInitializer {
    public static final String MOD_ID = "auto-torch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Data Attachment for persistent player state
    public static final AttachmentType<Boolean> AUTO_TORCH_ENABLED = AttachmentRegistry.create(
            Objects.requireNonNull(Identifier.tryBuild(MOD_ID, "enabled")),
            builder -> builder
                    .initializer(() -> true)  // Default: enabled
                    .persistent(Codec.BOOL)   // Persists across restarts
    );

    private static int tickCounter = 0;
    // Controls whether detailed debug logs are printed to the server console.
    // Can be toggled in-game using the '/autotorch debug' command (requires permission level 2).
    // Default is 'false' to keep the console clean during normal operation.
    private static boolean debugLoggingEnabled = false;

    private static boolean isAutoTorchEnabled(ServerPlayer player) {
        return player.getAttachedOrElse(AUTO_TORCH_ENABLED, true);
    }

    private static void setAutoTorchEnabled(ServerPlayer player, boolean enabled) {
        player.setAttached(AUTO_TORCH_ENABLED, enabled);
    }

    private static int setAutoTorchState(CommandContext<CommandSourceStack> context, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        setAutoTorchEnabled(player, enabled);
        String status = enabled ? "enabled" : "disabled";
        ChatFormatting color = enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        context.getSource().sendSuccess(() -> Component.literal("AutoTorch (player) has been " + status + ".").withStyle(color), false);
        return 1;
    }

    private static int toggleAutoTorchState(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean newState = !isAutoTorchEnabled(player);
        setAutoTorchEnabled(player, newState);
        String status = newState ? "enabled" : "disabled";
        ChatFormatting color = newState ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        context.getSource().sendSuccess(() -> Component.literal("AutoTorch (player) has been " + status + ".").withStyle(color), false);
        return 1;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("{} has been initialised!", MOD_ID);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("autotorch").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            boolean isEnabled = isAutoTorchEnabled(player);
                            String status = isEnabled ? "enabled" : "disabled";
                            ChatFormatting color = isEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                            context.getSource().sendSuccess(() -> Component.literal("AutoTorch (player) is currently " + status + ".").withStyle(color), false);
                            return 1;
                        })
                        .then(Commands.literal("on").executes(context -> setAutoTorchState(context, true)))
                        .then(Commands.literal("off").executes(context -> setAutoTorchState(context, false)))
                        .then(Commands.literal("toggle").executes(Autotorch::toggleAutoTorchState))
                        .then(Commands.literal("debug").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(context -> {
                            debugLoggingEnabled = !debugLoggingEnabled;
                            String status = debugLoggingEnabled ? "enabled" : "disabled";
                            ChatFormatting color = debugLoggingEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                            context.getSource().sendSuccess(() -> Component.literal("AutoTorch debug logging " + status + ".").withStyle(color), false);
                            return 1;
                        }))
        ));

        // Show player their AutoTorch status when they join the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LOGGER.info("Player {} joined the server, checking AutoTorch status...", handler.getPlayer().getName().getString());
            ServerPlayer player = handler.getPlayer();
            boolean isEnabled = isAutoTorchEnabled(player);
            String status = isEnabled ? "enabled" : "disabled";
            ChatFormatting color = isEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
            player.sendSystemMessage(Component.literal("AutoTorch (player) is " + status + ".").withStyle(color), false);
        });

        // TODO: Move this to scheduler
        // Query this every 20 ticks (1 second)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            if (tickCounter >= 20) {
                tickCounter = 0;

                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!isAutoTorchEnabled(player)) {
                        continue;
                    }

                    // This mod should only work in the overworld. Other dimensions don't care about light levels
                    // do NOT try-catch this, it WILL freeze the server
                    Level level = player.level();
                    if (level.dimension() != Level.OVERWORLD) {
                        continue;
                    }

                    // Only run this logic if the player is in survival mode (not adventure, spectator, or creative).
                    // isBlockBreakingRestricted() returns true for adventure and spectator modes, which we want to skip.
                    // isCreative() returns true for creative mode, which we also want to skip.
                    if (player.gameMode().isBlockPlacingRestricted() || player.isCreative()) {
                        continue;
                    }

                    // do NOT try-catch this either
                    Level world = player.level();

                    // TODO: Also we should consider detecting if the player is digging down?

                    BlockPos playerPos = player.blockPosition();
                    int blockLightLevel = world.getLightEmission(playerPos);
                    if (debugLoggingEnabled) {
                        LOGGER.info("[DEBUG] Player {} is at position {} in light level {}", player.getName().toString(), playerPos, blockLightLevel);
                    }

                    // Now lets place the torch if the light level is at 0
                    if (blockLightLevel == 0) {
                        if (debugLoggingEnabled) {
                            LOGGER.info("[DEBUG] Player {} is in darkness at position {}, attempting to place torch", player.getName().getString(), playerPos);
                        }

                        Inventory inventory = player.getInventory();
                        int torchSlot = -1;

                        // Find a torch
                        if (inventory.contains(net.minecraft.world.item.Items.TORCH.getDefaultInstance())) {
                            torchSlot = inventory.findSlotMatchingItem(net.minecraft.world.item.Items.TORCH.getDefaultInstance());
                        }

                        // If no torch found, skip this player
                        if (torchSlot == -1) {
                            if (debugLoggingEnabled) {
                                LOGGER.warn("Player {} has no torches!", player.getName().getString());
                            }
                            continue;
                        }

                        // Get placement position (block under player)
                        BlockPos placePos = playerPos.below();

                        // Only place if below block is solid and above is air
                        // TODO: Find alternative for isSolid(), is deprecated.
                        if (!world.getBlockState(placePos).isSolid()) {
                            if (debugLoggingEnabled) {
                                LOGGER.warn("Cannot place torch: no solid block below at {}", placePos);
                            }
                            continue;
                        }

                        BlockPos torchPos = placePos.above();

                        if (world.isEmptyBlock(torchPos)) {
                            if (debugLoggingEnabled) {
                                LOGGER.info("[DEBUG] Placed torch at {} and removed one from inventory", torchPos);
                            }
                            world.setBlock(torchPos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
                            inventory.removeItem(torchSlot, 1);
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