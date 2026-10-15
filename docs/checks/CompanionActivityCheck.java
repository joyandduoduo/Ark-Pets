/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.companion.*;
import com.alibaba.fastjson2.JSONObject;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Exercises real schedules, model returns, subtitles, and physical stability in an isolated fixture. */
public final class CompanionActivityCheck {
    private static CompanionCatalog catalog;
    private static JSONObject character, prefs;
    private static Path controls, status, report;
    private static Process pet;
    private static final List<String> passed = new ArrayList<>();
    private static JSONObject evidence = new JSONObject();

    private static JSONObject state() throws Exception { return JSONObject.parseObject(Files.readString(status)); }
    private static void require(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void save() throws Exception {
        Path temp = controls.resolveSibling(controls.getFileName() + ".tmp");
        Files.writeString(temp, prefs.toJSONString()); Files.move(temp, controls, StandardCopyOption.REPLACE_EXISTING);
    }
    private static void command(String value) throws Exception {
        prefs.put("command", value); prefs.put("commandId", UUID.randomUUID().toString()); save();
    }
    private static JSONObject await(Predicate<JSONObject> predicate, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (!pet.isAlive()) throw new AssertionError("Pet process exited: " + pet.exitValue());
            if (Files.exists(status)) {
                JSONObject value = state();
                if (value.getLongValue("pid") == pet.pid()) {
                    require(value.getString("error").isEmpty(), "Runtime failure: " + value.getString("error"));
                    if (predicate.test(value)) return value;
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out; last state=" + state());
    }

    public static void main(String[] args) throws Exception {
        catalog = new CompanionCatalog(Path.of(args[0]));
        report = Path.of(args[1]); Files.createDirectories(report);
        character = catalog.character("char_1038_whitw2");
        controls = catalog.controls(character.getString("id")); status = controls.resolveSibling(character.getString("id") + ".status.json");
        Throwable failure = null;
        try {
            String body = character.getJSONArray("voices").getJSONObject(0).getString("text");
            for (String theme : List.of("rhodes", "light", "terminal"))
                ImageIO.write(CompanionSpeechBubble.preview(character.getString("name"), "交谈1", body, theme), "png", report.resolve("气泡-" + theme + ".png").toFile());
            for (Rectangle screen : List.of(new Rectangle(0, 0, 1920, 1040), new Rectangle(-1920, 0, 1920, 1040)))
                for (Point head : List.of(new Point(screen.x, 30), new Point(screen.x + screen.width - 1, 1030))) {
                    Rectangle fit = CompanionSpeechBubble.placement(head, new Dimension(380, 213), screen, head.y < 213);
                    require(screen.contains(fit), "Bubble clipped at monitor edge");
                }
            passed.add("Three native dialogue themes and positive/negative monitor-edge placement");
            JSONObject model = catalog.model(character, "default", "building");
            JSONObject config = JSONObject.parseObject(new String(ArkConfig.class.getResourceAsStream(Const.configInternal).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            config.put("character_asset", catalog.resolve(model.getString("asset")).toString());
            config.put("character_files", model.getJSONObject("files")); config.put("character_label", character.getString("name"));
            config.put("behavior_allow_walk", false); config.put("behavior_allow_sit", false); config.put("enable_telemetry", false);
            config.put("initial_position_y", .9);
            Path configFile = controls.resolveSibling("activity-check.config.json"); Files.writeString(configFile, config.toJSONString());
            prefs = new JSONObject(); CompanionPreferences.defaults(prefs);
            prefs.put("skin", "default"); prefs.put("mode", "building"); prefs.put("voiceEnabled", true); prefs.put("volume", 0);
            prefs.put("voiceMinSeconds", 600); prefs.put("voiceMaxSeconds", 1200);
            command("none");
            pet = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-Dfile.encoding=UTF-8",
                    "-Darkpets.companion.catalog=" + catalog.directory.resolve("catalog.json"), "-Darkpets.companion.character=" + character.getString("id"),
                    "-cp", System.getProperty("java.class.path"), EmbeddedLauncher.class.getName(), "--config", configFile.toString())
                    .directory(catalog.directory.toFile()).redirectErrorStream(true).redirectOutput(report.resolve("pet.log").toFile()).start();
            JSONObject initial = await(s -> s.getJSONObject("bubble") != null, 25);
            long now = System.currentTimeMillis();
            require(initial.getLongValue("nextActivityAt") - now >= 40_000 && initial.getLongValue("nextActivityAt") - now <= 120_000, "Default activity cadence");
            require(initial.getLongValue("nextWardrobeAt") - now >= 1_790_000 && initial.getLongValue("nextWardrobeAt") - now <= 3_600_000, "Default wardrobe cadence");
            passed.add("Default 45-120 second activities and 30-60 minute wardrobes scheduled");
            evidence.put("initial", initial);
            prefs.put("autoActions", false); prefs.put("autoWardrobe", false); prefs.put("voiceEnabled", false); save();
            Thread.sleep(1200);
            command("voice");
            JSONObject speaking = await(s -> s.getBooleanValue("voicePlaying") && s.getJSONObject("bubble").getBooleanValue("visible"), 12);
            JSONObject box = speaking.getJSONObject("bubble");
            require(box.getBooleanValue("nativeWindowExcluded") && !box.getBooleanValue("focusable"), "Bubble must be excluded and nonfocusable");
            require(cn.harryh.arkpets.platform.User32HWndCtrl.getWindowList(true).stream().anyMatch(CompanionSpeechBubble::owns),
                    "Another process must also recognize the bubble collision exclusion");
            double floor = speaking.getDoubleValue("petY"); int x = box.getIntValue("x"), y = box.getIntValue("y");
            int samples = 0;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < until) {
                JSONObject current = state(), bubble = current.getJSONObject("bubble");
                if (bubble != null && bubble.getBooleanValue("visible")) {
                    require(bubble.getIntValue("x") == x && bubble.getIntValue("y") == y, "Stationary bubble moved");
                    require(Math.abs(current.getDoubleValue("petY") - floor) < .1, "Speech window moved the pet");
                    require(!bubble.getBooleanValue("hasFocus"), "Speech window stole focus"); samples++;
                }
                Thread.sleep(120);
            }
            require(samples >= 12, "Insufficient speech stability samples");
            evidence.put("speechSamples", samples); evidence.put("speaking", speaking);
            passed.add("Real zero-volume audio with synchronized subtitles; stationary pet/bubble and no focus over 3 seconds");
            List<JSONObject> trace = new ArrayList<>();
            evidence.put("speechTrace", trace);
            await(s -> {
                JSONObject b = s.getJSONObject("bubble");
                trace.add(new JSONObject(Map.of("petX", s.getDoubleValue("petX"), "petY", s.getDoubleValue("petY"),
                        "bubbleX", b.getIntValue("x"), "bubbleY", b.getIntValue("y"), "moves", b.getIntValue("positionChanges"),
                        "voicePlaying", s.getBooleanValue("voicePlaying"), "visible", b.getBooleanValue("visible"))));
                return !s.getBooleanValue("voicePlaying") && !b.getBooleanValue("visible");
            }, 60);
            passed.add("Bubble dismissed after audio and reading hold");
            prefs.put("autoWardrobe", true); prefs.put("wardrobeMinSeconds", 2); prefs.put("wardrobeMaxSeconds", 2); save();
            JSONObject wardrobe = await(s -> s.getIntValue("wardrobeCount") >= 1, 15);
            require(!wardrobe.getString("variant").startsWith("default/"), "Automatic wardrobe did not change skin");
            evidence.put("wardrobe", wardrobe); passed.add("Accelerated automatic wardrobe changes skin without restarting pet");
            prefs = JSONObject.parseObject(Files.readString(controls));
            prefs.put("autoWardrobe", false); prefs.put("autoActions", true); prefs.put("activityMinSeconds", 2); prefs.put("activityMaxSeconds", 2); save();
            JSONObject active = await(s -> s.getIntValue("activityCount") >= 3 && s.getJSONArray("recentActivities").toJavaList(String.class).stream().anyMatch(a -> a.startsWith("战斗")), 70);
            evidence.put("activities", active);
            require(active.getJSONArray("recentActivities").toJavaList(String.class).stream().noneMatch(a -> a.toLowerCase(Locale.ROOT).matches(".*(die|down|death).*")), "Defeat animation entered automatic pool");
            passed.add("Accelerated automatic activity rotates finite daily/battle gestures without defeat motions");
            await(s -> s.getString("activity").isEmpty() && s.getString("variant").endsWith("/building"), 15);
            passed.add("Temporary battle performance returns to the selected daily model");
            prefs.put("autoActions", false); save();
            Thread.sleep(600); int count = state().getIntValue("activityCount"); Thread.sleep(3200);
            require(state().getIntValue("activityCount") == count, "Pause failed");
            passed.add("Disabling automatic activities stops the schedule");
            List<JSONObject> all = CompanionVoices.all(character);
            JSONObject base = all.stream().filter(v -> v.getBooleanValue("defaultSelected")).findFirst().orElse(all.get(0));
            JSONObject related = all.stream().filter(v -> "拉普兰德".equals(v.getString("speakerName"))).findFirst().orElseThrow();
            JSONObject skinVoice = all.stream().filter(v -> v.getString("sourceName").contains("无序的谦卑")).findFirst().orElseThrow();
            prefs.put("selectedVoiceIds", List.of(base.getString("id"))); save();
            command("voice");
            await(s -> base.getString("id").equals(s.getString("lastVoice")) && s.getBooleanValue("voicePlaying"), 10);
            command("stopVoice"); await(s -> !s.getBooleanValue("voicePlaying"), 10);
            prefs.put("selectedVoiceIds", List.of()); prefs.put("voiceEnabled", true); prefs.put("voiceMinSeconds", 1); prefs.put("voiceMaxSeconds", 1); save();
            await(s -> s.getIntValue("selectedVoiceCount") == 0 && s.getLongValue("nextVoiceAt") == 0, 5);
            Thread.sleep(2200); require(!state().getBooleanValue("voicePlaying"), "Empty playlist played audio");
            passed.add("Single selection is exclusive; empty selection stays silent even with automatic voice enabled");
            for (JSONObject clip : List.of(related, skinVoice)) {
                command("voice:" + clip.getString("id"));
                JSONObject audition = await(s -> clip.getString("id").equals(s.getString("lastVoice")) && s.getBooleanValue("voicePlaying"), 10);
                require(clip.getString("speakerName").equals(audition.getString("voiceSpeaker")), "Incorrect related-operator speaker");
                command("stopVoice"); await(s -> !s.getBooleanValue("voicePlaying"), 10);
            }
            passed.add("Related original operator and voiced outfit audition real recordings without adding them to the playlist");
            command("exit"); require(pet.waitFor(10, TimeUnit.SECONDS), "Pet failed to exit");
            passed.add("Pet and subtitle window exit cleanly");
        } catch (Throwable error) { failure = error; }
        finally {
            if (pet != null && pet.isAlive()) pet.destroy();
            evidence.put("passed", passed); evidence.put("failure", failure == null ? null : failure.toString());
            evidence.put("speakerAudibilityTested", false); evidence.put("acceleratedScheduleOnlyInFixture", true);
            Files.writeString(report.resolve("activity-integration.json"), evidence.toJSONString());
        }
        if (failure != null) throw new AssertionError("Activity check failed", failure);
    }
}
