package me.imgalvin.autotorch;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Autotorch implements ModInitializer {
	public static final String MOD_ID = "auto-torch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static int tickCounter = 0;

	@Override
	public void onInitialize() {
		LOGGER.info("{} has been initialised!", MOD_ID);

        // Query this every 20 ticks (1 second)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            if (tickCounter >= 20) {
                tickCounter = 0;

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    // This mod should only work in the overworld. Other dimensions don't care about light levels
                    World world = player.getEntityWorld();
                    if (world.getRegistryKey() != World.OVERWORLD) {
                        continue;
                    }

                    // Also only do this if the player is in survival or adventure mode
                    if (!player.interactionManager.getGameMode().isSurvivalLike()) {
                        continue;
                    }

                    // TODO: Also we should consider detecting if the player is digging down?

                    BlockPos playerPos = player.getBlockPos();
                    int blockLightLevel = world.getLightLevel(playerPos);
                    LOGGER.info("Player {} is at position {} in light level {}", player.getName().toString(), playerPos, blockLightLevel);

                    // Now lets place the torch if the light level is at 0
                    if (blockLightLevel == 0) {
                        LOGGER.info("Player {} is in darkness at position {}, attempting to place torch", player.getName().getString(), playerPos);

                        PlayerInventory inventory = player.getInventory();
                        int torchSlot = -1;

                        // Find a torch
                        for (int i = 0; i < inventory.size(); i++) {
                            if (inventory.getStack(i).getItem() == Items.TORCH) {
                                torchSlot = i;
                                break;
                            }
                        }

                        // If no torch found, stop here
                        if (torchSlot == -1) {
                            LOGGER.info("Player {} has no torches!", player.getName().getString());
                            return; // or continue; if inside tick loop
                        }

                        // Get placement position (block under player)
                        BlockPos placePos = playerPos.down();

                        // Only place if below block is solid and above is air
                        if (!world.getBlockState(placePos).isSolidBlock(world, placePos)) {
                            LOGGER.info("Cannot place torch — no solid block below at {}", placePos);
                            return;
                        }

                        BlockPos torchPos = placePos.up();

                        if (world.isAir(torchPos)) {
                            world.setBlockState(torchPos, Blocks.TORCH.getDefaultState());
                            inventory.removeStack(torchSlot, 1);
                            LOGGER.info("Placed torch at {} and removed one from inventory", torchPos);
                        } else {
                            LOGGER.info("Cannot place torch — block not air at {}", torchPos);
                        }
                    }
                }
            }
        });
	}
}