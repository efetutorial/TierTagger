package com.kevin.tiertagger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kevin.tiertagger.config.TierTaggerConfig;
import com.kevin.tiertagger.model.GameMode;
import com.kevin.tiertagger.model.PlayerInfo;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import lombok.Getter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import com.kevin.tiertagger.util.ConfigManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class TierTagger implements ModInitializer {
    public static final String MOD_ID = "tier-tagger-tr";
    private static final String UPDATE_URL_FORMAT = "https://api.modrinth.com/v2/project/dpkYdLu5/version?game_versions=%s";

    public static final Gson GSON = new GsonBuilder().create();

    @Getter
    private static final ConfigManager<TierTaggerConfig> manager = ConfigManager.createDefault(TierTaggerConfig.class, MOD_ID);
    @Getter
    private static final Logger logger = LoggerFactory.getLogger(TierTagger.class);
    @Getter
    private static final HttpClient client = HttpClient.newHttpClient();

    // === version checker stuff ===
    @Getter
    private static Version latestVersion = null;
    private static final AtomicBoolean isObsolete = new AtomicBoolean(false);

    @Override
    public void onInitialize() {
        TierCache.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal(MOD_ID)
                        .then(argument("player", StringArgumentType.string())
                                .executes(TierTagger::displayTierInfo))));

        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding("tiertagger.keybind.gamemode", GLFW.GLFW_KEY_UNKNOWN, "key.categories.tiertagger"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.wasPressed()) {
                GameMode next = TierCache.findNextMode(manager.getConfig().getGameMode());
                manager.getConfig().setGameMode(next.id());
                if (mc.player != null) {
                    Text message = Text.literal("Displayed gamemode: ").append(next.asStyled(false));
                    mc.player.sendMessage(message, true);
                }
            }
        });

        checkForUpdates();
    }

    public static Text appendTier(UUID uuid, Text text) {
        MutableText following = getPlayerTier(uuid)
                .map(entry -> {
                    Text tierText = getRankingText(entry.ranking(), false);

                    if (manager.getConfig().isShowIcons() && entry.mode() != null && entry.mode().icon().isPresent()) {
                        return Text.literal(entry.mode().icon().get().toString()).append(tierText);
                    } else {
                        return tierText.copy();
                    }
                })
                .orElse(null);

        if (following != null) {
            following.append(Text.literal(" | ").formatted(Formatting.GRAY));
            return following.append(text);
        }

        return text;
    }

    public static Optional<PlayerInfo.NamedRanking> getPlayerTier(UUID uuid) {
        GameMode mode = manager.getConfig().getGameMode();

        return TierCache.getPlayerRankings(uuid)
                .map(rankings -> {
                    PlayerInfo.Ranking ranking = rankings.get(mode.id());
                    Optional<PlayerInfo.NamedRanking> highest = PlayerInfo.getHighestRanking(rankings);
                    TierTaggerConfig.HighestMode highestMode = manager.getConfig().getHighestMode();

                    if (ranking == null) {
                        if (highestMode != TierTaggerConfig.HighestMode.NEVER && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return null;
                        }
                    } else {
                        if (highestMode == TierTaggerConfig.HighestMode.ALWAYS && highest.isPresent()) {
                            return highest.get();
                        } else {
                            return ranking.asNamed(mode);
                        }
                    }
                });
    }

    private static MutableText getTierText(int tier, int pos, boolean retired) {
        StringBuilder text = new StringBuilder();
        if (retired) text.append("R");
        text.append(pos == 0 ? "H" : "L").append("T").append(tier);

        int color = TierTagger.getTierColor(text.toString());
        return Text.literal(text.toString()).styled(s -> s.withColor(color));
    }

    public static Text getRankingText(PlayerInfo.Ranking ranking, boolean showPeak) {
        if (ranking.retired() && ranking.peakTier() != null && ranking.peakPos() != null) {
            return getTierText(ranking.peakTier(), ranking.peakPos(), true);
        } else {
            MutableText tierText = getTierText(ranking.tier(), ranking.pos(), false);

            if (showPeak && ranking.comparablePeak() < ranking.comparableTier()) {
                // warning caused by potential NPE by unboxing of peak{Tier,Pos} which CANNOT happen, see impl of comparablePeak
                // noinspection DataFlowIssue
                tierText.append(Text.literal(" (peak: ").styled(s -> s.withColor(Formatting.GRAY)))
                        .append(getTierText(ranking.peakTier(), ranking.peakPos(), false))
                        .append(Text.literal(")").styled(s -> s.withColor(Formatting.GRAY)));
            }

            return tierText;
        }
    }

    private static int displayTierInfo(CommandContext<FabricClientCommandSource> ctx) {
        String selector = StringArgumentType.getString(ctx, "player");

        Optional<Map<String, PlayerInfo.Ranking>> rankings = ctx.getSource().getWorld().getPlayers().stream()
                .filter(p -> p.getNameForScoreboard().equalsIgnoreCase(selector) || p.getUuidAsString().equalsIgnoreCase(selector))
                .findFirst()
                .map(Entity::getUuid)
                .flatMap(TierCache::getPlayerRankings);

        if (rankings.isPresent()) {
            ctx.getSource().sendFeedback(printPlayerInfo(selector, rankings.get()));
        } else {
            ctx.getSource().sendFeedback(Text.of("[TierTagger] Searching..."));
            TierCache.searchPlayer(selector)
                    .thenAccept(p -> MinecraftClient.getInstance().execute(() -> ctx.getSource().sendFeedback(printPlayerInfo(selector, p.rankings()))))
                    .exceptionally(t -> {
                        ctx.getSource().sendError(Text.of("Could not find player " + selector));
                        return null;
                    });
        }

        return 0;
    }

    private static Text printPlayerInfo(String name, Map<String, PlayerInfo.Ranking> rankings) {
        if (rankings.isEmpty()) {
            return Text.literal(name + " does not have any tiers.");
        } else {
            MutableText text = Text.empty().append("=== Rankings for " + name + " ===");

            rankings.forEach((m, r) -> {
                if (m == null) return;
                GameMode mode = TierCache.findModeOrUgly(m);
                Text tierText = getRankingText(r, true);
                text.append(Text.literal("\n").append(mode.asStyled(true)).append(": ").append(tierText));
            });

            return text;
        }
    }

    public static int getTierColor(String tier) {
        if (tier.startsWith("R")) {
            return manager.getConfig().getRetiredColor();
        } else {
            return manager.getConfig().getTierColors().getOrDefault(tier, 0xD3D3D3);
        }
    }

    private static void checkForUpdates() {
        String versionParam = "[\"%s\"]".formatted(SharedConstants.getGameVersion().name());
        String fullUrl = UPDATE_URL_FORMAT.formatted(URLEncoder.encode(versionParam, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(fullUrl)).GET().build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    String body = r.body();
                    JsonArray array = GSON.fromJson(body, JsonArray.class);

                    if (!array.isEmpty()) {
                        JsonObject root = array.get(0).getAsJsonObject();

                        String versionName = root.get("name").getAsString();
                        if (versionName != null && versionName.toLowerCase(Locale.ROOT).startsWith("[o")) {
                            isObsolete.set(true);
                        }

                        String latestVer = root.get("version_number").getAsString();
                        try {
                            return Version.parse(latestVer);
                        } catch (VersionParsingException e) {
                            logger.warn("Could not parse version number {}", latestVer);
                        }
                    }

                    return null;
                })
                .exceptionally(t -> {
                    logger.warn("Error checking for updates", t);
                    return null;
                }).thenAccept(v -> {
                    logger.info("Found latest version {}", v.getFriendlyString());
                    latestVersion = v;
                });
    }

    public static boolean isObsolete() {
        return isObsolete.get();
    }
}
