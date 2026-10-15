/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import cn.harryh.arkpets.ArkPets;
import cn.harryh.arkpets.animations.AnimData;
import cn.harryh.arkpets.animations.CompanionAnimations;
import cn.harryh.arkpets.utils.Logger;
import com.alibaba.fastjson2.JSONObject;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import javax.sound.sampled.AudioSystem;
import java.awt.Point;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Coordinates bounded activities, wardrobes, Mandarin audio, and synchronized dialogue. */
public final class CompanionRuntime {
    private final ArkPets pet;
    private final CompanionCatalog catalog;
    private final JSONObject character;
    private final Path controlsFile, statusFile;
    private final CompanionSpeechBubble bubble = new CompanionSpeechBubble();
    private final List<JSONObject> voiceBag = new ArrayList<>();
    private final List<String> wardrobeBag = new ArrayList<>();
    private final Deque<String> recentActivities = new ArrayDeque<>();
    private JSONObject controls;
    private Music voice;
    private FileChannel audioChannel;
    private FileLock audioLock;
    private long nextVoiceAt, nextPoll, nextActivityAt, nextWardrobeAt;
    private long modified = -1, activityStarted, activityDeadline, readUntil;
    private float voiceDuration = 1;
    private String variant = "", desiredVariant = "", command = "";
    private String lastVoice = "", lastVoiceTitle = "", lastVoiceText = "", lastError = "";
    private String lastSpeaker = "", lastSource = "";
    private String activity = "", lastActivity = "", restoreSkin, restoreMode;
    private int activityCount, wardrobeCount;
    private boolean wardrobeRequested;
    private boolean bubblePreview;

    private CompanionRuntime(ArkPets pet, Path file, String id) throws IOException {
        this.pet = pet;
        catalog = new CompanionCatalog(file);
        character = catalog.character(id);
        controlsFile = catalog.controls(id);
        statusFile = controlsFile.resolveSibling(id + ".status.json");
        poll();
    }

    public static CompanionRuntime attach(ArkPets pet) {
        String file = System.getProperty("arkpets.companion.catalog");
        String id = System.getProperty("arkpets.companion.character");
        if (file == null || id == null) return null;
        try { return new CompanionRuntime(pet, Path.of(file), id); }
        catch (Exception error) { Logger.error("Companion", "Companion initialization failed", error); return null; }
    }

    public void update() {
        long now = System.currentTimeMillis();
        try {
            if (now >= nextPoll) { nextPoll = now + 300; poll(); }
            if (controls == null) return;
            if (voice != null && !voice.isPlaying() && now > readUntil + 250) {
                stopVoice(false);
                readUntil = now + 1500;
                scheduleVoice(now);
            }
            if (voice == null && now >= readUntil) bubble.hide();
            pet.companionSpeaking = voice != null || now < readUntil;
            if (!activity.isEmpty() && now - activityStarted >= 350) {
                AnimData playing = pet.cha.getPlaying();
                if (playing == null || !playing.isStrict() || now >= activityDeadline) finishActivity(now);
            }
            boolean available = activity.isEmpty() && voice == null && now >= readUntil && pet.canCompanionAct();
            if (available && (wardrobeRequested || (controls.getBooleanValue("autoWardrobe") && now >= nextWardrobeAt))) {
                changeWardrobe(now); available = false;
            }
            if (available && controls.getBooleanValue("voiceEnabled") && nextVoiceAt > 0 && now >= nextVoiceAt) {
                playVoice(); available = false;
            }
            if (available && controls.getBooleanValue("autoActions") && now >= nextActivityAt) startActivity(now);
        } catch (Exception error) { reportError(error); nextActivityAt = now + 30_000; nextWardrobeAt = now + 60_000; }
    }

