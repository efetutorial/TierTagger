package com.kevin.tiertagger.model;

import com.google.gson.JsonObject;
import com.kevin.tiertagger.TierTagger;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Pair;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public record GameMode(String id, String title) {
    public static final GameMode NONE = new GameMode("annoying_long_id_that_no_one_will_ever_use_just_to_make_sure", "§cNone§r");

    public static CompletableFuture<List<GameMode>> fetchGamemodes(HttpClient client) {
        String endpoint = TierTagger.getManager().getConfig().getApiUrl() + "/tierlists";
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> {
                    JsonObject obj = TierTagger.GSON.fromJson(r.body(), JsonObject.class);

                    return obj.entrySet().stream().map(e -> {
                        String title = e.getValue().getAsJsonObject().get("title").getAsString();
                        return new GameMode(e.getKey(), title);
                    }).toList();
                });
    }

    public boolean isNone() {
        return this.id.equals(NONE.id);
    }

    private Pair<Character, TextColor> iconAndColor() {
        return switch (this.id) {
            case "axe" -> new Pair<>('\uE701', TextColor.fromFormatting(Formatting.GREEN));
            case "mace" -> new Pair<>('\uE702', TextColor.fromFormatting(Formatting.GRAY));
            case "neth_pot" -> new Pair<>('\uE703', TextColor.fromRgb(0x7d4a40));
            case "pot" -> new Pair<>('\uE704', TextColor.fromRgb(0xff0000));
            case "smp" -> new Pair<>('\uE705', TextColor.fromRgb(0xeccb45));
            case "sword" -> new Pair<>('\uE706', TextColor.fromRgb(0xa4fdf0));
            case "uhc" -> new Pair<>('\uE707', TextColor.fromFormatting(Formatting.RED));
            case "crystal" -> new Pair<>('\uE708', TextColor.fromFormatting(Formatting.LIGHT_PURPLE));
            default -> new Pair<>('•', TextColor.fromFormatting(Formatting.WHITE));
        };
    }

    public Optional<Character> icon() {
        Pair<Character, TextColor> pair = this.iconAndColor();

        return pair.getRight().getRgb() == 0xFFFFFF ? Optional.empty() : Optional.of(pair.getLeft());
    }

    public Text asStyled(boolean withDefaultDot) {
        Pair<Character, TextColor> pair = this.iconAndColor();

        if (pair.getRight().getRgb() == 0xFFFFFF && !withDefaultDot) {
            return Text.of(this.title);
        } else {
            Text name = Text.literal(this.title).styled(s -> s.withColor(pair.getRight()));
            return Text.literal(pair.getLeft() + " ").append(name);
        }
    }
}
