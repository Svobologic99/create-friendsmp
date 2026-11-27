package com.svobologicstudios.friendsmpupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * File operations that could not run while Minecraft had the files open (mostly on Windows).
 * Uses only the JDK, so it can also run as a tiny separate program after Minecraft exits:
 * java -cp friendsmp-updater.jar com.svobologicstudios.friendsmpupdater.PendingOps PID pending.txt
 */
public final class PendingOps {
    private PendingOps() {}

    /** Runs every line once; returns the lines that still failed. */
    public static List<String> runAll(List<String> lines) {
        List<String> left = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t");
            try {
                if (p[0].equals("DELETE") && p.length == 2) {
                    Files.deleteIfExists(Path.of(p[1]));
                } else if (p[0].equals("MOVE") && p.length == 3) {
                    Path src = Path.of(p[1]);
                    Path dst = Path.of(p[2]);
                    if (Files.exists(src)) {
                        Files.createDirectories(dst.getParent());
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException | RuntimeException e) {
                left.add(line);
            }
        }
        return left;
    }

    public static List<String> read(Path file) {
        try {
            return Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static void write(Path file, List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path file = Path.of(args[1]);
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            try {
                game.get().onExit().get(5, TimeUnit.MINUTES);
            } catch (Exception ignored) {
            }
        }
        List<String> lines = read(file);
        for (int i = 0; i < 30 && !lines.isEmpty(); i++) {
            Thread.sleep(1000);
            lines = runAll(lines);
        }
        write(file, lines);
    }
}