    private void poll() throws IOException {
        if (!Files.exists(controlsFile)) return;
        long timestamp = Files.getLastModifiedTime(controlsFile).toMillis();
        if (modified == timestamp) { writeStatus(); return; }
        JSONObject next = JSONObject.parseObject(Files.readString(controlsFile));
        CompanionPreferences.defaults(next);
        modified = timestamp;
        lastError = "";
        String requested = next.getString("skin") + "/" + next.getString("mode");
        if (!requested.equals(desiredVariant)) {
            activity = "";
            applyVariant(next.getString("skin"), next.getString("mode"));
            desiredVariant = requested;
        }
        boolean voiceDisabled = controls != null && controls.getBooleanValue("voiceEnabled") && !next.getBooleanValue("voiceEnabled");
        boolean voiceChanged = controls == null || !controls.getBooleanValue("voiceEnabled")
                || controls.getIntValue("voiceMinSeconds") != next.getIntValue("voiceMinSeconds")
                || controls.getIntValue("voiceMaxSeconds") != next.getIntValue("voiceMaxSeconds");
        boolean actionsChanged = changed(next, "autoActions", "activityMinSeconds", "activityMaxSeconds");
        boolean wardrobeChanged = changed(next, "autoWardrobe", "wardrobeMinSeconds", "wardrobeMaxSeconds");
        boolean playlistChanged = changed(next, "selectedVoiceIds");
        controls = next;
        if (playlistChanged) {
            voiceBag.clear();
            if (voice != null && !CompanionVoices.selected(character, controls).contains(lastVoice)) { stopVoice(true); readUntil = 0; }
            if (CompanionVoices.enabled(character, controls).isEmpty()) nextVoiceAt = 0;
            else if (nextVoiceAt == 0) scheduleVoice(System.currentTimeMillis());
        }
        if (voiceDisabled) { stopVoice(true); readUntil = 0; }
        if (voiceChanged && controls.getBooleanValue("voiceEnabled")) scheduleVoice(System.currentTimeMillis());
        if (voice != null) voice.setVolume(volume());
        if (!controls.getBooleanValue("bubbleEnabled")) bubble.hide();
        bubble.theme(controls.getString("bubbleTheme"));
        if (actionsChanged) nextActivityAt = after(System.currentTimeMillis(), "activity", 45, 120);
        if (wardrobeChanged) nextWardrobeAt = after(System.currentTimeMillis(), "wardrobe", 1800, 3600);
        if (!controls.getBooleanValue("autoActions") && !activity.isEmpty()) finishActivity(System.currentTimeMillis());
        String id = controls.getString("commandId");
        if (id != null && !id.equals(command)) {
            command = id;
            String action = Objects.requireNonNullElse(controls.getString("command"), "none");
            switch (action) {
                case "voice" -> { if (!activity.isEmpty()) finishActivity(System.currentTimeMillis()); playVoice(); }
                case "activity" -> { if (voice == null && System.currentTimeMillis() >= readUntil && pet.canCompanionAct()) startActivity(System.currentTimeMillis()); }
                case "wardrobe" -> wardrobeRequested = true;
                case "previewBubble" -> previewBubble();
                case "attack", "skill1", "skill2", "skill3" -> {
                    if (!activity.isEmpty()) finishActivity(System.currentTimeMillis());
                    pet.playCompanionSkill(action.equals("attack") ? 0 : Integer.parseInt(action.substring(5)));
                }
                case "exit" -> { bubble.dispose(); Gdx.app.exit(); }
                default -> {
                    if (action.startsWith("voice:")) {
                        if (!activity.isEmpty()) finishActivity(System.currentTimeMillis());
                        JSONObject requestedVoice = CompanionVoices.all(character).stream().filter(v -> action.substring(6).equals(v.getString("id"))).findFirst().orElse(null);
                        if (requestedVoice != null) { stopVoice(true); playVoice(requestedVoice); }
                    } else if (action.equals("stopVoice")) { stopVoice(true); readUntil = 0; scheduleVoice(System.currentTimeMillis()); }
                }
            }
        }
        writeStatus();
    }

    private boolean changed(JSONObject next, String... keys) {
        if (controls == null) return true;
        for (String key : keys) if (!Objects.equals(controls.get(key), next.get(key))) return true;
        return false;
    }

    private long after(long now, String prefix, int defaultMin, int defaultMax) {
        int min = Math.max(1, controls.containsKey(prefix + "MinSeconds") ? controls.getIntValue(prefix + "MinSeconds") : defaultMin);
        int max = Math.max(min, controls.containsKey(prefix + "MaxSeconds") ? controls.getIntValue(prefix + "MaxSeconds") : defaultMax);
        return now + ThreadLocalRandom.current().nextLong(min, (long) max + 1) * 1000;
    }

    private JSONObject skin(String id) {
        return character.getJSONArray("skins").toJavaList(JSONObject.class).stream()
                .filter(s -> id.equals(s.getString("id"))).findFirst().orElseThrow();
    }

    private void applyVariant(String skin, String mode) {
        JSONObject model = catalog.model(character, skin, mode);
        Path asset = catalog.resolve(model.getString("asset"));
        if (!asset.equals(Path.of(pet.config.character_asset).toAbsolutePath().normalize()))
            pet.changeCompanionModel(asset.toString(), model.getJSONObject("files"));
        variant = skin + "/" + mode;
    }

