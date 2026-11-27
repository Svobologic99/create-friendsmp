package com.svobologicstudios.friendsmpupdater;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(value = FriendSmpUpdater.MODID, dist = Dist.CLIENT)
public final class FriendSmpUpdater {
    public static final String MODID = "friendsmpupdater";
    public static final Logger LOG = LogUtils.getLogger();

    private static PackSync.Result result;
    private static boolean screenShown;

    public FriendSmpUpdater(IEventBus modBus, ModContainer container) {
        Path ownJar = null;
        try {
            ownJar = container.getModInfo().getOwningFile().getFile().getFilePath();
        } catch (Throwable t) {
            LOG.warn("[FriendSMP Updater] Could not find own jar", t);
        }

        long start = System.currentTimeMillis();
        try {
            PackSync sync = new PackSync(FMLPaths.GAMEDIR.get(), FMLPaths.CONFIGDIR.get(), ownJar);
            result = sync.run();
        } catch (Throwable t) {
            LOG.error("[FriendSMP Updater] Update failed", t);
            result = new PackSync.Result();
            result.error = String.valueOf(t.getMessage());
        }
        LOG.info("[FriendSMP Updater] Finished in {} ms: {}", System.currentTimeMillis() - start, result.summary());

        NeoForge.EVENT_BUS.addListener(FriendSmpUpdater::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (screenShown || result == null || !result.needsRestart()) return;
        if (event.getNewScreen() instanceof TitleScreen title) {
            screenShown = true;
            event.setNewScreen(new RestartScreen(result, title));
        }
    }
}
