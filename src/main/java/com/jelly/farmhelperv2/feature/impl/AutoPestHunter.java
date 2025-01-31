package com.jelly.farmhelperv2.feature.impl;

import cc.polyfrost.oneconfig.utils.Multithreading;
import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.BaritoneHandler;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.util.InventoryUtils;
import com.jelly.farmhelperv2.util.KeyBindUtils;
import com.jelly.farmhelperv2.util.LogUtils;
import com.jelly.farmhelperv2.util.PlayerUtils;
import com.jelly.farmhelperv2.util.helper.Clock;
import com.jelly.farmhelperv2.util.helper.RotationConfiguration;
import com.jelly.farmhelperv2.util.helper.Target;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public class AutoPestHunter implements IFeature {
    private final Minecraft mc = Minecraft.getMinecraft();
    private static AutoPestHunter instance;

    public static AutoPestHunter getInstance() {
        if (instance == null) {
            instance = new AutoPestHunter();
        }
        return instance;
    }

    private boolean enabled = false;
    @Setter
    public boolean manuallyStarted = false;
    @Getter
    private State state = State.NONE;
    @Getter
    private final Clock stuckClock = new Clock();
    @Getter
    private final Clock delayClock = new Clock();
    private BlockPos positionBeforeTp;

    private BlockPos deskPos() {
        return new BlockPos(FarmHelperConfig.pestHunterDeskX, FarmHelperConfig.pestHunterDeskY, FarmHelperConfig.pestHunterDeskZ);
    }

    private static final RotationHandler rotation = RotationHandler.getInstance();

    @Override
    public String getName() {
        return "Auto Pest Hunter";
    }

    @Override
    public boolean isRunning() {
        return enabled;
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void start() {
        if (enabled) return;
        if (!canEnableMacro(manuallyStarted)) return;
        if (!GameStateHandler.getInstance().inGarden()) return;
        if (!isToggled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        resetStatesAfterMacroDisabled();
        enabled = true;
        LogUtils.sendWarning("[Auto Pest Hunter] Starting...");
    }

    @Override
    public void stop() {
        enabled = false;
        LogUtils.sendWarning("[Auto Pest Hunter] Stopping...");
        KeyBindUtils.stopMovement();
        resetStatesAfterMacroDisabled();
        if (BaritoneHandler.isPathing())
            BaritoneHandler.stopPathing();
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        manuallyStarted = false;
        state = State.NONE;
        stuckClock.reset();
        delayClock.reset();
        positionBeforeTp = null;
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.autoPestHunter;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return false;
    }

    enum State {
        NONE,
        TELEPORT_TO_DESK,
        GO_TO_PHILLIP,
        CLICK_PHILLIP,
        WAIT_FOR_GUI,
        EMPTY_VACUUM,
        WAIT_FOR_VACUUM,
        GO_BACK,
    }

    public boolean canEnableMacro(boolean manually) {
        if (!isToggled()) return false;
        if (!GameStateHandler.getInstance().inGarden()) return false;
        if (!MacroHandler.getInstance().isMacroToggled() && !manually) return false;
        if (FarmHelperConfig.pestHunterDeskX == 0 && FarmHelperConfig.pestHunterDeskY == 0 && FarmHelperConfig.pestHunterDeskZ == 0) {
            LogUtils.sendError("[Auto Pest Hunter] The desk position is not set!");
            return false;
        }
        return true;
    }

    @SubscribeEvent
    public void onTickExecution(TickEvent.ClientTickEvent event) {
        if (!enabled) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!isToggled()) return;
        if (event.phase != TickEvent.Phase.START) return;
        if (!GameStateHandler.getInstance().inGarden()) return;
        if (!enabled) return;

        if (stuckClock.isScheduled() && stuckClock.passed()) {
            LogUtils.sendError("[Auto Pest Hunter] The player is stuck!");
            state = State.GO_BACK;
            return;
        }

        if (delayClock.isScheduled() && !delayClock.passed()) return;

        switch (state) {
            case NONE:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    break;
                }
                positionBeforeTp = mc.thePlayer.getPosition();
                state = State.TELEPORT_TO_DESK;
                mc.thePlayer.sendChatMessage("/tptoplot barn");
                delayClock.schedule((long) (1_000 + Math.random() * 500));
                stuckClock.schedule(10_000L);
                break;
            case TELEPORT_TO_DESK:
                if (mc.thePlayer.getPosition().equals(positionBeforeTp) || PlayerUtils.isPlayerSuffocating()) {
                    LogUtils.sendDebug("[Auto Pest Hunter] Waiting for teleportation...");
                    break;
                }
                if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
                    KeyBindUtils.setKeyBindState(mc.gameSettings.keyBindSneak, true);
                    LogUtils.sendDebug("[Auto Pest Hunter] The player is not on the ground, waiting...");
                    stuckClock.schedule(5_000L);
                    break;
                }
                KeyBindUtils.stopMovement();
                if (positionBeforeTp.distanceSq(mc.thePlayer.getPosition()) > 3) {
                    state = State.GO_TO_PHILLIP;
                    delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
                    stuckClock.schedule(30_000L);
                    break;
                }
            case GO_TO_PHILLIP:
                if (BaritoneHandler.hasFailed() && deskPos().distanceSq(mc.thePlayer.getPosition()) > 3) {
                    LogUtils.sendError("[Auto Pest Hunter] Baritone failed to reach the destination!");
                    state = State.GO_BACK;
                    break;
                }
                if (BaritoneHandler.isWalkingToGoalBlock()) break;
                if (mc.thePlayer.getDistanceSqToCenter(deskPos()) < 2) {
                    state = State.CLICK_PHILLIP;
                    delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
                    stuckClock.schedule(30_000L);
                    break;
                }
                BaritoneHandler.walkToBlockPos(deskPos());
                delayClock.schedule(1000L);
                break;
            case CLICK_PHILLIP:
                if (mc.thePlayer.getDistanceSqToCenter(deskPos()) > 3) {
                    state = State.GO_TO_PHILLIP;
                    delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
                    stuckClock.schedule(15_000L);
                    break;
                }
                Entity closest = mc.theWorld.getLoadedEntityList().
                        stream().
                        filter(entity ->
                                entity.hasCustomName() && entity.getCustomNameTag().contains(StringUtils.stripControlCodes("Phillip")))
                        .min(Comparator.comparingDouble(entity -> entity.getDistanceSqToCenter(mc.thePlayer.getPosition()))).orElse(null);
                if (closest == null) {
                    break;
                }
                rotation.easeTo(
                        new RotationConfiguration(
                                new Target(closest),
                                FarmHelperConfig.getRandomRotationTime(),
                                () -> {
                                    KeyBindUtils.leftClick();
                                    state = State.WAIT_FOR_GUI;
                                    RotationHandler.getInstance().reset();
                                    stuckClock.schedule(10_000L);
                                }
                        ).easeOutBack(true)
                );
                delayClock.schedule(FarmHelperConfig.getRandomRotationTime() + 500L);
                break;
            case WAIT_FOR_GUI:
                String invName = InventoryUtils.getInventoryName();
                if (invName != null && !invName.contains("Pesthunter")) {
                    PlayerUtils.closeScreen();
                    state = State.CLICK_PHILLIP;
                } else {
                    state = State.EMPTY_VACUUM;
                }
                delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
                break;
            case EMPTY_VACUUM:
                Slot vacuumSlot = InventoryUtils.getSlotOfItemInContainer("Empty Vacuum Bag");
                if (vacuumSlot == null) {
                    break;
                }
                ItemStack itemLore = vacuumSlot.getStack();
                if (InventoryUtils.getItemLore(itemLore).contains("Click to empty the vacuum")) {
                    state = State.WAIT_FOR_VACUUM;
                } else {
                    state = State.GO_BACK;
                    LogUtils.sendWarning("[Auto Pest Hunter] The vacuum is empty!");
                }
                InventoryUtils.clickContainerSlot(vacuumSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
                state = State.WAIT_FOR_VACUUM;
                stuckClock.schedule(10_000L);
                delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
                break;
            case GO_BACK:
                if (!manuallyStarted) {
                    Multithreading.schedule(() -> {
                        MacroHandler.getInstance().triggerWarpGarden(true, false);
                        Multithreading.schedule(() -> {
                            MacroHandler.getInstance().resumeMacro();
                        }, 1_000, TimeUnit.MILLISECONDS);
                    }, 500, TimeUnit.MILLISECONDS);
                }
                stop();
                break;
        }
    }

    private final String[] dialogueMessages = {
            "Howdy, ",
            "Sorry for the intrusion, but I got a report of a Pest outbreak around here?",
            "Err...vermin? Swine? Buggers? Y'know...Pests!",
            "They seem to pop up when you're breaking crops on your Garden!",
            "Okay, feller. Take this SkyMart Vacuum!",
            "There's a Pest out there somewhere, I'm certain of it.",
            "When you find one, simply aim the vacuum, hold down the button, and you can suck them buggers up real quick!",
            "When you've done that, come back and tell me all about how much fun you had!"
    };

    @SubscribeEvent
    public void onChatMessageReceived(ClientChatReceivedEvent event) {
        if (enabled && event.type == 0 && event.message != null && state == State.WAIT_FOR_VACUUM) {
            if (event.message.getFormattedText().contains("§e[NPC] §6Phillip§f: Thanks for the §6Pests§f,"))
                LogUtils.sendSuccess("[Auto Pest Hunter] Successfully emptied the vacuum!");
            else {
                for (String message : dialogueMessages) {
                    if (event.message.getFormattedText().contains("§e[NPC] §6Phillip§f: " + message)) {
                        LogUtils.sendError("[Auto Pest Hunter] You haven't unlocked Phillip yet!");
                        break;
                    }
                }
            }
            state = State.GO_BACK;
            delayClock.schedule((long) (FarmHelperConfig.pestAdditionalGUIDelay + 300 + Math.random() * 300));
        }
    }
}
