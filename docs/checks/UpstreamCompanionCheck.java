/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.utils.ArgPending;
import cn.harryh.arkpets.utils.GuiComponents.NamedItem;
import cn.harryh.arkpets.utils.Logger;
import cn.harryh.arkpets.controllers.CompanionVoicePicker;
import cn.harryh.arkpets.companion.CompanionVoices;
import com.alibaba.fastjson2.JSONObject;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Exercises the upstream FXML modules and real pet processes using isolated preferences. */
public final class UpstreamCompanionCheck extends Application {
    public static final class Entry {
        public static void main(String[] args) {
            System.setProperty("arkpets.companion.catalog", args[0]);
            System.setProperty("arkpets.check.output", args[1]);
            ArgPending.argCache = new String[0];
            try { Files.createDirectories(Path.of(Const.PathConfig.tempDirPath)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            Logger.initialize("logs/upstream-check", 2);
            Application.launch(UpstreamCompanionCheck.class);
            System.exit(0);
        }
    }
    private ArkHomeFX app;
    private Stage stage;
    private Timeline timer;
    private final List<String> passed = new ArrayList<>();
    private int phase;
    private long started, pid, restartedPid;
    private String otherSkin;
    private boolean voiceSeen;
    private long phaseReady;
    private Path output;

    @SuppressWarnings("unchecked") private <T> T control(String id) {
        Object node = stage.getScene().lookup("#" + id);
        if (node != null) return (T) node;
        for (Object controller : new Object[]{app.modelsModule, app.behaviorModule, app.settingsModule, app.rootModule}) {
            try { var field = controller.getClass().getDeclaredField(id); field.setAccessible(true); return (T) field.get(controller); }
            catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
    private void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    @Override public void start(Stage stage) throws Exception {
        this.stage = stage;
        output = Path.of(System.getProperty("arkpets.check.output"));
        Files.createDirectories(output);
        app = new ArkHomeFX(); app.start(stage);
        stage.setOpacity(1);
        started = System.currentTimeMillis();
        timer = new Timeline(new KeyFrame(Duration.millis(500), e -> step()));
        timer.setCycleCount(Timeline.INDEFINITE); timer.play();
    }

    private void snapshot(String name) throws Exception {
        snapshotNode(stage.getScene().getRoot(), name);
    }

    private void snapshotNode(javafx.scene.Node node, String name) throws Exception {
        var image = node.snapshot(null, null);
        var pixels = image.getPixelReader();
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, pixels.getArgb(x, y));
        ImageIO.write(png, "png", output.resolve(name + ".png").toFile());
    }

    @SuppressWarnings("unchecked") private void step() {
        try {
            Files.writeString(output.resolve("progress.json"), new JSONObject(Map.of("phase", phase, "showing", stage.isShowing(), "opacity", stage.getOpacity(), "pid", app.companions.state().getLongValue("pid"), "character", app.companions.selected == null ? "" : app.companions.selected.getString("id"))).toJSONString());
            if (System.currentTimeMillis() - started > 150_000) throw new AssertionError("Timed out in phase " + phase);
            if (app.modelsModule == null || app.behaviorModule == null || app.settingsModule == null) return;
            ListView<cn.harryh.arkpets.assets.ModelItem> characters = control("modelListView");
            if (characters.getItems().isEmpty()) return;
            if (phase <= 3) app.dialogs.getDialogController("announceDialog").getReturnButton().fire();
            JSONObject state = app.companions.state();
            if (phase > 1 && phase < 8 && state.getString("error") != null)
                check(state.getString("error").isEmpty(), "Pet runtime: " + state.getString("error"));
            if (phase == 0) {
                app.dialogs.getDialogController("announceDialog").getReturnButton().fire();
                check(characters.getItems().size() == 11, "Exactly 11 character rows");
                for (var item : List.copyOf(characters.getItems())) {
                    characters.getSelectionModel().select(item);
                    check(app.companions.selected.getString("name").equals(item.name), "Selection belongs to character");
                    check(((ComboBox<?>) control("companionSkin")).getItems().size() == app.companions.selected.getJSONArray("skins").size(), "All skins grouped");
                }
                passed.add("11 character rows with every wardrobe grouped");
                for (String id : List.of("searchModelInput", "topFavorite", "toggleFilterPane", "toggleManagePane", "modelVerify", "modelExport",
                        "configBehaviorAllowWalk", "configBehaviorAllowSit", "configBehaviorAllowSleep", "configBehaviorAllowSpecial",
                        "configBehaviorAiActivation", "configBehaviorWalkSpeed", "configBehaviorAllowInteract", "configBehaviorDoPeerRepulsion",
                        "configDirectionSwitching", "configDeployMultiMonitors", "configDeployMarginBottom", "configTransitionAnimation",
                        "configTransitionDuration", "configTransitionFunction", "configPhysicGravity", "configPhysicAirFriction",
                        "configPhysicStaticFriction", "configPhysicSpeedLimitX", "configPhysicSpeedLimitY", "configDisplayScale", "configDisplayFps",
                        "configCanvasColor", "configRenderOutline", "configRenderOpacityNormal", "configWindowTopmost", "configSolidExit", "configEcoMode"))
                    check(control(id) != null, "Upstream control missing: " + id);
                passed.add("33 original model/behavior/display/physics/window controls retained");
                characters.getSelectionModel().select(characters.getItems().stream().filter(c -> c.name.equals("荒芜拉普兰德")).findFirst().orElseThrow());
                app.rootModule.moduleWrapperComposer.activate(0);
                phaseReady = System.currentTimeMillis() + 1600;
                phase = 1;
            } else if (phase == 1 && System.currentTimeMillis() >= phaseReady) {
                snapshot("模型页");
                app.rootModule.moduleWrapperComposer.activate(1);
                phase = 2;
            } else if (phase == 2) {
                snapshot("行为页");
                for (String id : List.of("autoActions", "battleActions", "autoWardrobe", "bubbleEnabled", "activityMin", "activityMax",
                        "wardrobeMin", "wardrobeMax", "bubbleTheme", "tryActivity", "changeWardrobe", "previewBubble"))
                    check(control(id) != null, "Companion control missing: " + id);
                ComboBox<Integer> minimum = control("activityMin"), maximum = control("activityMax");
                minimum.setValue(180);
                check(maximum.getValue() >= 180, "Activity interval order");
                ComboBox<String> theme = control("bubbleTheme"); theme.setValue("terminal");
                JSONObject saved = JSONObject.parseObject(Files.readString(app.companions.catalog.controls(app.companions.selected.getString("id"))));
                check(saved.getIntValue("activityMinSeconds") == 180 && "terminal".equals(saved.getString("bubbleTheme")), "Companion preferences persisted");
                check("terminal".equals(app.config.companion_preferences.getJSONObject(app.companions.selected.getString("id")).getString("bubbleTheme")), "ArkConfig profile persisted");
                minimum.setValue(45); maximum.setValue(120); theme.setValue("rhodes");
                snapshotNode(control("companionPane"), "陪伴设置完整页");
                Stage picker = new CompanionVoicePicker(app.companions).open(stage);
                picker.getScene().getRoot().applyCss(); picker.getScene().getRoot().layout();
                snapshotNode(picker.getScene().getRoot(), "语音勾选面板");
                ComboBox<JSONObject> sources = (ComboBox<JSONObject>) picker.getScene().lookup("#voiceSource");
                check(sources.getItems().size() == 4, "Lappland default, voiced skin and original sources");
                sources.getSelectionModel().select(2);
                ((Button) picker.getScene().lookup("#voiceSelectAll")).fire();
                check(CompanionVoices.enabled(app.companions.selected, app.companions.controls).size() == 48, "Selecting skin adds all 38 without replacing defaults");
                sources.getSelectionModel().select(0);
                ((Button) picker.getScene().lookup("#voiceSelectNone")).fire();
                check(CompanionVoices.enabled(app.companions.selected, app.companions.controls).isEmpty(), "Clear playlist persists");
                JSONObject playlist = JSONObject.parseObject(Files.readString(app.companions.catalog.controls(app.companions.selected.getString("id"))));
                check(playlist.containsKey("selectedVoiceIds") && playlist.getJSONArray("selectedVoiceIds").isEmpty(), "Empty is distinct from defaults");
                picker.close();
                passed.add("Voice picker groups related operator and outfit; selections and explicit empty playlist persist");
                passed.add("12 new companion controls retained in original behavior page; interval and theme persist");
                app.rootModule.moduleWrapperComposer.activate(2);
                phase = 3;
            } else if (phase == 3) {
                snapshot("选项页");
                stage.hide();
                characters.getSelectionModel().select(characters.getItems().stream().filter(c -> c.name.equals("重岳")).findFirst().orElseThrow());
                ((ComboBox<String>) control("companionMode")).setValue("building");
                ComboBox<NamedItem<Float>> scale = control("configDisplayScale");
                scale.setValue(scale.getItems().stream().filter(v -> v.value() == .75f).findFirst().orElseThrow());
                CheckBox walk = control("configBehaviorAllowWalk");
                if (walk.isSelected()) walk.fire();
                app.companions.controls.put("voiceEnabled", true);
                app.companions.controls.put("volume", 0);
                app.companions.controls.put("autoActions", false);
                app.companions.controls.put("autoWardrobe", false);
                app.companions.save();
                app.rootModule.launchBtn.fire(); phase = 4;
            } else if (phase == 4 && app.companions.running() && state.getLongValue("pid") > 0 && !state.isEmpty()) {
                pid = state.getLongValue("pid");
                JSONObject config = JSONObject.parseObject(Files.readString(app.companions.catalog.controls(app.companions.selected.getString("id")).resolveSibling(app.companions.selected.getString("id") + ".config.json")));
                check(config.getFloatValue("display_scale") == .75f && !config.getBooleanValue("behavior_allow_walk"), "Original settings must reach pet process");
                passed.add("Original scale and walk settings passed to pet config");
                long delay = state.getLongValue("nextVoiceAt") - System.currentTimeMillis();
                check(delay > 570_000 && delay <= 1_200_000, "10-20 minute schedule");
                passed.add("10-20 minute automatic Mandarin schedule");
                ComboBox<JSONObject> skins = control("companionSkin");
                JSONObject other = skins.getItems().stream().filter(s -> !s.getString("id").equals(skins.getValue().getString("id"))).findFirst().orElseThrow();
                otherSkin = other.getString("id"); skins.setValue(other); phase = 5;
            } else if (phase == 5 && (otherSkin + "/building").equals(state.getString("variant"))) {
                check(pid == state.getLongValue("pid"), "Wardrobe must keep PID");
                passed.add("Live wardrobe keeps same character process");
                ((ComboBox<String>) control("companionMode")).setValue("battle-front"); phase = 6;
            } else if (phase == 6 && (otherSkin + "/battle-front").equals(state.getString("variant"))) {
                check(pid == state.getLongValue("pid"), "Battle pose must keep PID");
                passed.add("Live battle model switch keeps same character process");
                app.companions.command("skill3"); phase = 7;
            } else if (phase == 7 && state.getString("playing") != null && state.getString("playing").toLowerCase(Locale.ROOT).startsWith("skill_3")) {
                passed.add("Native battle skill starts");
                app.companions.command("voice"); phase = 8;
            } else if (phase == 8) {
                if (state.getBooleanValue("voicePlaying")) voiceSeen = true;
                if (voiceSeen && "Idle".equalsIgnoreCase(state.getString("playing"))) {
                    passed.add("Skill finishes at Idle; audio playback observed at zero volume");
                    ComboBox<NamedItem<Float>> scale = control("configDisplayScale");
                    scale.setValue(scale.getItems().stream().filter(v -> v.value() == 1f).findFirst().orElseThrow());
                    app.rootModule.launchBtn.fire(); phase = 9;
                }
            } else if (phase == 9 && app.companions.running() && state.getLongValue("pid") != pid) {
                restartedPid = state.getLongValue("pid");
                check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Old pet must exit on apply settings");
                passed.add("Applying original settings restarts only current character");
                app.companions.command("exit"); phase = 10;
            } else if (phase == 10 && !ProcessHandle.of(restartedPid).map(ProcessHandle::isAlive).orElse(false)) {
                passed.add("Current character exits through control");
                finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }

    private void finish(Throwable error) {
        timer.stop();
        try {
            JSONObject report = new JSONObject(); report.put("passed", passed); report.put("failure", error == null ? null : error.toString());
            report.put("phase", phase); report.put("physicalClicksTested", false); report.put("speakerAudibilityTested", false);
            Files.writeString(output.resolve("upstream-integration.json"), report.toJSONString());
        } catch (Exception failure) { throw new RuntimeException(failure); }
        app.stop(); stage.close(); Platform.exit();
    }
}
