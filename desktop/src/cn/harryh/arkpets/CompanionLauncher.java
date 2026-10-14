/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.companion.CompanionCatalog;
import com.alibaba.fastjson2.JSONObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/** Provides a character-first launcher with wardrobe and Mandarin voice controls. */
public final class CompanionLauncher extends Application {
    private CompanionCatalog catalog;
    private JSONObject selected;
    private JSONObject controls;
    private boolean updating;
    private final Map<String, Process> processes = new HashMap<>();
    private final List<Button> gestureButtons = new ArrayList<>();
    private final ComboBox<JSONObject> skins = new ComboBox<>();
    private final ComboBox<String> modes = new ComboBox<>();
    private final Label name = new Label("选择一位角色");
    private final Label details = new Label();
    private final Label status = new Label();
    private final Label voiceDetails = new Label();
    private final CheckBox voices = new CheckBox("自动轮播中文陪伴语音");
    private final Slider volume = new Slider(0, 1, .4);
    private final Spinner<Integer> minMinutes = new Spinner<>(1, 120, 10);
    private final Spinner<Integer> maxMinutes = new Spinner<>(1, 120, 20);
    private final Button launch = new Button("请他来陪伴");
    private final Map<String, String> modeLabels = Map.of("building", "基建 · 日常陪伴", "battle-front", "战斗 · 正面", "battle-back", "战斗 · 背面");

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) throws Exception {
        String source = getParameters().getNamed().getOrDefault("catalog", "companion-data/catalog.json");
        startWithCatalog(stage, Path.of(source));
    }

    /** Opens an explicitly supplied character collection. */
    public void startWithCatalog(Stage stage, Path source) throws Exception {
        catalog = new CompanionCatalog(source);
        Files.createDirectories(Path.of(Const.PathConfig.tempDirPath));
        Const.FontsConfig.REGULAR.loadFontToJavafx();
        ListView<JSONObject> characters = new ListView<>(FXCollections.observableArrayList(catalog.characters()));
        characters.setPrefWidth(225);
        characters.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(JSONObject value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : value.getString("name"));
                setPrefHeight(46);
            }
        });
        characters.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> select(value));
        skins.setConverter(new StringConverter<>() {
            public String toString(JSONObject value) { return value == null ? "" : value.getString("name"); }
            public JSONObject fromString(String value) { return null; }
        });
        modes.setConverter(new StringConverter<>() {
            public String toString(String value) { return value == null ? "" : modeLabels.getOrDefault(value, value); }
            public String fromString(String value) { return value; }
        });
        skins.setMaxWidth(Double.MAX_VALUE);
        modes.setMaxWidth(Double.MAX_VALUE);
        skins.valueProperty().addListener((o, old, value) -> { if (!updating) { updateModes(); save(); } });
        modes.valueProperty().addListener((o, old, value) -> { if (!updating) save(); });
        voices.selectedProperty().addListener((o, old, value) -> { if (!updating) save(); });
        volume.valueChangingProperty().addListener((o, old, changing) -> { if (!changing && !updating) save(); });
        volume.setOnMouseReleased(e -> save());
        volume.setOnKeyReleased(e -> save());
        minMinutes.valueProperty().addListener((o, old, value) -> { if (!updating) save(); });
        maxMinutes.valueProperty().addListener((o, old, value) -> { if (!updating) save(); });
        name.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");
        details.setWrapText(true);
        voiceDetails.setWrapText(true);
        voiceDetails.setStyle("-fx-text-fill: #566273;");
        status.setWrapText(true);
        Button play = new Button("现在说一句");
        play.setOnAction(e -> command("voice"));
        HBox skills = new HBox(10);
        for (int i = 0; i <= 3; i++) {
            final int skill = i;
            Button gesture = new Button(i == 0 ? "攻击动作" : "技能 " + i);
            gesture.setOnAction(e -> command(skill == 0 ? "attack" : "skill" + skill));
            gesture.setDisable(true);
            gestureButtons.add(gesture);
            skills.getChildren().add(gesture);
        }
        Button stop = new Button("让他休息");
        stop.setOnAction(e -> command("exit"));
        launch.setDefaultButton(true);
        launch.setOnAction(e -> startCharacter());
        launch.setStyle("-fx-background-color: #2A528C; -fx-text-fill: white; -fx-padding: 12 24;");
        HBox interval = new HBox(10, new Label("每"), minMinutes, new Label("至"), maxMinutes, new Label("分钟随机播放"));
        minMinutes.setPrefWidth(85);
        maxMinutes.setPrefWidth(85);
        VBox content = new VBox(16, name, details, new Label("外观"), skins, new Label("陪伴姿态"), modes,
                skills, new Separator(), voices, interval, new Label("音量"), volume, voiceDetails, play,
                new Separator(), new HBox(12, launch, stop), status);
        content.setPadding(new Insets(28));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        BorderPane root = new BorderPane(scroll, null, null, null, characters);
        root.setStyle("-fx-font-family: 'Source Han Sans CN'; -fx-font-size: 15px; -fx-background-color: #FAFBFD;");
        Scene scene = new Scene(root, 940, 760);
        stage.setTitle("ArkPets · 角色陪伴");
        stage.setScene(scene);
        stage.setMinWidth(780);
        stage.setMinHeight(600);
        stage.show();
        if (!characters.getItems().isEmpty()) characters.getSelectionModel().select(0);
        Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshStatus()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        stage.setOnHidden(e -> refresh.stop());
    }

    private void select(JSONObject character) {
        if (character == null) return;
        updating = true;
        selected = character;
        name.setText(character.getString("name"));
        try {
            Path file = catalog.controls(character.getString("id"));
            controls = Files.exists(file) ? JSONObject.parseObject(Files.readString(file)) : new JSONObject();
            skins.setItems(FXCollections.observableArrayList(character.getJSONArray("skins").toJavaList(JSONObject.class)));
            JSONObject preferred = skins.getItems().stream().filter(s -> Objects.equals(s.getString("id"), controls.getString("skin")))
                    .findFirst().orElseGet(() -> skins.getItems().stream().filter(s -> "默认".equals(s.getString("name"))).findFirst().orElse(skins.getItems().isEmpty() ? null : skins.getItems().get(0)));
            skins.setValue(preferred);
            modes.setValue(null);
            updateModes();
            voices.setSelected(!controls.containsKey("voiceEnabled") || controls.getBooleanValue("voiceEnabled"));
            volume.setValue(controls.containsKey("volume") ? controls.getDoubleValue("volume") : .4);
            minMinutes.getValueFactory().setValue(controls.containsKey("voiceMinSeconds") ? Math.max(1, controls.getIntValue("voiceMinSeconds") / 60) : 10);
            maxMinutes.getValueFactory().setValue(controls.containsKey("voiceMaxSeconds") ? Math.max(1, controls.getIntValue("voiceMaxSeconds") / 60) : 20);
            voiceDetails.setText("已有 " + character.getJSONArray("voices").size() + " 条中文普通话语音；一轮内不重复，多位角色错开播放。\n关闭此窗口会保留已启动的角色。选中角色后可再次打开管理。");
            details.setText(character.getJSONArray("missing").isEmpty() ? "皮肤与姿态在当前角色内切换，启动后立即生效。" : String.join("；", character.getJSONArray("missing").toJavaList(String.class)));
            launch.setDisable(preferred == null);
        } catch (Exception e) { showError(e); }
        finally { updating = false; }
        refreshStatus();
    }

    private void updateModes() {
        if (skins.getValue() == null) { modes.getItems().clear(); return; }
        boolean before = updating;
        updating = true;
        List<String> available = List.of("building", "battle-front", "battle-back").stream()
                .filter(m -> skins.getValue().getJSONObject("models").containsKey(m)).toList();
        String preferred = modes.getValue();
        if (preferred == null && controls != null) preferred = controls.getString("mode");
        modes.setItems(FXCollections.observableArrayList(available));
        modes.setValue(available.contains(preferred) ? preferred : available.isEmpty() ? null : available.get(0));
        updating = before;
    }

    private void save() {
        if (updating || selected == null || skins.getValue() == null || modes.getValue() == null) return;
        if (minMinutes.getValue() > maxMinutes.getValue()) { status.setText("最短间隔不能大于最长间隔。"); return; }
        controls.put("skin", skins.getValue().getString("id"));
        controls.put("mode", modes.getValue());
        controls.put("voiceEnabled", voices.isSelected());
        controls.put("volume", volume.getValue());
        controls.put("voiceMinSeconds", minMinutes.getValue() * 60);
        controls.put("voiceMaxSeconds", maxMinutes.getValue() * 60);
        try {
            Path file = catalog.controls(selected.getString("id"));
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, controls.toJSONString());
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { showError(e); }
    }

    private boolean running() {
        if (selected == null) return false;
        Process local = processes.get(selected.getString("id"));
        if (local != null && local.isAlive()) return true;
        try {
            Path file = catalog.controls(selected.getString("id")).resolveSibling(selected.getString("id") + ".status.json");
            if (!Files.exists(file)) return false;
            JSONObject state = JSONObject.parseObject(Files.readString(file));
            ProcessHandle process = ProcessHandle.of(state.getLongValue("pid")).orElse(null);
            return process != null && process.isAlive() && Arrays.asList(process.info().arguments().orElse(new String[0]))
                    .contains("-Darkpets.companion.character=" + selected.getString("id"));
        } catch (Exception e) { return false; }
    }

    private void command(String action) {
        if (!running()) { status.setText("请先启动这位角色。"); return; }
        controls.put("command", action);
        controls.put("commandId", UUID.randomUUID().toString());
        save();
    }

    private void startCharacter() {
        if (selected == null || skins.getValue() == null || modes.getValue() == null) return;
        if (running()) { save(); status.setText("这位角色已在陪伴中，外观与设置已更新。"); return; }
        try {
            controls.put("command", "none");
            controls.put("commandId", UUID.randomUUID().toString());
            save();
            JSONObject model = catalog.model(selected, skins.getValue().getString("id"), modes.getValue());
            JSONObject config;
            try (InputStream stream = Objects.requireNonNull(ArkConfig.class.getResource(Const.configInternal)).openStream()) {
                config = JSONObject.parseObject(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            config.put("character_asset", catalog.resolve(model.getString("asset")).toString());
            config.put("character_files", model.getJSONObject("files"));
            config.put("character_label", selected.getString("name"));
            config.put("enable_telemetry", false);
            Path configFile = catalog.controls(selected.getString("id")).resolveSibling(selected.getString("id") + ".config.json");
            Files.writeString(configFile, config.toJSONString());
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().normalize().toString())
                    .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            ProcessBuilder builder = new ProcessBuilder(javaExecutable,
                    "-Dfile.encoding=UTF-8",
                    "-Darkpets.companion.catalog=" + catalog.directory.resolve("catalog.json"),
                    "-Darkpets.companion.character=" + selected.getString("id"),
                    "-cp", classpath, EmbeddedLauncher.class.getName(), "--config", configFile.toString());
            builder.directory(catalog.directory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(catalog.directory.resolve("runtime/" + selected.getString("id") + ".log").toFile());
            processes.put(selected.getString("id"), builder.start());
            status.setText("正在请" + selected.getString("name") + "过来…");
        } catch (Exception e) { showError(e); }
    }

    private void refreshStatus() {
        if (selected == null) return;
        gestureButtons.forEach(b -> b.setDisable(true));
        if (running()) {
            launch.setText("已在陪伴 · 应用换装");
            try {
                JSONObject state = JSONObject.parseObject(Files.readString(catalog.controls(selected.getString("id")).resolveSibling(selected.getString("id") + ".status.json")));
                if (!"building".equals(modes.getValue()) && state.getJSONArray("gestures") != null)
                    for (int index : state.getJSONArray("gestures").toJavaList(Integer.class)) gestureButtons.get(index).setDisable(false);
                status.setText(state.getString("error").isEmpty() ? "陪伴中 · " + modeLabels.getOrDefault(modes.getValue(), "") : "设置未生效：" + state.getString("error"));
                if (state.getString("voiceTitle") != null && !state.getString("voiceTitle").isEmpty())
                    voiceDetails.setText(state.getString("voiceTitle") + "：" + state.getString("voiceText"));
            } catch (Exception ignored) { }
        } else launch.setText("请他来陪伴");
    }

    private void showError(Exception error) {
        status.setText("未能完成：" + error.getMessage());
    }
}