    private void startActivity(long now) {
        if (!activity.isEmpty() || !pet.canCompanionAct()) return;
        restoreSkin = controls.getString("skin"); restoreMode = controls.getString("mode");
        List<AnimData> daily = pet.companionDailyGestures();
        boolean battle = controls.getBooleanValue("battleActions") && (!"building".equals(restoreMode)
                || daily.isEmpty() || activityCount % 3 == 2 || ThreadLocalRandom.current().nextInt(100) < 45);
        AnimData chosen = null;
        String label = "日常";
        if (battle) {
            JSONObject models = skin(restoreSkin).getJSONObject("models");
            List<String> modes = List.of("battle-front", "battle-back").stream().filter(models::containsKey).toList();
            if (!modes.isEmpty()) {
                String mode = modes.get(ThreadLocalRandom.current().nextInt(modes.size()));
                applyVariant(restoreSkin, mode);
                List<AnimData> gestures = new ArrayList<>();
                for (int i = 0; i <= 3; i++) {
                    AnimData candidate = pet.companionSkill(i);
                    float length = CompanionAnimations.duration(candidate);
                    if (candidate != null && length > 0 && length <= 8.5f) gestures.add(candidate);
                }
                if (!gestures.isEmpty()) {
                    List<AnimData> fresh = gestures.stream().filter(g -> !g.name().equals(lastActivity)).toList();
                    if (!fresh.isEmpty()) gestures = fresh;
                    chosen = gestures.get(ThreadLocalRandom.current().nextInt(gestures.size()));
                    label = mode.equals("battle-front") ? "战斗正面" : "战斗背面";
                }
            }
        }
        if (chosen == null) {
            applyVariant(restoreSkin, restoreMode);
            daily = pet.companionDailyGestures();
            if (!daily.isEmpty()) {
                List<AnimData> fresh = daily.stream().filter(g -> !g.name().equals(lastActivity)).toList();
                if (!fresh.isEmpty()) daily = fresh;
                chosen = daily.get(ThreadLocalRandom.current().nextInt(daily.size()));
            }
        }
        if (chosen != null && pet.playCompanionGesture(chosen)) {
            activity = label + " · " + chosen.name();
            lastActivity = chosen.name(); activityStarted = System.currentTimeMillis();
            activityDeadline = activityStarted + Math.round((CompanionAnimations.duration(chosen) + 2) * 1000);
            activityCount++;
            recentActivities.addLast(activity);
            while (recentActivities.size() > 12) recentActivities.removeFirst();
        } else applyVariant(restoreSkin, restoreMode);
        nextActivityAt = after(now, "activity", 45, 120);
    }

    private void finishActivity(long now) {
        activity = "";
        applyVariant(controls.getString("skin"), controls.getString("mode"));
        pet.resetCompanionIdle();
        nextActivityAt = after(now, "activity", 45, 120);
    }

    private void changeWardrobe(long now) throws IOException {
        wardrobeRequested = false;
        List<JSONObject> all = character.getJSONArray("skins").toJavaList(JSONObject.class);
        if (all.size() > 1) {
            if (wardrobeBag.isEmpty()) { for (JSONObject item : all) wardrobeBag.add(item.getString("id")); Collections.shuffle(wardrobeBag); }
            String current = controls.getString("skin");
            String next = wardrobeBag.stream().filter(id -> !id.equals(current)).findFirst().orElse(null);
            if (next == null) { wardrobeBag.clear(); nextWardrobeAt = now + 1000; return; }
            wardrobeBag.remove(next);
            JSONObject latest = JSONObject.parseObject(Files.readString(controlsFile));
            String mode = latest.getString("mode");
            if (!skin(next).getJSONObject("models").containsKey(mode)) mode = skin(next).getJSONObject("models").containsKey("building") ? "building" : "battle-front";
            latest.put("skin", next); latest.put("mode", mode);
            atomic(controlsFile, latest);
            controls = latest;
            applyVariant(next, mode); desiredVariant = next + "/" + mode;
            modified = Files.getLastModifiedTime(controlsFile).toMillis();
            wardrobeCount++;
        }
        nextWardrobeAt = after(now, "wardrobe", 1800, 3600);
        writeStatus();
    }

    private float volume() { return Math.max(0, Math.min(1, controls.getFloatValue("volume"))); }
    private void scheduleVoice(long now) { nextVoiceAt = CompanionVoices.enabled(character, controls).isEmpty() ? 0 : after(now, "voice", 600, 1200); }

    private void playVoice() { playVoice(null); }

