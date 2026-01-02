package com.kevin.tiertagger.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager<T> {
    private final T config;
    private final Path path;
    private final Gson gson;

    private ConfigManager(T config, Path path, Gson gson) {
        this.config = config;
        this.path = path;
        this.gson = gson;
    }

    public static <U> ConfigManager<U> createDefault(Class<U> clazz, String id) {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path file = dir.resolve(id + ".json");
        Gson gson = new GsonBuilder().create();
        U cfg;
        try {
            if (Files.exists(file)) {
                String s = Files.readString(file);
                cfg = gson.fromJson(s, clazz);
                if (cfg == null) cfg = clazz.getDeclaredConstructor().newInstance();
            } else {
                cfg = clazz.getDeclaredConstructor().newInstance();
                try {
                    Files.createDirectories(dir);
                } catch (IOException ignored) {
                }
                Files.writeString(file, gson.toJson(cfg));
            }
        } catch (IOException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            try {
                cfg = clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        return new ConfigManager<>(cfg, file, gson);
    }

    public T getConfig() {
        return config;
    }

    public void save() {
        try {
            Files.writeString(path, gson.toJson(config));
        } catch (IOException ignored) {
        }
    }
}
