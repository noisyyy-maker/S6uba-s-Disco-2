package com.discomusic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Cfg {
    public int volume = 60, theme = 1, style = 0;
    public boolean blur = false;
    public Map<String, List<String>> playlists = new LinkedHashMap<>();

    public static Cfg I = new Cfg();
    private static final Gson G = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("discomusic.json"); }

    public static void load() {
        try {
            if (Files.exists(file())) {
                Cfg c = G.fromJson(Files.readString(file()), Cfg.class);
                if (c != null) { I = c; if (I.playlists == null) I.playlists = new LinkedHashMap<>(); }
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try { Files.writeString(file(), G.toJson(I)); } catch (IOException ignored) {}
    }
}
