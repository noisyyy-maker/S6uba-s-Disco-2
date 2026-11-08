package com.discomusic;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.IntConsumer;

/** Immediate-mode UI: draws only while open, so it costs nothing in-game. */
public class DiscoScreen extends Screen {
    static final int[] ACC = {0xFFFF5FA2, 0xFFC44BFF, 0xFF4FB4FF, 0xFF1ED760};
    static final int[] BGC = {0x240C1A, 0x140C22, 0x0A1626, 0x0A1C12};
    static final int[] ALPHA = {0x66, 0xB8, 0xFF};
    static final String[] THEME_N = {"Pink", "Purple", "Sky blue", "Green"};
    static final String[] STYLE = {"Transparent", "Translucent", "Solid"};
    static final String[] STYLE_D = {"See more of your world", "Mostly see-through", "Solid theme color"};
    static final int[] ART = {0xFF2A1A5E, 0xFF5E1A3F, 0xFF1A3F5E, 0xFF1A5E33, 0xFF5E4A1A, 0xFF3A1A5E};
    static final int WHITE = 0xFFFFFFFF, GREY = 0xFFB0B0C0;
    static final int P_PLAY = 0, P_PAUSE = 1, P_NEXT = 2, P_PREV = 3, P_PLUS = 4, P_X = 5, P_CHECK = 6;

    // remembered between openings
    static int view = 0, tab = 0;
    static boolean showQueue = true, showSettings = false;
    static String openPl = null;
    static boolean editPl = false;

    static final class Hit { int x, y, w, h; Runnable click; IntConsumer drag; }

    private final List<Hit> hits = new ArrayList<>();
    private Hit dragging;
    private int scroll;
    private KeyMapping listening;

    public DiscoScreen() {
        super(Component.literal("Disco"));
        Player.refresh();
    }

    @Override public boolean isPauseScreen() { return false; }

