package com.svobologicstudios.friendsmpupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.server.packs.repository.PackRepository;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Server list entry and resource pack switching, done once the title screen opens. */
final class ClientExtras {
    static final String DEFAULT_SERVER_NAME = "Create FriendSMP";
    static final String DEFAULT_SERVER_IP = "116.202.49.157:50181";

    private ClientExtras() {}

    private static Path dataDir() {
        return FMLPaths.CONFIGDIR.get().resolve("friendsmp-updater");
    }

    private static Properties config() {
        Properties p = new Properties();
        Path file = dataDir().resolve("config.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    /** Adds the FriendSMP server to the multiplayer list, unless it is already there. */
    static void addServer() {
        Properties cfg = config();
        if (!Boolean.parseBoolean(cfg.getProperty("add-server", "true"))) return;
        String name = cfg.getProperty("server-name", DEFAULT_SERVER_NAME).trim();
        String ip = cfg.getProperty("server-ip", DEFAULT_SERVER_IP).trim();
        if (ip.isEmpty()) return;

        ServerList list = new ServerList(Minecraft.getInstance());
        list.load();
        for (int i = 0; i < list.size(); i++) {
            ServerData d = list.get(i);
            if (d.ip.equalsIgnoreCase(ip) || d.name.equals(name)) {
                if (!d.ip.equalsIgnoreCase(ip) && d.name.equals(name)) {
                    // Our entry, but the address changed: update it.
                    d.ip = ip;
                    list.save();
                    FriendSmpUpdater.LOG.info("[FriendSMP Updater] Updated server list entry to {}", ip);
                }
                return;
            }
        }
        list.add(new ServerData(name, ip, ServerData.Type.OTHER), false);
        list.save();
        FriendSmpUpdater.LOG.info("[FriendSMP Updater] Added {} ({}) to the server list", name, ip);
    }

    /**
     * Turns on pack resource packs the first time they appear. A pack the player turns off later
     * stays off, because we remember which ones we already turned on.
     */
    static void enableResourcePacks(List<String> files) throws IOException {
        if (files == null || files.isEmpty()) return;
        if (!Boolean.parseBoolean(config().getProperty("enable-resource-packs", "true"))) return;

        Path doneFile = dataDir().resolve("enabled-resourcepacks.txt");
        Set<String> done = new LinkedHashSet<>();
        if (Files.exists(doneFile)) done.addAll(Files.readAllLines(doneFile, StandardCharsets.UTF_8));

        Minecraft mc = Minecraft.getInstance();
        PackRepository repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        boolean changed = false;
        for (String file : files) {
            if (done.contains(file)) continue;
            String id = "file/" + file;
            if (!repo.getAvailableIds().contains(id)) continue;
            if (!selected.contains(id)) {
                selected.add(id); // end of the list = highest priority
                changed = true;
            }
            done.add(file);
        }
        Files.createDirectories(doneFile.getParent());
        Files.write(doneFile, done, StandardCharsets.UTF_8);
        if (changed) {
            repo.setSelected(selected);
            mc.options.updateResourcePacks(repo);
            FriendSmpUpdater.LOG.info("[FriendSMP Updater] Enabled resource packs: {}", selected);
        }
    }
}
