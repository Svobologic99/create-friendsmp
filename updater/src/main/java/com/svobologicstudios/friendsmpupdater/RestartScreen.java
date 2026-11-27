package com.svobologicstudios.friendsmpupdater;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RestartScreen extends Screen {
    private final PackSync.Result result;
    private final Screen next;

    public RestartScreen(PackSync.Result result, Screen next) {
        super(Component.literal("Create FriendSMP modpack updated"));
        this.result = result;
        this.next = next;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 + 30;
        addRenderableWidget(Button.builder(Component.literal("Quit Minecraft"), b -> this.minecraft.stop())
                .bounds(cx - 155, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Continue anyway"), b -> this.minecraft.setScreen(next))
                .bounds(cx + 5, y, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int y = this.height / 2 - 50;
        g.drawCenteredString(this.font, this.title, cx, y, 0xFFAA00);
        y += 20;
        g.drawCenteredString(this.font, Component.literal(
                result.added + " added, " + result.updated + " updated, " + result.removed + " removed"), cx, y, 0xFFFFFF);
        y += 14;
        g.drawCenteredString(this.font, Component.literal(
                "Please restart Minecraft so the changes take effect."), cx, y, 0xFFFFFF);
        if (result.error != null) {
            y += 14;
            g.drawCenteredString(this.font, Component.literal("Some files failed: " + result.error), cx, y, 0xFF5555);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