    /** Vanilla draws a blurred, dimmed background here. Skipping it saves a full-screen blur pass every frame. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
        if (Cfg.I.blur) super.extractBackground(g, mx, my, dt);
    }

    // ---------- helpers ----------
    private int acc() { return ACC[Cfg.I.theme]; }
    private int bgc() { return (ALPHA[Cfg.I.style] << 24) | BGC[Cfg.I.theme]; }
    private int tint(int a) { return (a << 24) | (acc() & 0xFFFFFF); }
    private static boolean in(int mx, int my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private Hit add(int x, int y, int w, int h, Runnable c, IntConsumer d) {
        Hit t = new Hit(); t.x = x; t.y = y; t.w = w; t.h = h; t.click = c; t.drag = d;
        hits.add(t);
        return t;
    }

    private void txt(GuiGraphicsExtractor g, String s, int x, int y, int c) { g.text(this.font, s, x, y, c, false); }

    private String clip(String s, int w) {
        if (this.font.width(s) <= w) return s;
        while (s.length() > 1 && this.font.width(s + "..") > w) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x, y, x + w, y + 1, c); g.fill(x, y + h - 1, x + w, y + h, c);
        g.fill(x, y, x + 1, y + h, c); g.fill(x + w - 1, y, x + w, y + h, c);
    }

    private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, bgc());
        border(g, x, y, w, h, 0x38FFFFFF);
    }

    private void icon(GuiGraphicsExtractor g, int p, int cx, int cy, int c) {
        switch (p) {
            case P_PLAY -> tri(g, cx - 3, cy, 8, false, c);
            case P_PAUSE -> { g.fill(cx - 3, cy - 4, cx - 1, cy + 4, c); g.fill(cx + 1, cy - 4, cx + 3, cy + 4, c); }
            case P_NEXT -> { tri(g, cx - 4, cy, 8, false, c); g.fill(cx + 3, cy - 4, cx + 5, cy + 4, c); }
            case P_PREV -> { tri(g, cx - 3, cy, 8, true, c); g.fill(cx - 5, cy - 4, cx - 3, cy + 4, c); }
            case P_PLUS -> { g.fill(cx - 4, cy, cx + 4, cy + 1, c); g.fill(cx, cy - 4, cx + 1, cy + 4, c); }
            case P_X -> { for (int i = 0; i < 7; i++) { g.fill(cx - 3 + i, cy - 3 + i, cx - 2 + i, cy - 2 + i, c); g.fill(cx + 3 - i, cy - 3 + i, cx + 4 - i, cy - 2 + i, c); } }
            case P_CHECK -> { for (int i = 0; i < 3; i++) g.fill(cx - 4 + i, cy + i, cx - 3 + i, cy + i + 1, c); for (int i = 0; i < 5; i++) g.fill(cx - 1 + i, cy + 2 - i, cx + i, cy + 3 - i, c); }
            default -> { }
        }
    }

    private void tri(GuiGraphicsExtractor g, int x, int cy, int h, boolean left, int c) {
        int wd = (int) (h * 0.8);
        for (int i = 0; i < wd; i++) {
            int hh = Math.max(1, (int) (h * (1.0 - (double) i / wd)));
            int px = left ? x + wd - 1 - i : x + i;
            g.fill(px, cy - hh / 2, px + 1, cy - hh / 2 + hh, c);
        }
    }

    private void btn(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, String label, boolean on, Runnable r) {
        boolean hov = in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, on ? tint(0x55) : hov ? 0x40FFFFFF : 0x22FFFFFF);
        if (on) border(g, x, y, w, h, acc());
        txt(g, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, WHITE);
        add(x, y, w, h, r, null);
    }

    private void ibtn(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, int ic, boolean on, Runnable r) {
        boolean hov = in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, on ? tint(0x55) : hov ? 0x40FFFFFF : 0x00FFFFFF);
        icon(g, ic, x + w / 2, y + h / 2, on ? acc() : WHITE);
        add(x, y, w, h, r, null);
    }

    private void art(GuiGraphicsExtractor g, int x, int y, int s, Track t) {
        int base = ART[Math.floorMod(t == null ? 0 : t.title.hashCode(), ART.length)];
        if (s < 20) { g.fill(x, y, x + s, y + s, base); return; }
        for (int r = 0; r < s; r++) {
            float f = r / (float) s;
            int cr = (int) (((base >> 16) & 255) * (1 - f) + ((acc() >> 16) & 255) * f * 0.8f);
            int cg = (int) (((base >> 8) & 255) * (1 - f) + ((acc() >> 8) & 255) * f * 0.8f);
            int cb = (int) ((base & 255) * (1 - f) + (acc() & 255) * f * 0.8f);
            g.fill(x, y + r, x + s, y + r + 1, 0xFF000000 | (cr << 16) | (cg << 8) | cb);
        }
        String l = t == null ? "?" : t.title.substring(0, 1).toUpperCase();
        txt(g, l, x + (s - this.font.width(l)) / 2, y + s / 2 - 4, WHITE);
    }

    private static String fmt(double s) {
        if (s < 0) return "--:--";
        int t = (int) s;
        return (t / 60) + ":" + (t % 60 < 10 ? "0" : "") + (t % 60);
    }

    private List<Track> tracksOf(String pl) {
        List<Track> out = new ArrayList<>();
        List<String> names = Cfg.I.playlists.get(pl);
        if (names == null) return out;
        for (String n : names) for (Track t : Player.lib) if (t.file.equals(n)) { out.add(t); break; }
        return out;
    }

    // ---------- render ----------
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        hits.clear();
        int TW = Math.min(this.width - 8, 540), TH = Math.min(this.height - 8, 236);
        int x0 = (this.width - TW) / 2, y0 = (this.height - TH) / 2;
        boolean right = showQueue || showSettings;
        int RW = Math.min(170, TW * 3 / 10 + 10);
        int mainW = right ? TW - RW - 4 : TW;

        // ----- main window -----
        panel(g, x0, y0, mainW, TH);
        txt(g, "S6uba's ", x0 + 8, y0 + 6, WHITE);
        txt(g, "Disco", x0 + 8 + this.font.width("S6uba's "), y0 + 6, acc());
        ibtn(g, mx, my, x0 + mainW - 18, y0 + 3, 14, 14, P_X, false, this::onClose);
        g.fill(x0, y0 + 18, x0 + mainW, y0 + 19, 0x22FFFFFF);

        // sidebar
        int SW = 58;
        g.fill(x0 + SW, y0 + 19, x0 + SW + 1, y0 + TH, 0x22FFFFFF);
        String[] nav = {"Music", "Playlists", "Queue", "Settings"};
        for (int i = 0; i < 4; i++) {
            int ny = y0 + 22 + i * 28;
            boolean on = (i == 0 && view == 0) || (i == 1 && view == 1) || (i == 2 && showQueue) || (i == 3 && showSettings);
            boolean hov = in(mx, my, x0 + 1, ny, SW - 1, 26);
            g.fill(x0 + 1, ny, x0 + SW, ny + 26, on ? 0x30FFFFFF : hov ? 0x15FFFFFF : 0);
            if (on) g.fill(x0 + 1, ny, x0 + 3, ny + 26, acc());
            txt(g, nav[i], x0 + 1 + (SW - 1 - this.font.width(nav[i])) / 2, ny + 9, WHITE);
            final int k = i;
            add(x0 + 1, ny, SW - 1, 26, () -> {
                scroll = 0;
                if (k == 0) { view = 0; } else if (k == 1) { view = 1; openPl = null; editPl = false; }
                else if (k == 2) { showQueue = !showQueue; if (showQueue) showSettings = false; }
                else { showSettings = !showSettings; if (showSettings) showQueue = false; }
            }, null);
        }

        int cx = x0 + SW + 7, cw = mainW - SW - 13, cy = y0 + 24;

        // now playing
        Track cur = Player.current;
        art(g, cx, cy, 52, cur);
        int tx = cx + 60;
        String title = cur == null ? "Nothing playing" : cur.title;
        txt(g, clip(title, cw - 60 - 96), tx, cy + 2, WHITE);
        txt(g, clip(Player.error != null ? "Can't play this song (details below)" : cur == null ? "Press play or pick a song" : cur.artist, cw - 60 - 96), tx, cy + 14, Player.error != null ? 0xFFFF6060 : GREY);

        // volume (right side, top)
        int vx = cx + cw - 92;
        txt(g, Cfg.I.volume + "%", vx, cy + 2, WHITE);
        int vsx = vx + 28, vsw = 60;
        g.fill(vsx, cy + 4, vsx + vsw, cy + 7, 0x40FFFFFF);
        g.fill(vsx, cy + 4, vsx + vsw * Cfg.I.volume / 100, cy + 7, acc());
        g.fill(vsx + vsw * Cfg.I.volume / 100 - 2, cy + 2, vsx + vsw * Cfg.I.volume / 100 + 2, cy + 9, WHITE);
        add(vsx - 3, cy, vsw + 6, 12, null, m -> Player.setVolume(Math.round((m - vsx) * 100f / vsw)));

        // progress
        double dur = cur != null ? cur.duration : -1;
        double pos = Player.playing ? Player.pos : 0;
        txt(g, fmt(pos), tx, cy + 31, GREY);
        int bx = tx + 28, bw = cw - 60 - 28 - 30;
        g.fill(bx, cy + 35, bx + bw, cy + 38, 0x40FFFFFF);
        if (dur > 0) {
            int fw = (int) (bw * Math.min(1, pos / dur));
            g.fill(bx, cy + 35, bx + fw, cy + 38, acc());
            g.fill(bx + fw - 2, cy + 33, bx + fw + 2, cy + 40, WHITE);
        }
        txt(g, fmt(dur), bx + bw + 4, cy + 31, GREY);
        add(bx - 3, cy + 30, bw + 6, 14, null, m -> { Track c = Player.current; if (c != null && c.duration > 0) Player.seek((m - bx) * c.duration / bw); });

        // transport
        int mid = cx + cw / 2, ty = cy + 56;
        btn(g, mx, my, mid - 88, ty + 2, 34, 14, "Shuf", Player.shuffle, () -> Player.shuffle = !Player.shuffle);
        ibtn(g, mx, my, mid - 40, ty, 24, 18, P_PREV, false, Player::prev);
        boolean showPause = Player.playing && !Player.paused;
        g.fill(mid - 14, ty - 1, mid + 14, ty + 19, 0x22FFFFFF);
        border(g, mid - 14, ty - 1, 28, 20, acc());
        icon(g, showPause ? P_PAUSE : P_PLAY, mid, ty + 9, WHITE);
        add(mid - 14, ty - 1, 28, 20, Player::toggle, null);
        ibtn(g, mx, my, mid + 16, ty, 24, 18, P_NEXT, false, () -> Player.next(false));
        btn(g, mx, my, mid + 54, ty + 2, 34, 14, "Rep", Player.repeat, () -> Player.repeat = !Player.repeat);

        // list area
        int ly = cy + 80, bottom = y0 + TH - 22;
        g.fill(cx, ly - 2, cx + cw, ly - 1, 0x22FFFFFF);
        int off = 0;
        if (Player.error != null) {
            String er = Player.error;
            int n = 0;
            while (!er.isEmpty() && n < 4) {
                int k = er.length();
                while (k > 1 && this.font.width(er.substring(0, k)) > cw - 4) k--;
                txt(g, er.substring(0, k), cx + 2, ly + 14 + n * 10, 0xFFFF6060);
                er = er.substring(k);
                n++;
            }
            off = n * 10 + 2;
        }
        if (view == 0) {
            txt(g, "Music Library (" + Player.lib.size() + ")", cx + 2, ly + 3, WHITE);
            if (Player.lib.isEmpty()) {
                txt(g, "No songs yet. Put .mp3 files in", cx + 4, ly + 22, GREY);
                txt(g, "mods/music, then click Refresh.", cx + 4, ly + 34, GREY);
            } else list(g, mx, my, cx, ly + 14 + off, cw, bottom - ly - 14 - off, Player.lib, 0);
        } else if (openPl == null) {
            txt(g, "Playlists", cx + 2, ly + 3, WHITE);
            btn(g, mx, my, cx + cw - 56, ly, 56, 12, "+ New", false, () -> {
                int n = Cfg.I.playlists.size() + 1;
                while (Cfg.I.playlists.containsKey("Playlist " + n)) n++;
                Cfg.I.playlists.put("Playlist " + n, new ArrayList<>());
                Cfg.save();
            });
            int rh = 18, vis = Math.max(1, (bottom - ly - 14) / rh), i = 0;
            List<String> names = new ArrayList<>(Cfg.I.playlists.keySet());
            scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - vis)));
            for (int k = scroll; k < names.size() && i < vis; k++, i++) {
                String n = names.get(k);
                int ry = ly + 14 + i * rh;
                if (in(mx, my, cx, ry, cw, rh)) g.fill(cx, ry, cx + cw, ry + rh, 0x18FFFFFF);
                txt(g, clip(n, cw - 90), cx + 4, ry + 5, WHITE);
                txt(g, Cfg.I.playlists.get(n).size() + " songs", cx + cw - 82, ry + 5, GREY);
                add(cx, ry, cw, rh, () -> { openPl = n; editPl = false; scroll = 0; }, null);
                ibtn(g, mx, my, cx + cw - 20, ry + 1, 16, 16, P_PLAY, false, () -> {
                    List<Track> l = tracksOf(n);
                    if (!l.isEmpty()) Player.play(l.get(0), l);
                });
            }
            if (names.isEmpty()) txt(g, "Click \"+ New\" to make a playlist.", cx + 4, ly + 22, GREY);
        } else {
            btn(g, mx, my, cx, ly, 34, 12, "Back", false, () -> { openPl = null; editPl = false; scroll = 0; });
            txt(g, clip(openPl, cw - 190), cx + 40, ly + 3, acc());
            btn(g, mx, my, cx + cw - 144, ly, 54, 12, editPl ? "Done" : "Add songs", editPl, () -> { editPl = !editPl; scroll = 0; });
            btn(g, mx, my, cx + cw - 86, ly, 40, 12, "Play", false, () -> {
                List<Track> l = tracksOf(openPl);
                if (!l.isEmpty()) Player.play(l.get(0), l);
            });
            btn(g, mx, my, cx + cw - 42, ly, 42, 12, "Delete", false, () -> {
                Cfg.I.playlists.remove(openPl); Cfg.save(); openPl = null; editPl = false;
            });
            list(g, mx, my, cx, ly + 14 + off, cw, bottom - ly - 14 - off, editPl ? Player.lib : tracksOf(openPl), editPl ? 2 : 1);
        }

        // bottom row
        int by = y0 + TH - 18;
        btn(g, mx, my, cx, by, 46, 13, "Refresh", false, () -> Player.refresh());
        btn(g, mx, my, cx + cw - 82, by, 82, 13, "Open Music Folder", false, () -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                new ProcessBuilder(os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open", Track.dir().toString()).start();
            } catch (Throwable ignored) {}
        });

        // ----- right panel -----
        if (right) {
            int rx = x0 + mainW + 4;
            panel(g, rx, y0, RW, TH);
            if (showQueue) queuePanel(g, mx, my, rx, y0, RW, TH);
            else settingsPanel(g, mx, my, rx, y0, RW, TH);
        }
    }

    private void list(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h, List<Track> items, int mode) {
        int rh = 18, vis = Math.max(1, h / rh), max = Math.max(0, items.size() - vis);
        scroll = Math.max(0, Math.min(scroll, max));
        for (int i = 0; i < vis && i + scroll < items.size(); i++) {
            final Track t = items.get(i + scroll);
            int ry = y + i * rh;
            boolean isCur = t == Player.current;
            if (isCur) { g.fill(x, ry, x + w, ry + rh - 1, tint(0x30)); border(g, x, ry, w, rh - 1, acc()); }
            else if (in(mx, my, x, ry, w, rh)) g.fill(x, ry, x + w, ry + rh - 1, 0x18FFFFFF);
            art(g, x + 3, ry + 3, 12, t);
            int nameW = w - 20 - 40;
            String name = clip(t.title, nameW);
            txt(g, name, x + 20, ry + 5, isCur ? acc() : WHITE);
            int rest = nameW - this.font.width(name) - 6;
            if (rest > 30) txt(g, clip(t.artist, rest), x + 20 + this.font.width(name) + 6, ry + 5, GREY);

            final List<Track> ctxList = new ArrayList<>(items);
            if (mode == 2) {
                final boolean has = Cfg.I.playlists.getOrDefault(openPl, List.of()).contains(t.file);
                add(x, ry, w, rh, () -> togglePl(t), null);
                ibtn(g, mx, my, x + w - 20, ry + 1, 16, 16, has ? P_CHECK : P_PLUS, has, () -> togglePl(t));
            } else {
                add(x, ry, w, rh, () -> Player.play(t, ctxList), null);
                ibtn(g, mx, my, x + w - 38, ry + 1, 16, 16, P_PLAY, false, () -> Player.play(t, ctxList));
                if (mode == 0) ibtn(g, mx, my, x + w - 20, ry + 1, 16, 16, P_PLUS, false, () -> Player.queue.add(t));
                else ibtn(g, mx, my, x + w - 20, ry + 1, 16, 16, P_X, false, () -> togglePl(t));
            }
        }
        if (max > 0) {
            int sh = Math.max(8, h * vis / items.size());
            int sy = y + (h - sh) * scroll / max;
            g.fill(x + w - 2, y, x + w, y + h, 0x20FFFFFF);
            g.fill(x + w - 2, sy, x + w, sy + sh, acc());
        }
    }

    private void togglePl(Track t) {
        List<String> l = Cfg.I.playlists.get(openPl);
        if (l == null) return;
        if (!l.remove(t.file)) l.add(t.file);
        Cfg.save();
    }

    private void queuePanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        txt(g, "Queue (" + Player.queue.size() + ")", x + 8, y + 6, WHITE);
        ibtn(g, mx, my, x + w - 18, y + 3, 14, 14, P_X, false, () -> showQueue = false);
        g.fill(x, y + 18, x + w, y + 19, 0x22FFFFFF);
        int rh = 22, vis = (h - 19 - 24) / rh;
        for (int i = 0; i < Player.queue.size() && i < vis; i++) {
            final int k = i;
            Track t = Player.queue.get(i);
            int ry = y + 21 + i * rh;
            art(g, x + 5, ry + 3, 14, t);
            txt(g, clip(t.title, w - 50), x + 24, ry + 2, WHITE);
            txt(g, clip(t.artist, w - 50), x + 24, ry + 12, GREY);
            ibtn(g, mx, my, x + w - 20, ry + 3, 16, 16, P_X, false, () -> { if (k < Player.queue.size()) Player.queue.remove(k); });
        }
        if (Player.queue.size() > vis) txt(g, "+" + (Player.queue.size() - vis) + " more", x + 8, y + h - 32, GREY);
        if (Player.queue.isEmpty()) txt(g, "Use + on a song to queue it", x + 8, y + 26, GREY);
        btn(g, mx, my, x + 6, y + h - 20, w - 12, 14, "Clear Queue", false, () -> Player.queue.clear());
    }

    private void settingsPanel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, int h) {
        txt(g, "Settings", x + 8, y + 6, WHITE);
        ibtn(g, mx, my, x + w - 18, y + 3, 14, 14, P_X, false, () -> showSettings = false);
        g.fill(x, y + 18, x + w, y + 19, 0x22FFFFFF);
        String[] tabs = {"Look", "General", "Keys"};
        int tw = (w - 8) / 3;
        for (int i = 0; i < 3; i++) { final int k = i; btn(g, mx, my, x + 4 + i * tw, y + 22, tw - 2, 13, tabs[i], tab == i, () -> tab = k); }
        int px = x + 8, py = y + 42;
        if (tab == 0) {
            txt(g, "Theme", px, py, WHITE);
            for (int i = 0; i < 4; i++) {
                final int k = i;
                int sx = px + i * 26;
                g.fill(sx, py + 12, sx + 20, py + 32, ACC[i]);
                if (Cfg.I.theme == i) border(g, sx - 2, py + 10, 24, 24, WHITE);
                add(sx, py + 12, 20, 20, () -> { Cfg.I.theme = k; Cfg.save(); }, null);
            }
            txt(g, THEME_N[Cfg.I.theme], px, py + 38, GREY);
            txt(g, "Menu Style", px, py + 54, WHITE);
            for (int i = 0; i < 3; i++) {
                final int k = i;
                int ry = py + 66 + i * 28;
                boolean on = Cfg.I.style == i;
                g.fill(px - 3, ry, x + w - 5, ry + 26, on ? tint(0x30) : in(mx, my, px - 3, ry, w - 10, 26) ? 0x15FFFFFF : 0);
                if (on) border(g, px - 3, ry, w - 10, 26, acc());
                g.fill(px + 2, ry + 8, px + 10, ry + 16, 0x55FFFFFF);
                if (on) g.fill(px + 4, ry + 10, px + 8, ry + 14, acc());
                txt(g, STYLE[i], px + 16, ry + 3, WHITE);
                txt(g, clip(STYLE_D[i], w - 36), px + 16, ry + 14, GREY);
                add(px - 3, ry, w - 10, 26, () -> { Cfg.I.style = k; Cfg.save(); }, null);
            }
            int by2 = py + 154;
            g.fill(px, by2, px + 10, by2 + 10, 0x55FFFFFF);
            if (Cfg.I.blur) g.fill(px + 2, by2 + 2, px + 8, by2 + 8, acc());
            txt(g, "Menu blur", px + 16, by2 + 1, WHITE);
            txt(g, Cfg.I.blur ? "Off = more FPS" : "Off (faster)", px + 16, by2 + 12, GREY);
            add(px - 3, by2 - 2, w - 10, 24, () -> { Cfg.I.blur = !Cfg.I.blur; Cfg.save(); }, null);
        } else if (tab == 1) {
            txt(g, "Music volume is separate", px, py, GREY);
            txt(g, "from game sounds.", px, py + 11, GREY);
            txt(g, "Folder: mods/music", px, py + 30, GREY);
            txt(g, "Formats: .mp3 .ogg .wav", px, py + 41, GREY);
        } else {
            txt(g, "Click a key, then press", px, py - 2, GREY);
            txt(g, "a new one. Esc cancels.", px, py + 9, GREY);
            KeyMapping[] km = {DiscoMod.K_PLAY, DiscoMod.K_PREV, DiscoMod.K_NEXT, DiscoMod.K_VUP, DiscoMod.K_VDN, DiscoMod.K_OPEN};
            String[] nm = {"Pause / Play", "Previous", "Next", "Volume up", "Volume down", "Open menu"};
            for (int i = 0; i < km.length; i++) {
                final KeyMapping m = km[i];
                int ry = py + 24 + i * 18;
                txt(g, nm[i], px, ry + 4, WHITE);
                String label = listening == m ? "..." : m.getTranslatedKeyMessage().getString();
                btn(g, mx, my, x + w - 62, ry, 54, 14, clip(label, 50), listening == m, () -> listening = m);
            }
        }
    }

    // ---------- input ----------
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return false;
        int mx = (int) e.x(), my = (int) e.y();
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (in(mx, my, h.x, h.y, h.w, h.h)) {
                if (h.drag != null) { dragging = h; h.drag.accept(mx); }
                if (h.click != null) h.click.run();
                return true;
            }
        }
        listening = null;
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging != null) { dragging.drag.accept((int) e.x()); return true; }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        boolean was = dragging != null;
        dragging = null;
        if (was) Cfg.save();
        return was;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.signum(sy);
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (listening != null) {
            if (k != GLFW.GLFW_KEY_ESCAPE) {
                listening.setKey(InputConstants.Type.KEYSYM.getOrCreate(k));
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
            }
            listening = null;
            return true;
        }
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (DiscoMod.K_PLAY.matches(e)) Player.toggle();
        else if (DiscoMod.K_PREV.matches(e)) Player.prev();
        else if (DiscoMod.K_NEXT.matches(e)) Player.next(false);
        else if (DiscoMod.K_VUP.matches(e)) { Player.setVolume(Cfg.I.volume + 5); Cfg.save(); }
        else if (DiscoMod.K_VDN.matches(e)) { Player.setVolume(Cfg.I.volume - 5); Cfg.save(); }
        else if (DiscoMod.K_OPEN.matches(e)) onClose();
        else return false;
        return true;
    }

    @Override
    public void onClose() {
        Cfg.save();
        super.onClose();
    }
}
