package dev.jax.skylyrics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

public final class SkyLyricsMod implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("SkyLyrics");
    private static final String CATEGORY = "key.categories.skylyrics";
    private static final int[] PALETTE = {0xFFD6E8, 0xCDE8FF, 0xE0D4FF, 0xD4FFE8, 0xFFF1C2, 0xFFD9C7};
    private static final int MAX_POPS = 8;

    private static final String SAMPLE_NAME = "sample.lrc";
    private static final String SAMPLE_TEXT = String.join("\n",
            "[ti:Sample Song]",
            "[ar:SkyLyrics]",
            "[00:02.00]Welcome to the sky show",
            "[00:05.50]Your words float up here",
            "[00:09.00]Drop your own lyric files in this folder",
            "[00:13.00]Plain .txt files work too",
            "[00:17.00]Press K again to stop",
            "");

    private final Random rng = new Random();
    private final LyricsPlayer player = new LyricsPlayer();
    private final SkySlots slots = new SkySlots(rng);
    private final List<LyricsParser.Song> songs = new ArrayList<>();
    private final List<Pop> pops = new ArrayList<>();

    private Settings settings = new Settings();
    private Path dir;
    private int songIndex;
    private KeyBinding playBind;
    private KeyBinding skipBind;
    private KeyBinding nextBind;
    private long lastNanos;
    private String banner = "";
    private double bannerAge = 99.0;

    @Override
    @SuppressWarnings("deprecation")
    public void onInitializeClient() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("skylyrics");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.error("Could not create {}: {}", dir, e.toString());
        }
        settings = Settings.load(dir.resolve("config.json"));
        writeSampleIfEmpty();
        loadSongs();

        playBind = bind("play", GLFW.GLFW_KEY_K);
        skipBind = bind("skip", GLFW.GLFW_KEY_N);
        nextBind = bind("next", GLFW.GLFW_KEY_L);

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::onHud);
        LOG.info("SkyLyrics loaded: {} song file(s) in {}", songs.size(), dir);
    }

    private static KeyBinding bind(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.skylyrics." + id, InputUtil.Type.KEYSYM, key, CATEGORY));
    }

    // ------------------------------------------------------------------ files

    private void writeSampleIfEmpty() {
        try (Stream<Path> s = Files.list(dir)) {
            boolean any = s.anyMatch(SkyLyricsMod::isSongFile);
            if (!any) {
                Files.writeString(dir.resolve(SAMPLE_NAME), SAMPLE_TEXT, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.error("Could not write sample file: {}", e.toString());
        }
    }

    private static boolean isSongFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(p) && (n.endsWith(".lrc") || n.endsWith(".txt"));
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static List<String> readLines(Path p) throws IOException {
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException badUtf8) {
            return Files.readAllLines(p, StandardCharsets.ISO_8859_1);
        }
    }

    private void loadSongs() {
        String keep = songs.isEmpty() ? null : songs.get(Math.min(songIndex, songs.size() - 1)).file;
        songs.clear();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(SkyLyricsMod::isSongFile).sorted().forEach(files::add);
        } catch (IOException e) {
            LOG.error("Could not list {}: {}", dir, e.toString());
        }
        for (Path p : files) {
            try {
                songs.add(LyricsParser.parse(p.getFileName().toString(), baseName(p), readLines(p), settings.interval));
            } catch (IOException | RuntimeException e) {
                LOG.error("Skipping {}: {}", p.getFileName(), e.toString());
            }
        }
        songIndex = 0;
        if (keep != null) {
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).file.equals(keep)) {
                    songIndex = i;
                }
            }
        }
    }

    // ------------------------------------------------------------------ controls

    private void say(String text) {
        banner = text;
        bannerAge = 0.0;
    }

    private void fadeOutPops() {
        for (Pop p : pops) {
            p.age = Math.max(p.age, p.life - SkyRenderer.FADE_OUT);
        }
    }

    private void togglePlay() {
        if (player.isPlaying()) {
            player.stop();
            fadeOutPops();
            say("Lyrics stopped");
            return;
        }
        loadSongs();
        if (songs.isEmpty()) {
            say("No lyric files in config/skylyrics");
            return;
        }
        LyricsParser.Song song = songs.get(songIndex);
        if (song.cues.isEmpty()) {
            say(song.title + " has no lyric lines");
            return;
        }
        player.start(song);
        if (settings.showTitle) {
            say("\u266a " + song.title + (song.artist.isEmpty() ? "" : " - " + song.artist));
        }
    }

    private void nextSong() {
        player.stop();
        fadeOutPops();
        loadSongs();
        if (songs.isEmpty()) {
            say("No lyric files in config/skylyrics");
            return;
        }
        songIndex = (songIndex + 1) % songs.size();
        say("Song " + (songIndex + 1) + "/" + songs.size() + ": " + songs.get(songIndex).title);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            player.stop();
            pops.clear();
            while (playBind.wasPressed()) { }
            while (skipBind.wasPressed()) { }
            while (nextBind.wasPressed()) { }
            return;
        }
        while (playBind.wasPressed()) {
            togglePlay();
        }
        while (skipBind.wasPressed()) {
            player.skip();
        }
        while (nextBind.wasPressed()) {
            nextSong();
        }
    }

    // ------------------------------------------------------------------ frame

    private void spawn(MinecraftClient mc, LyricsParser.Cue cue) {
        int w = mc.getWindow().getScaledWidth();
        int maxPx = Math.max(60, (int) (w * 0.62 / settings.textScale));
        List<String> lines = TextWrap.wrap(cue.text, maxPx, s -> mc.textRenderer.getWidth(s));
        int slot = slots.nextIndex();
        double[] spot = slots.spot(slot, mc.player.getYaw(), mc.player.getPitch());
        int rgb = PALETTE[rng.nextInt(PALETTE.length)];
        pops.add(new Pop(lines, rgb, settings.lifetime, slot, spot[0], spot[1]));
        while (pops.size() > MAX_POPS) {
            pops.remove(0);
        }
    }

    private void onHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) {
            return;
        }

        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.016 : Math.min(0.1, (now - lastNanos) / 1.0e9);
        lastNanos = now;

        bannerAge += dt;
        for (LyricsParser.Cue cue : player.advance(dt)) {
            spawn(mc, cue);
        }
        for (Pop p : pops) {
            p.age += dt;
        }
        pops.removeIf(p -> p.age >= p.life);

        if (mc.options.hudHidden) {
            return;
        }
        SkyRenderer.render(ctx, mc, pops, settings);
        SkyRenderer.banner(ctx, mc, banner, bannerAge);
    }
}
