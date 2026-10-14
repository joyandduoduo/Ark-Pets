/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import cn.harryh.arkpets.ArkPets;
import cn.harryh.arkpets.utils.Logger;
import com.alibaba.fastjson2.JSONObject;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Applies character wardrobe and voice requests on the render thread. */
public final class CompanionRuntime {
    private final ArkPets pet;
    private final CompanionCatalog catalog;
    private final JSONObject character;
    private final Path controlsFile;
    private final Path statusFile;
    private final List<JSONObject> voiceBag = new ArrayList<>();
    private JSONObject controls;
    private Music voice;
    private FileChannel audioChannel;
    private FileLock audioLock;
    private long nextVoiceAt;
    private long nextPoll;
    private long modified = -1;
    private String variant = "";
    private String command = "";
    private String lastVoice = "";
    private String lastVoiceTitle = "";
    private String lastVoiceText = "";
    private String lastError = "";

    private CompanionRuntime(ArkPets pet, Path file, String id) throws IOException {
        this.pet = pet;
        catalog = new CompanionCatalog(file);
        character = catalog.character(id);
        controlsFile = catalog.controls(id);
        statusFile = controlsFile.resolveSibling(id + ".status.json");
        nextVoiceAt = System.currentTimeMillis() + 600_000;
        poll();
    }

    public static CompanionRuntime attach(ArkPets pet) {
        String file = System.getProperty("arkpets.companion.catalog");
        String id = System.getProperty("arkpets.companion.character");
        if (file == null || id == null) return null;
        try { return new CompanionRuntime(pet, Path.of(file), id); }
        catch (Exception e) { Logger.error("Companion", "陪伴模块载入失败", e); return null; }
    }

    public void update() {
        long now = System.currentTimeMillis();
        if (now >= nextPoll) {
            nextPoll = now + 500;
            try { poll(); }
            catch (Exception e) { reportError(e); }
        }
        if (voice != null && !voice.isPlaying()) {
            stopVoice();
            schedule(now);
        }
        if (controls != null && controls.getBooleanValue("voiceEnabled") && now >= nextVoiceAt && voice == null)
            playVoice();
    }

    private void poll() throws IOException {
        if (!Files.exists(controlsFile)) return;
        long timestamp = Files.getLastModifiedTime(controlsFile).toMillis();
        if (modified == timestamp) { writeStatus(); return; }
        JSONObject next = JSONObject.parseObject(Files.readString(controlsFile));
        modified = timestamp;
        lastError = "";
        String requested = next.getString("skin") + "/" + next.getString("mode");
        if (!requested.equals(variant)) {
            JSONObject model = catalog.model(character, next.getString("skin"), next.getString("mode"));
            String asset = catalog.resolve(model.getString("asset")).toString();
            if (!Path.of(asset).toAbsolutePath().normalize().equals(Path.of(pet.config.character_asset).toAbsolutePath().normalize()))
                pet.changeCompanionModel(asset, model.getJSONObject("files"));
            variant = requested;
        }
        boolean wasEnabled = controls != null && controls.getBooleanValue("voiceEnabled");
        controls = next;
        if (!controls.getBooleanValue("voiceEnabled")) stopVoice();
        if (!wasEnabled && controls.getBooleanValue("voiceEnabled")) schedule(System.currentTimeMillis());
        if (voice != null) voice.setVolume(volume());
        String nextCommand = controls.getString("commandId");
        if (nextCommand != null && !nextCommand.equals(command)) {
            command = nextCommand;
            switch (Objects.requireNonNullElse(controls.getString("command"), "none")) {
                case "voice" -> playVoice();
                case "attack" -> pet.playCompanionSkill(0);
                case "skill1" -> pet.playCompanionSkill(1);
                case "skill2" -> pet.playCompanionSkill(2);
                case "skill3" -> pet.playCompanionSkill(3);
                case "exit" -> Gdx.app.exit();
                default -> { }
            }
        }
        writeStatus();
    }

    private float volume() {
        return Math.max(0, Math.min(1, controls.getFloatValue("volume")));
    }

    private void schedule(long now) {
        int min = Math.max(30, controls.getIntValue("voiceMinSeconds"));
        int max = Math.max(min, controls.getIntValue("voiceMaxSeconds"));
        nextVoiceAt = now + ThreadLocalRandom.current().nextLong(min, (long) max + 1) * 1000;
    }

    private void playVoice() {
        if (voice != null || character.getJSONArray("voices").isEmpty()) return;
        try {
            audioChannel = FileChannel.open(catalog.directory.resolve("runtime/voice.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            audioLock = audioChannel.tryLock();
            if (audioLock == null) { stopVoice(); nextVoiceAt = System.currentTimeMillis() + 30_000; return; }
            if (voiceBag.isEmpty()) {
                voiceBag.addAll(character.getJSONArray("voices").toJavaList(JSONObject.class));
                Collections.shuffle(voiceBag);
                if (voiceBag.size() > 1 && lastVoice.equals(voiceBag.get(0).getString("id")))
                    Collections.swap(voiceBag, 0, voiceBag.size() - 1);
            }
            JSONObject clip = voiceBag.remove(0);
            lastVoice = clip.getString("id");
            lastVoiceTitle = clip.getString("title");
            lastVoiceText = clip.getString("text");
            voice = Gdx.audio.newMusic(Gdx.files.absolute(catalog.resolve(clip.getString("file")).toString()));
            voice.setVolume(volume());
            voice.setLooping(false);
            voice.play();
            writeStatus();
        } catch (Exception e) { stopVoice(); nextVoiceAt = System.currentTimeMillis() + 60_000; reportError(e); }
    }

    private void stopVoice() {
        if (voice != null) { voice.stop(); voice.dispose(); voice = null; }
        try { if (audioLock != null) audioLock.release(); } catch (IOException ignored) { }
        try { if (audioChannel != null) audioChannel.close(); } catch (IOException ignored) { }
        audioLock = null;
        audioChannel = null;
    }

    private void reportError(Exception error) {
        if (!error.toString().equals(lastError)) Logger.error("Companion", "角色切换或语音播放失败", error);
        lastError = error.toString();
        try { writeStatus(); } catch (IOException ignored) { }
    }

    private void writeStatus() throws IOException {
        JSONObject status = new JSONObject();
        status.put("pid", ProcessHandle.current().pid());
        status.put("variant", variant);
        status.put("gestures", pet.companionGestures());
        status.put("lastVoice", lastVoice);
        status.put("voiceTitle", lastVoiceTitle);
        status.put("voiceText", lastVoiceText);
        status.put("voicePlaying", voice != null && voice.isPlaying());
        status.put("playing", pet.cha.getPlaying() == null ? "" : pet.cha.getPlaying().name());
        status.put("nextVoiceAt", nextVoiceAt);
        status.put("error", lastError);
        status.put("updatedAt", System.currentTimeMillis());
        Files.writeString(statusFile, status.toJSONString());
    }

    public void dispose() { stopVoice(); }
}
