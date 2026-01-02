package com.kevin.tiertagger.tierlist;

import com.kevin.tiertagger.TierCache;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class PlayerSearchScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget textField;
    private ButtonWidget searchButton;

    private boolean searching = false;
    private CompletableFuture<?> future = null;

    public PlayerSearchScreen(Screen parent) {
        super(Text.of("Player Search"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        String username = I18n.translate("tiertagger.search.user");
        this.textField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 116, 200, 20, Text.of(username));
        this.textField.setMaxLength(32);
        this.addSelectableChild(this.textField);

        this.searchButton = this.addDrawableChild(
                ButtonWidget.builder(Text.translatable("tiertagger.search"), button -> this.loadAndShowProfile())
                        .dimensions(this.width / 2 - 100, this.height / 4 + 96 + 12, 200, 20)
                        .build()
        );

        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, button -> {
                            if (this.future != null) {
                                this.future.cancel(true);
                            }
                            MinecraftClient.getInstance().setScreen(this.parent);
                        })
                        .dimensions(this.width / 2 - 100, this.height / 4 + 120 + 12, 200, 20)
                        .build()
        );

        this.setInitialFocus(this.textField);
    }

    @Override
    public void tick() {
        super.tick();
        this.searchButton.active = this.textField.getText().matches("[a-zA-Z0-9_-]+") && !searching;
    }

    private void loadAndShowProfile() {
        String username = this.textField.getText();
        this.searching = true;
        this.searchButton.setMessage(Text.translatable("tiertagger.search.loading"));

        CompletableFuture<PlayerSkinWidget> skinFuture = CompletableFuture.supplyAsync(() -> {
            UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
            GameProfile profile = new GameProfile(offlineId, username);

            Supplier<SkinTextures> skinSupplier = MinecraftClient.getInstance().getSkinProvider().getSkinTexturesSupplier(profile);
            PlayerSkinWidget skin = new PlayerSkinWidget(60, 144, MinecraftClient.getInstance().getLoadedEntityModels(), skinSupplier);
            skin.setPosition(this.width / 2 - 65, (this.height - 144) / 2);
            return skin;
        });

        this.future = TierCache.searchPlayer(username)
                .thenCombine(skinFuture, (info, skin) -> new PlayerInfoScreen(this, info, skin))
                .thenAccept(screen -> MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(screen)))
                .whenComplete((v, t) -> {
                    if (t != null) {
                        if (MinecraftClient.getInstance().player != null) {
                            MinecraftClient.getInstance().player.sendMessage(Text.translatable("tiertagger.search.unknown"), true);
                        }
                    }
                    this.searching = false;
                    this.searchButton.setMessage(Text.translatable("tiertagger.search"));
                });
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        String string = this.textField.getText();
        this.init(client, width, height);
        this.textField.setText(string);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 16777215);
        this.textField.render(context, mouseX, mouseY, delta);
    }
}
