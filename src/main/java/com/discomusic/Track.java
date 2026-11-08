package com.discomusic;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class Track {
    public final Path path;
    public final String file, title, artist;
    public volatile double duration = -1;

    Track(Path p) {
        path = p;
        file = p.getFileName().toString();
        String n = file.substring(0, file.lastIndexOf('.'));
        int i = n.indexOf(" - ");
        if (i > 0) { artist = n.substring(0, i).trim(); title = n.substring(i + 3).trim(); }
        else { artist = "Unknown artist"; title = n; }
    }

    public static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods").resolve("music");
    }

    /** Scans mods/music (and loose files directly in mods/) for .mp3, .ogg and .wav files. */
    public static List<Track> scan() {
        List<Track> out = new ArrayList<>();
        try { Files.createDirectories(dir()); } catch (IOException ignored) {}
        for (Path d : new Path[]{dir(), dir().getParent()}) {
            try (Stream<Path> s = Files.list(d)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".wav")) out.add(new Track(p));
                });
            } catch (IOException ignored) {}
        }
        out.sort(Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT)));
        return out;
    }
}
