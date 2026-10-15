/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.assets.ModelItem;
import cn.harryh.arkpets.assets.ModelsDataset;
import cn.harryh.arkpets.companion.CompanionCatalog;
import cn.harryh.arkpets.companion.CompanionPreferences;
import cn.harryh.arkpets.utils.Logger;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Adds curated characters to the upstream launcher without replacing its modules. */
public final class CompanionSupport {
    public final CompanionCatalog catalog;
    public JSONObject selected;
    public JSONObject controls;
    public Runnable onSelection = () -> {};
    public Runnable onStatus = () -> {};
    private final ArkHomeFX app;
    private final Timeline timer;
    private ComboBox<JSONObject> skins;
    private ComboBox<String> modes;
    private boolean updating;
    private final Set<String> starting = new HashSet<>();
    private final Map<String, Process> children = new HashMap<>();
    private String message = "";
    public static final Map<String, String> MODE_LABELS = Map.of(
            "building", "基建", "battle-front", "战斗 · 正面", "battle-back", "战斗 · 背面");

    public CompanionSupport(ArkHomeFX app, Path source) throws Exception {
        this.app = app;
        catalog = new CompanionCatalog(source);
        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    /** Adapts one representative per character to the original searchable, favoriteable list. */
    public ModelsDataset dataset() {
        JSONObject json = new JSONObject();
        json.put("storageDirectory", Map.of("COMPANION", catalog.directory.resolve("models").toString()));
        json.put("arkPetsCompatibility", new int[]{3, 13, 1});
        json.put("sortTags", Map.of("COMPANION", "干员"));
        JSONObject data = new JSONObject();
        for (JSONObject character : catalog.characters()) {
            JSONObject skin = defaultSkin(character, new JSONObject());
            JSONObject model = catalog.model(character, skin.getString("id"), firstMode(skin, "building"));
            JSONObject item = new JSONObject();
            item.put("name", character.getString("name"));
            item.put("type", "COMPANION");
            item.put("appellation", character.getJSONArray("skins").size() + " 套外观");
            item.put("assetList", model.getJSONObject("files"));
            Set<String> tags = new LinkedHashSet<>();
            for (JSONObject s : character.getJSONArray("skins").toJavaList(JSONObject.class))
                s.getJSONObject("models").keySet().forEach(m -> tags.add(MODE_LABELS.get(m)));
            if (!character.getJSONArray("voices").isEmpty()) tags.add("普通话语音");
            item.put("sortTags", new JSONArray(tags));
            data.put(character.getString("id"), item);
        }
        json.put("data", data);
        ModelsDataset result = new ModelsDataset(json);
        for (ModelItem item : result.data) {
            JSONObject character = catalog.character(item.key);
            JSONObject skin = defaultSkin(character, new JSONObject());
            String mode = firstMode(skin, "building");
            item.assetDir = catalog.resolve(catalog.model(character, skin.getString("id"), mode).getString("asset")).toFile();
        }
        return result;
    }

    /** Includes every wardrobe variant when the original verification dialog checks the collection. */
    public ModelsDataset allModelsDataset() {
        JSONObject json = new JSONObject();
        json.put("storageDirectory", Map.of("COMPANION", catalog.directory.toString()));
        json.put("arkPetsCompatibility", new int[]{3, 13, 1});
        JSONObject data = new JSONObject();
        for (JSONObject character : catalog.characters())
            for (JSONObject skin : character.getJSONArray("skins").toJavaList(JSONObject.class))
                for (String mode : skin.getJSONObject("models").keySet()) {
                    JSONObject model = skin.getJSONObject("models").getJSONObject(mode);
                    JSONObject item = new JSONObject();
                    item.put("type", "COMPANION");
                    item.put("name", character.getString("name"));
                    item.put("assetList", model.getJSONObject("files"));
                    item.put("sortTags", new JSONArray());
                    data.put(model.getString("asset"), item);
                }
        json.put("data", data);
        return new ModelsDataset(json);
    }

    public void bindWardrobe(ComboBox<JSONObject> skins, ComboBox<String> modes) {
        this.skins = skins;
        this.modes = modes;
        skins.setConverter(new StringConverter<>() {
            public String toString(JSONObject s) { return s == null ? "" : s.getString("name"); }
            public JSONObject fromString(String s) { return null; }
        });
        modes.setConverter(new StringConverter<>() {
            public String toString(String s) { return MODE_LABELS.getOrDefault(s, ""); }
            public String fromString(String s) { return s; }
        });
        skins.valueProperty().addListener((o, before, value) -> {
            if (updating || value == null) return;
            updating = true;
            updateModes(value, modes.getValue());
            updating = false;
            saveWardrobe();
        });
        modes.valueProperty().addListener((o, before, value) -> { if (!updating && value != null) saveWardrobe(); });
    }

    private JSONObject defaultSkin(JSONObject character, JSONObject preferences) {
        List<JSONObject> all = character.getJSONArray("skins").toJavaList(JSONObject.class);
        return all.stream().filter(s -> Objects.equals(s.getString("id"), preferences.getString("skin")))
                .findFirst().orElseGet(() -> all.stream().filter(s -> "默认".equals(s.getString("name"))).findFirst().orElse(all.get(0)));
    }

    private String firstMode(JSONObject skin, String requested) {
        return List.of("building", "battle-front", "battle-back").stream()
                .filter(m -> skin.getJSONObject("models").containsKey(m))
                .filter(m -> Objects.equals(m, requested)).findFirst()
                .orElseGet(() -> List.of("building", "battle-front", "battle-back").stream()
                        .filter(m -> skin.getJSONObject("models").containsKey(m)).findFirst().orElseThrow());
    }

    public void select(String id) {
        try {
            selected = catalog.character(id);
            Path file = catalog.controls(id);
            controls = Files.exists(file) ? JSONObject.parseObject(Files.readString(file)) : new JSONObject();
            if (app.config.companion_preferences == null) app.config.companion_preferences = new JSONObject();
            JSONObject profile = app.config.companion_preferences.getJSONObject(id);
            if (profile != null) profile.forEach(controls::putIfAbsent);
            CompanionPreferences.defaults(controls);
            controls.putIfAbsent("voiceEnabled", true);
            controls.putIfAbsent("volume", .4);
            controls.putIfAbsent("voiceMinSeconds", 600);
            controls.putIfAbsent("voiceMaxSeconds", 1200);
            JSONObject preferred = defaultSkin(selected, controls);
            updating = true;
            skins.getItems().setAll(selected.getJSONArray("skins").toJavaList(JSONObject.class));
            skins.setValue(preferred);
            updateModes(preferred, controls.getString("mode"));
            updating = false;
            message = "";
            saveWardrobe();
            onSelection.run();
        } catch (Exception error) { fail(error); }
        finally { updating = false; }
    }

    private void updateModes(JSONObject skin, String requested) {
        modes.getItems().setAll(List.of("building", "battle-front", "battle-back").stream()
                .filter(m -> skin.getJSONObject("models").containsKey(m)).toList());
        modes.setValue(firstMode(skin, requested));
    }

    private void saveWardrobe() {
        if (selected == null || skins.getValue() == null || modes.getValue() == null) return;
        controls.put("skin", skins.getValue().getString("id"));
        controls.put("mode", modes.getValue());
        JSONObject model = catalog.model(selected, controls.getString("skin"), controls.getString("mode"));
        app.config.character_asset = catalog.resolve(model.getString("asset")).toString();
        app.config.character_files = model.getJSONObject("files");
        app.config.character_label = selected.getString("name");
        app.config.save();
        save();
    }

    public void save() {
        if (selected == null) return;
        try {
            writeControls(selected.getString("id"), controls);
            if (app.config.companion_preferences == null) app.config.companion_preferences = new JSONObject();
            JSONObject profile = new JSONObject();
            for (String key : CompanionPreferences.DEFAULTS.keySet()) profile.put(key, controls.get(key));
            if (controls.containsKey("selectedVoiceIds")) profile.put("selectedVoiceIds", controls.getJSONArray("selectedVoiceIds"));
            app.config.companion_preferences.put(selected.getString("id"), profile);
            app.config.save();
        }
        catch (Exception error) { fail(error); }
    }

    private void writeControls(String id, JSONObject value) throws Exception {
        Path file = catalog.controls(id);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, value.toJSONString());
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    public JSONObject state() {
        if (selected == null) return new JSONObject();
        return state(selected.getString("id"));
    }

    private JSONObject state(String id) {
        try { return JSONObject.parseObject(Files.readString(catalog.controls(id).resolveSibling(id + ".status.json"))); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private ProcessHandle process(String id) {
        Process local = children.get(id);
        if (local != null && local.isAlive()) return local.toHandle();
        try {
            JSONObject state = state(id);
            ProcessHandle process = ProcessHandle.of(state.getLongValue("pid")).orElse(null);
            if (process == null || !process.isAlive()) return null;
            if (Arrays.asList(process.info().arguments().orElse(new String[0])).contains("-Darkpets.companion.character=" + id)) return process;
            String line = process.info().commandLine().orElse("");
            if (line.contains("-Darkpets.companion.character=" + id) && line.contains(catalog.directory.toString())) return process;
            long startedAt = process.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
            return startedAt > 0 && startedAt == state.getLongValue("startedAt") && id.equals(state.getString("character"))
                    && catalog.directory.toString().equals(state.getString("catalogDirectory")) ? process : null;
        } catch (Exception ignored) { return null; }
    }

    public boolean running() { return selected != null && process(selected.getString("id")) != null; }

    public void command(String action) {
        if (!running()) { message = "请先启动这位角色。"; refresh(); return; }
        controls.put("command", action);
        controls.put("commandId", UUID.randomUUID().toString());
        save();
    }

    /** Starts with all upstream settings; applying settings restarts only this character. */
    public void launch() {
        if (selected == null) return;
        String id = selected.getString("id");
        if (!starting.add(id)) return;
        saveWardrobe();
        JSONObject config = JSONObject.parseObject(JSONObject.toJSONString(app.config));
        config.remove("download_mc_cdk");
        config.remove("user_announcement_read");
        ProcessHandle before = process(id);
        if (before != null) command("exit");
        message = before == null ? "正在启动…" : "正在应用原版设置并重启当前角色…";
        refresh();
        CompletableFuture.runAsync(() -> {
            try {
                if (before != null) before.onExit().get(8, TimeUnit.SECONDS);
                Platform.runLater(() -> {
                    try {
                        JSONObject value = JSONObject.parseObject(Files.readString(catalog.controls(id)));
                        value.put("command", "none");
                        value.put("commandId", UUID.randomUUID().toString());
                        writeControls(id, value);
                        if (selected != null && id.equals(selected.getString("id"))) controls = value;
                        Files.writeString(catalog.controls(id).resolveSibling(id + ".status.json"), "{}");
                        Path configFile = catalog.controls(id).resolveSibling(id + ".config.json");
                        Files.writeString(configFile, config.toJSONString());
                        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                                .map(p -> Path.of(p).toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator));
                        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                                "-Dfile.encoding=UTF-8", "-Darkpets.companion.catalog=" + catalog.directory.resolve("catalog.json"),
                                "-Darkpets.companion.character=" + id, "-cp", classpath, EmbeddedLauncher.class.getName(), "--config", configFile.toString());
                        builder.directory(catalog.directory.toFile());
                        builder.redirectErrorStream(true);
                        builder.redirectOutput(catalog.directory.resolve("runtime/" + id + ".log").toFile());
                        Process child = builder.start();
                        children.put(id, child);
                        child.onExit().thenAccept(p -> Platform.runLater(() -> {
                            if (p.exitValue() != 0) fail(new IllegalStateException("角色进程退出，错误码 " + p.exitValue()));
                        }));
                        message = "";
                    } catch (Exception error) { fail(error); }
                    finally { starting.remove(id); refresh(); }
                });
            } catch (Exception error) { Platform.runLater(() -> { starting.remove(id); fail(error); refresh(); }); }
        });
    }

    public String statusText() {
        if (!message.isEmpty()) return message;
        if (!running()) return "未启动 · 在左侧点击启动";
        JSONObject state = state();
        if (state.getString("activity") != null && !state.getString("activity").isEmpty()) return "自动活动 · " + state.getString("activity");
        if (state.getString("error") != null && !state.getString("error").isEmpty()) return "未能应用：" + state.getString("error");
        return "陪伴中 · " + MODE_LABELS.getOrDefault(controls.getString("mode"), "");
    }

    private void refresh() {
        if (selected != null && running() && !starting.contains(selected.getString("id"))) {
            try {
                JSONObject latest = JSONObject.parseObject(Files.readString(catalog.controls(selected.getString("id"))));
                if (latest != null && (!Objects.equals(latest.getString("skin"), controls.getString("skin"))
                        || !Objects.equals(latest.getString("mode"), controls.getString("mode")))) {
                    controls = latest;
                    updating = true;
                    JSONObject skin = defaultSkin(selected, controls);
                    skins.setValue(skin);
                    updateModes(skin, controls.getString("mode"));
                    updating = false;
                    saveWardrobe();
                    onSelection.run();
                }
            } catch (Exception ignored) { updating = false; }
        }
        if (selected != null) {
            app.rootModule.launchBtn.setText(running() ? "重启" : "启动");
            if (app.rootModule.launchBtn.getTooltip() == null)
                app.rootModule.launchBtn.setTooltip(new javafx.scene.control.Tooltip("启动当前角色；已运行时，按新的原版设置重启当前角色。换装和模型切换即时生效。"));
            app.rootModule.launchBtn.setDisable(starting.contains(selected.getString("id")));
        }
        onStatus.run();
    }

    private void fail(Exception error) {
        message = "未能完成：" + error.getMessage();
        Logger.error("Companion", message, error);
    }

    public void close(boolean solidExit) {
        timer.stop();
        if (solidExit) for (JSONObject character : catalog.characters()) {
            String id = character.getString("id");
            if (process(id) == null) continue;
            try {
                JSONObject value = JSONObject.parseObject(Files.readString(catalog.controls(id)));
                value.put("command", "exit");
                value.put("commandId", UUID.randomUUID().toString());
                writeControls(id, value);
            } catch (Exception error) { fail(error); }
        }
    }
}
