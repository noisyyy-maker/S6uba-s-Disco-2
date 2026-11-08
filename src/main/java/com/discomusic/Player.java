package com.discomusic;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Streams music through its own OpenAL source, so its volume is completely
 * separate from Minecraft's sound sliders. One small background thread, 4 small buffers.
 */
public final class Player {
    private static final Logger LOG = LoggerFactory.getLogger("discomusic");
    private static final Random R = new Random();

    public static List<Track> lib = new ArrayList<>();
    public static List<Track> queue = new ArrayList<>();
    public static List<Track> ctx = lib;
    public static Track current;
    public static volatile boolean playing, paused, shuffle, repeat;
    public static volatile double pos;
    public static volatile String error;
    private static volatile float gain = 0.6f;
    private static volatile int gen;
    private static volatile double seekReq = -1;

    private Player() {}

    public static synchronized void refresh() {
        boolean wasLib = ctx == lib;
        lib = Track.scan();
        if (wasLib || ctx == null) ctx = lib;
    }

    public static void setVolume(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        Cfg.I.volume = pct;
        gain = pct / 100f;
    }

    public static synchronized void play(Track t, List<Track> context) {
        if (context != null) ctx = context;
        current = t; paused = false; playing = true; pos = 0; seekReq = -1; error = null;
        final int g = ++gen;
        Thread th = new Thread(() -> run(t, g), "disco-audio");
        th.setDaemon(true);
        th.setPriority(3); // never compete with the render thread
        th.start();
    }

    public static synchronized void stop() { gen++; playing = false; }

    public static synchronized void toggle() {
        if (current == null) { if (!lib.isEmpty()) play(lib.get(0), lib); }
        else if (!playing) play(current, null);
        else paused = !paused;
    }

    public static void seek(double s) { if (playing) seekReq = Math.max(0, s); }

    public static synchronized void next(boolean auto) {
        Track t = null;
        if (auto && repeat && current != null) t = current;
        else if (!queue.isEmpty()) t = queue.remove(0);
        else if (!ctx.isEmpty()) {
            if (shuffle) t = ctx.get(R.nextInt(ctx.size()));
            else t = ctx.get((ctx.indexOf(current) + 1) % ctx.size());
        }
        if (t != null) play(t, null); else stop();
    }