    private void playVoice(JSONObject requested) {
        if (voice != null || (requested == null && CompanionVoices.enabled(character, controls).isEmpty())) return;
        try {
            audioChannel = FileChannel.open(catalog.directory.resolve("runtime/voice.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            audioLock = audioChannel.tryLock();
            if (audioLock == null) { stopVoice(true); nextVoiceAt = System.currentTimeMillis() + 30_000; return; }
            if (requested == null && voiceBag.isEmpty()) {
                voiceBag.addAll(CompanionVoices.enabled(character, controls)); Collections.shuffle(voiceBag);
                if (voiceBag.size() > 1 && lastVoice.equals(voiceBag.get(0).getString("id"))) Collections.swap(voiceBag, 0, voiceBag.size() - 1);
            }
            JSONObject clip = requested != null ? requested : voiceBag.remove(0);
            lastVoice = clip.getString("id"); lastVoiceTitle = clip.getString("title"); lastVoiceText = clip.getString("text");
            lastSpeaker = Objects.requireNonNullElse(clip.getString("speakerName"), character.getString("name"));
            lastSource = Objects.requireNonNullElse(clip.getString("sourceName"), character.getString("name"));
            Path file = catalog.resolve(clip.getString("file"));
            try (var stream = AudioSystem.getAudioInputStream(file.toFile())) { voiceDuration = Math.max(1f, stream.getFrameLength() / stream.getFormat().getFrameRate()); }
            voice = Gdx.audio.newMusic(Gdx.files.absolute(file.toString()));
            bubblePreview = false;
            voice.setVolume(volume()); voice.setLooping(false); voice.play();
            readUntil = System.currentTimeMillis();
            if (controls.getBooleanValue("bubbleEnabled")) bubble.show(lastSpeaker, lastVoiceTitle, lastVoiceText,
                    controls.getString("bubbleTheme"), pet.companionHeadAnchor(), pet.config.window_style_topmost);
            writeStatus();
        } catch (Exception error) { stopVoice(true); nextVoiceAt = System.currentTimeMillis() + 60_000; reportError(error); }
    }

    private void previewBubble() {
        if (character.getJSONArray("voices").isEmpty()) return;
        JSONObject sample = character.getJSONArray("voices").getJSONObject(0);
        bubble.show(character.getString("name"), "气泡预览 · 无声音", sample.getString("text"), controls.getString("bubbleTheme"), pet.companionHeadAnchor(), pet.config.window_style_topmost);
        bubblePreview = true;
        readUntil = System.currentTimeMillis() + 7000;
    }

    public void updateBubbleAnchor(Point anchor) {
        float progress = voice != null ? voice.getPosition() / voiceDuration
                : bubblePreview ? 1 - Math.max(0, readUntil - System.currentTimeMillis()) / 7000f : 1;
        bubble.update(anchor, progress);
    }

    private void stopVoice(boolean hide) {
        if (voice != null) { voice.stop(); voice.dispose(); voice = null; }
        try { if (audioLock != null) audioLock.release(); } catch (IOException ignored) { }
        try { if (audioChannel != null) audioChannel.close(); } catch (IOException ignored) { }
        audioLock = null; audioChannel = null;
        if (hide) bubble.hide();
    }

    private void reportError(Exception error) {
        if (!error.toString().equals(lastError)) Logger.error("Companion", "Activity or voice failed", error);
        lastError = error.toString();
        try { writeStatus(); } catch (IOException ignored) { }
    }

    private static void atomic(Path file, JSONObject value) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(temp, value.toJSONString());
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void writeStatus() throws IOException {
        JSONObject status = new JSONObject();
        status.put("pid", ProcessHandle.current().pid());
        status.put("startedAt", ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L));
        status.put("character", character.getString("id")); status.put("catalogDirectory", catalog.directory.toString());
        status.put("variant", variant); status.put("gestures", pet.companionGestures());
        status.put("lastVoice", lastVoice); status.put("voiceTitle", lastVoiceTitle); status.put("voiceText", lastVoiceText);
        status.put("voiceSource", lastSource); status.put("voiceSpeaker", lastSpeaker);
        status.put("selectedVoiceCount", CompanionVoices.enabled(character, controls).size());
        status.put("voicePlaying", voice != null && voice.isPlaying()); status.put("voiceDuration", voiceDuration);
        status.put("playing", pet.cha.getPlaying() == null ? "" : pet.cha.getPlaying().name());
        status.put("nextVoiceAt", nextVoiceAt); status.put("nextActivityAt", nextActivityAt); status.put("nextWardrobeAt", nextWardrobeAt);
        status.put("activity", activity); status.put("activityCount", activityCount); status.put("wardrobeCount", wardrobeCount);
        status.put("recentActivities", List.copyOf(recentActivities)); status.put("bubble", bubble.status());
        status.put("petX", pet.plane.getX()); status.put("petY", pet.plane.getY());
        status.put("error", lastError); status.put("updatedAt", System.currentTimeMillis());
        atomic(statusFile, status);
    }

    public void dispose() { stopVoice(true); pet.companionSpeaking = false; bubble.dispose(); }
}
