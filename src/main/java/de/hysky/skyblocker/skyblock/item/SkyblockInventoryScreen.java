package de.hysky.skyblocker.skyblock.item;

import com.mojang.serialization.Codec;
import de.hysky.skyblocker.SkyblockerMod;
import de.hysky.skyblocker.events.SkyblockEvents;
import de.hysky.skyblocker.mixins.accessors.SlotAccessor;
import de.hysky.skyblocker.utils.ItemUtils;
import de.hysky.skyblocker.utils.Utils;
import de.hysky.skyblocker.utils.scheduler.MessageScheduler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.nbt.visitor.StringNbtWriter;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Opened here {@code de.hysky.skyblocker.mixins.MinecraftClientMixin#skyblocker$skyblockInventoryScreen}
 * <br>
 * Book button is moved here {@code de.hysky.skyblocker.mixins.InventoryScreenMixin#skyblocker}
 */
public class SkyblockInventoryScreen extends InventoryScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Equipment");

    public static final ItemStack[] equipment = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    private static final Codec<ItemStack[]> CODEC = ItemUtils.EMPTY_ALLOWING_ITEMSTACK_CODEC.listOf(4,4)
            .xmap(itemStacks -> itemStacks.toArray(ItemStack[]::new), List::of).fieldOf("items").codec();

    private static final Identifier SLOT_TEXTURE = Identifier.ofVanilla("container/slot");
    private static final Identifier EMPTY_SLOT = Identifier.of(SkyblockerMod.NAMESPACE, "equipment/empty_icon");
    private static final Path FOLDER = SkyblockerMod.CONFIG_DIR.resolve("equipment");

    private final Slot[] equipmentSlots = new Slot[4];

    private static void save(String profileId) {
        try {
            Files.createDirectories(FOLDER);
        } catch (IOException e) {
            LOGGER.error("[Skyblocker] Failed to create folder for equipment!", e);
        }
        Path resolve = FOLDER.resolve(profileId + ".nbt");

        try (BufferedWriter writer = Files.newBufferedWriter(resolve)) {
            writer.write(new StringNbtWriter().apply(CODEC.encodeStart(NbtOps.INSTANCE, equipment).getOrThrow()));
        } catch (Exception e) {
            LOGGER.error("[Skyblocker] Failed to save Equipment data", e);
        }
    }

    private static void load(String profileId) {
        Path resolve = FOLDER.resolve(profileId + ".nbt");
        CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = Files.newBufferedReader(resolve)) {
                return CODEC.parse(
                                NbtOps.INSTANCE, StringNbtReader.parse(reader.lines().collect(Collectors.joining())))
                        .getOrThrow();
            } catch (NoSuchFileException ignored) {
            } catch (Exception e) {
                LOGGER.error("[Skyblocker] Failed to load Equipment data", e);

            }
            return null;
            // Schedule on main thread to avoid any async weirdness
        }).thenAccept(itemStacks -> MinecraftClient.getInstance().execute(() -> System.arraycopy(itemStacks, 0, equipment, 0, Math.min(itemStacks.length, 4))));

    }

    public static void initEquipment() {

        SkyblockEvents.PROFILE_CHANGE.register(((prevProfileId, profileId) -> {
            if (!prevProfileId.isEmpty()) save(prevProfileId);
            load(profileId);
        }));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client1 -> {
            String profileId = Utils.getProfileId();
            if (!profileId.isBlank()) {
                save(profileId);
            }
        });
    }

    public SkyblockInventoryScreen(PlayerEntity player) {
        super(player);
        SimpleInventory inventory = new SimpleInventory(equipment);

        Slot slot = handler.slots.get(45);
        ((SlotAccessor) slot).setX(slot.x + 21);
        for (int i = 0; i < 4; i++) {
            equipmentSlots[i] = new EquipmentSlot(inventory, i, 77, 8 + i * 18);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (Slot equipmentSlot : equipmentSlots) {
            if (isPointWithinBounds(equipmentSlot.x, equipmentSlot.y, 16, 16, mouseX, mouseY)) {
                MessageScheduler.INSTANCE.sendMessageAfterCooldown("/equipment");
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean canDrawTooltips = false;

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.translate(this.x, this.y, 0.0F);
        for (Slot equipmentSlot : equipmentSlots) {
            drawSlot(context, equipmentSlot);
            if (isPointWithinBounds(equipmentSlot.x, equipmentSlot.y, 16, 16, mouseX, mouseY)) drawSlotHighlight(context, equipmentSlot.x, equipmentSlot.y, 0);
        }
        matrices.pop();
        canDrawTooltips = true;
        drawMouseoverTooltip(context, mouseX, mouseY);
        canDrawTooltips = false;
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int x, int y) {
        if (!canDrawTooltips) return;
        super.drawMouseoverTooltip(context, x, y);
        if (!handler.getCursorStack().isEmpty()) return;
        for (Slot equipmentSlot : equipmentSlots) {
            if (isPointWithinBounds(equipmentSlot.x, equipmentSlot.y, 16, 16, x, y) && equipmentSlot.hasStack()) {
                ItemStack itemStack = equipmentSlot.getStack();
                context.drawTooltip(this.textRenderer, this.getTooltipFromItem(itemStack), itemStack.getTooltipData(), x, y);
            }
        }
    }

    @Override
    public void removed() {
        super.removed();
        // put the handler back how it was, the handler is the same while the player is alive/in the same world
        Slot slot = handler.slots.get(45);
        ((SlotAccessor) slot).setX(slot.x - 21);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        super.drawBackground(context, delta, mouseX, mouseY);
        for (int i = 0; i < 4; i++) {
            context.drawGuiTexture(SLOT_TEXTURE, x + 76 + (i == 3 ? 21 : 0), y + 7 + i * 18, 18, 18);
        }
    }

    @Override
    protected void drawSlot(DrawContext context, Slot slot) {
        super.drawSlot(context, slot);
        if (slot instanceof EquipmentSlot && !slot.hasStack()) {
            context.drawGuiTexture(EMPTY_SLOT, slot.x, slot.y, 16, 16);
        }
    }

    private static class EquipmentSlot extends Slot {

        public EquipmentSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) {
            return false;
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }
    }
}