    public static synchronized void prev() {
        if (current == null || ctx.isEmpty()) return;
        if (pos > 3) { if (playing) seekReq = 0; else play(current, null); return; }
        int i = ctx.indexOf(current);
        play(ctx.get(i <= 0 ? ctx.size() - 1 : i - 1), null);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private static int fill(Dec d, ShortBuffer pcm, int buf, int fmt) {
        int n = d.read(pcm);
        if (n > 0) AL10.alBufferData(buf, fmt, pcm, d.rate);
        return n;
    }

    private static void run(Track t, int g) {
        Dec d = null;
        int src = 0;
        long dev = 0, ctx = 0;
        int[] bufs = new int[4];
        ShortBuffer pcm = null;
        boolean natural = false;
        try {
            d = Dec.open(t.path);
            t.duration = d.seconds;
            if (d.channels < 1 || d.channels > 2) throw new IllegalStateException("only mono/stereo supported");
            int fmt = d.channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            // Own audio device + context on this thread. Minecraft's context is thread-local,
            // so without this every OpenAL call from here is silently ignored.
            dev = ALC10.alcOpenDevice((ByteBuffer) null);
            if (dev == 0) throw new IllegalStateException("no audio device");
            ctx = ALC10.alcCreateContext(dev, (IntBuffer) null);
            if (ctx == 0) throw new IllegalStateException("no audio context");
            ALCCapabilities ac = ALC.createCapabilities(dev);
            if (!ac.ALC_EXT_thread_local_context) throw new IllegalStateException("audio driver lacks thread contexts");
            if (!EXTThreadLocalContext.alcSetThreadContext(ctx)) throw new IllegalStateException("can't bind audio context");
            AL.createCapabilities(ac);
            AL10.alGetError();
            src = AL10.alGenSources();
            if (src == 0 || AL10.alGetError() != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL source error");
            for (int i = 0; i < bufs.length; i++) bufs[i] = AL10.alGenBuffers();
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // not positional: plays "in your head"
            pcm = MemoryUtil.memAllocShort(16384 * d.channels);

            ArrayDeque<Integer> counts = new ArrayDeque<>();
            double base = 0;
            boolean eof = false;
            int inFlight = 0;
            for (int b : bufs) {
                int n = fill(d, pcm, b, fmt);
                if (n <= 0) { eof = true; break; }
                AL10.alSourceQueueBuffers(src, b);
                counts.add(n); inFlight++;
            }
            AL10.alSourcef(src, AL10.AL_GAIN, gain);
            AL10.alSourcePlay(src);

            while (g == gen) {
                AL10.alSourcef(src, AL10.AL_GAIN, gain);

                double sr = seekReq;
                if (sr >= 0) {
                    seekReq = -1;
                    AL10.alSourceStop(src);
                    int q = AL10.alGetSourcei(src, AL10.AL_BUFFERS_QUEUED);
                    while (q-- > 0) AL10.alSourceUnqueueBuffers(src);
                    counts.clear(); inFlight = 0; eof = false;
                    base = sr;
                    d.seek(sr);
                    for (int b : bufs) {
                        int n = fill(d, pcm, b, fmt);
                        if (n <= 0) { eof = true; break; }
                        AL10.alSourceQueueBuffers(src, b);
                        counts.add(n); inFlight++;
                    }
                    if (!paused && inFlight > 0) AL10.alSourcePlay(src);
                    continue;
                }

                int state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE);
                if (paused) {
                    if (state == AL10.AL_PLAYING) AL10.alSourcePause(src);
                    pos = base + AL10.alGetSourcef(src, AL11.AL_SEC_OFFSET);
                    sleep(40);
                    continue;
                }
                if (state == AL10.AL_PAUSED) AL10.alSourcePlay(src);

                int pr = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED);
                while (pr-- > 0) {
                    int b = AL10.alSourceUnqueueBuffers(src);
                    inFlight--;
                    Integer c = counts.poll();
                    if (c != null) base += c / (double) d.rate;
                    if (!eof) {
                        int n = fill(d, pcm, b, fmt);
                        if (n <= 0) eof = true;
                        else { AL10.alSourceQueueBuffers(src, b); counts.add(n); inFlight++; }
                    }
                }
                if (inFlight == 0 && eof) { natural = true; break; }

                state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING && inFlight > 0) AL10.alSourcePlay(src); // buffer underrun recovery
                pos = base + AL10.alGetSourcef(src, AL11.AL_SEC_OFFSET);
                sleep(25);
            }
        } catch (Throwable e) {
            LOG.error("Could not play {}", t.file, e);
            if (g == gen) { error = t.file + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage(); playing = false; }
        } finally {
            try {
                if (src != 0) {
                    AL10.alSourceStop(src);
                    AL10.alSourcei(src, AL10.AL_BUFFER, 0);
                    AL10.alDeleteSources(src);
                }
                for (int b : bufs) if (b != 0) AL10.alDeleteBuffers(b);
            } catch (Throwable ignored) {}
            try {
                if (ctx != 0) { EXTThreadLocalContext.alcSetThreadContext(0L); ALC10.alcDestroyContext(ctx); }
                if (dev != 0) ALC10.alcCloseDevice(dev);
            } catch (Throwable ignored) {}
            if (pcm != null) MemoryUtil.memFree(pcm);
            if (d != null) d.close();
        }
        if (natural) synchronized (Player.class) { if (g == gen) next(true); }
    }

    // ---------- decoders ----------
    abstract static class Dec {
        int channels, rate;
        double seconds = -1;
        abstract int read(ShortBuffer b);
        abstract void seek(double s);
        abstract void close();

        static Dec open(Path p) throws IOException {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".mp3")) return new Mp3(p);
            return n.endsWith(".wav") ? new Wav(p) : new Ogg(p);
        }
    }

    /** OGG Vorbis via the stb_vorbis that Minecraft already ships (no extra libraries). */
    static final class Ogg extends Dec {
        long h;
        ByteBuffer data;

        Ogg(Path p) throws IOException {
            data = MemoryUtil.memAlloc((int) Files.size(p));
            try (FileChannel c = FileChannel.open(p)) { while (data.hasRemaining() && c.read(data) >= 0) { } }
            data.flip();
            try (MemoryStack st = MemoryStack.stackPush()) {
                IntBuffer err = st.mallocInt(1);
                h = STBVorbis.stb_vorbis_open_memory(data, err, null);
                if (h == 0) { MemoryUtil.memFree(data); throw new IOException("bad ogg (" + err.get(0) + ")"); }
                STBVorbisInfo info = STBVorbisInfo.malloc(st);
                STBVorbis.stb_vorbis_get_info(h, info);
                channels = info.channels();
                rate = info.sample_rate();
                seconds = STBVorbis.stb_vorbis_stream_length_in_seconds(h);
            }
        }

        int read(ShortBuffer b) {
            b.clear();
            int n = STBVorbis.stb_vorbis_get_samples_short_interleaved(h, channels, b);
            b.limit(n * channels).position(0);
            return n;
        }

        void seek(double s) { STBVorbis.stb_vorbis_seek(h, (int) (s * rate)); }

        void close() { STBVorbis.stb_vorbis_close(h); MemoryUtil.memFree(data); }
    }

    /** 16-bit PCM WAV, streamed from disk. */
    static final class Wav extends Dec {
        final FileChannel ch;
        long start, len, posBytes;
        final ByteBuffer scratch;

        Wav(Path p) throws IOException {
            ch = FileChannel.open(p);
            ByteBuffer hd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            long off = 12;
            int bits = 0;
            while (off + 8 <= ch.size()) {
                hd.clear().limit(8);
                ch.read(hd, off);
                hd.flip();
                int id = hd.getInt();
                long size = hd.getInt() & 0xFFFFFFFFL;
                if (id == 0x20746d66) { // "fmt "
                    hd.clear().limit(16);
                    ch.read(hd, off + 8);
                    hd.flip();
                    int tag = hd.getShort();
                    channels = hd.getShort();
                    rate = hd.getInt();
                    hd.getInt(); hd.getShort();
                    bits = hd.getShort();
                    if (tag != 1 || bits != 16) { ch.close(); throw new IOException("only 16-bit PCM wav"); }
                } else if (id == 0x61746164) { // "data"
                    start = off + 8;
                    len = Math.min(size, ch.size() - start);
                    break;
                }
                off += 8 + size + (size & 1);
            }
            if (rate == 0 || len == 0) { ch.close(); throw new IOException("bad wav"); }
            seconds = len / (double) (rate * channels * 2);
            scratch = ByteBuffer.allocate(16384 * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        }

        int read(ShortBuffer b) {
            b.clear();
            try {
                int want = (int) Math.min(scratch.capacity(), len - posBytes);
                if (want <= 0) { b.limit(0); return 0; }
                scratch.clear().limit(want);
                int got = ch.read(scratch, start + posBytes);
                if (got <= 0) { b.limit(0); return 0; }
                posBytes += got;
                scratch.flip();
                int frames = (got / 2) / channels;
                b.limit(frames * channels);
                for (int i = 0; i < frames * channels; i++) b.put(scratch.getShort());
                b.position(0);
                return frames;
            } catch (IOException e) { b.limit(0); return 0; }
        }

        void seek(double s) {
            long frame = (long) (s * rate);
            posBytes = Math.min(len, frame * channels * 2L);
        }

        void close() { try { ch.close(); } catch (IOException ignored) {} }
    }

    /** MP3 via the bundled JLayer decoder, streamed frame by frame. */
    static final class Mp3 extends Dec {
        final Path path;
        Bitstream bs;
        Decoder dec;
        short[] pending;
        float msPerFrame = 26f;

        Mp3(Path p) throws IOException {
            path = p;
            openStream();
            try {
                Header h = bs.readFrame();
                if (h == null) throw new IOException("bad mp3");
                msPerFrame = h.ms_per_frame();
                seconds = h.total_ms((int) Files.size(p)) / 1000.0; // estimate (exact for constant bitrate)
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                bs.closeFrame();
                channels = dec.getOutputChannels();
                rate = dec.getOutputFrequency();
                pending = Arrays.copyOf(sb.getBuffer(), sb.getBufferLength());
            } catch (Exception e) {
                throw new IOException("bad mp3: " + e.getMessage());
            }
        }

        private void openStream() throws IOException {
            bs = new Bitstream(new java.io.BufferedInputStream(Files.newInputStream(path), 65536));
            dec = new Decoder();
        }

        private short[] nextFrame() {
            if (pending != null) { short[] t = pending; pending = null; return t; }
            try {
                Header h = bs.readFrame();
                if (h == null) return null;
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                bs.closeFrame();
                return Arrays.copyOf(sb.getBuffer(), sb.getBufferLength());
            } catch (Exception e) { return null; }
        }

        int read(ShortBuffer b) {
            b.clear();
            int need = 1152 * channels;
            while (b.remaining() >= need) {
                if (pending != null) { b.put(pending); pending = null; continue; }
                try {
                    Header h = bs.readFrame();
                    if (h == null) break;
                    SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                    bs.closeFrame();
                    b.put(sb.getBuffer(), 0, sb.getBufferLength()); // no extra copy, no garbage
                } catch (Exception e) { break; }
            }
            int n = b.position() / channels;
            b.limit(b.position()).position(0);
            return n;
        }

        void seek(double s) {
            try {
                close();
                openStream();
                pending = null;
                int skip = (int) (s * 1000.0 / msPerFrame);
                for (int i = 0; i < skip; i++) {
                    if (bs.readFrame() == null) break;
                    bs.closeFrame();
                }
            } catch (Exception ignored) {}
        }

        void close() { try { bs.close(); } catch (Exception ignored) {} }
    }
}
