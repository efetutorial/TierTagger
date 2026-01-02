package com.kevin.tiertagger.mixin;

import com.kevin.tiertagger.TierTagger;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerListHud.class)
public class MixinPlayerListHud {
    @ModifyReturnValue(method = "getPlayerName", at = @At("RETURN"))
    @Nullable
    public Text prependTier(Text original, PlayerListEntry entry) {
        TierTaggerConfig config = TierTagger.getManager().getConfig();
        if (config.isEnabled() && config.isPlayerList()) {
            return TierTagger.appendTier(entry.getProfile().getId(), original);
        } else {
            return original;
        }
    }
}
