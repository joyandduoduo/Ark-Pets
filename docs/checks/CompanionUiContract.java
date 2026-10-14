/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.companion.CompanionCatalog;
import com.alibaba.fastjson2.JSONObject;
import javafx.animation.*;
import javafx.application.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the app's own controls; it does not inject Windows input. */
public final class CompanionUiContract extends Application {
    private CompanionLauncher app;
    private CompanionCatalog catalog;
    private JSONObject first;
    private Path state;
    private Path report;
    private long started;
    private int phase;
    private long pid;
    private String otherSkin;
    private boolean audioPathSeen;
    private final List<String> passed = new ArrayList<>();
    private Timeline timer;

    public static void main(String[] args) { Application.launch(CompanionUiContract.class, args); }
    public static final class Entry {
        public static void main(String[] args) { Application.launch(CompanionUiContract.class, args); }
    }

    private Object field(String name) throws Exception {
        Field f = CompanionLauncher.class.getDeclaredField(name); f.setAccessible(true); return f.get(app);
    }
    private void invoke(String name, Class<?> type, Object value) throws Exception {
        Method m = type == null ? CompanionLauncher.class.getDeclaredMethod(name) : CompanionLauncher.class.getDeclaredMethod(name, type);
        m.setAccessible(true); if (type == null) m.invoke(app); else m.invoke(app, value);
    }
    private JSONObject status() throws Exception { return JSONObject.parseObject(Files.readString(state)); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @Override public void start(Stage stage) throws Exception {
        catalog = new CompanionCatalog(Path.of(getParameters().getNamed().get("catalog")));
        report = Path.of(getParameters().getNamed().get("report"));
        app = new CompanionLauncher();
        app.startWithCatalog(stage, catalog.directory.resolve("catalog.json"));
        for (JSONObject character : catalog.characters()) {
            invoke("select", JSONObject.class, character);
            check(((Label) field("name")).getText().equals(character.getString("name")), "Character selection");
        }
        passed.add("all 11 character selections");
        first = catalog.characters().get(0);
        invoke("select", JSONObject.class, first);
        ((CheckBox) field("voices")).setSelected(false);
        ((Slider) field("volume")).setValue(0);
        invoke("save", null, null);
        invoke("startCharacter", null, null);
        pid = ((Process) ((Map<?, ?>) field("processes")).get(first.getString("id"))).pid();
        state = catalog.controls(first.getString("id")).resolveSibling(first.getString("id") + ".status.json");
        started = System.currentTimeMillis();
        timer = new Timeline(new KeyFrame(Duration.millis(500), e -> step(stage)));
        timer.setCycleCount(Timeline.INDEFINITE); timer.play();
    }

    @SuppressWarnings("unchecked") private void step(Stage stage) {
        try {
            if (System.currentTimeMillis() - started > 100_000) throw new AssertionError("Live companion timeout at phase " + phase);
            if (!Files.exists(state)) return;
            JSONObject s;
            try { s = status(); } catch (Exception incompleteWrite) { return; }
            if (s.getLongValue("pid") != pid) return;
            check(s.getString("error").isEmpty(), "Runtime error: " + s.getString("error"));
            if (phase == 0) {
                check(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Character process not alive");
                passed.add("native character started");
                ComboBox<JSONObject> skins = (ComboBox<JSONObject>) field("skins");
                JSONObject other = skins.getItems().stream().filter(v -> !v.getString("id").equals(skins.getValue().getString("id"))).findFirst().orElseThrow();
                otherSkin = other.getString("id"); skins.setValue(other);
                ((CheckBox) field("voices")).setSelected(true);
                phase = 1;
            } else if (phase == 1 && s.getString("variant").equals(otherSkin + "/building")) {
                check(pid == s.getLongValue("pid"), "Wardrobe restarted the character");
                long delay = s.getLongValue("nextVoiceAt") - System.currentTimeMillis();
                check(delay >= 570_000 && delay <= 1_200_000, "Voice interval outside 10-20 minutes");
                passed.add("wardrobe changed within same character process"); passed.add("10-20 minute voice scheduling");
                ((ComboBox<String>) field("modes")).setValue("battle-front"); phase = 2;
            } else if (phase == 2 && s.getString("variant").equals(otherSkin + "/battle-front")) {
                check(pid == s.getLongValue("pid"), "Pose change restarted character");
                passed.add("building to battle model switch");
                invoke("command", String.class, "skill3"); phase = 3;
            } else if (phase == 3 && s.getString("playing").toLowerCase(Locale.ROOT).startsWith("skill_3")) {
                passed.add("native skill animation played");
                ((CheckBox) field("voices")).setSelected(false);
                invoke("command", String.class, "voice"); phase = 4;
            } else if (phase == 4) {
                if (s.getBooleanValue("voicePlaying") && !audioPathSeen) {
                    audioPathSeen = true;
                    passed.add("real audio playback path at zero volume");
                }
                if (audioPathSeen && s.getString("playing").equalsIgnoreCase("Idle")) {
                    passed.add("native skill finished and returned to idle");
                    invoke("command", String.class, "exit"); phase = 5;
                }
            } else if (phase == 5 && !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                passed.add("single character exited"); finish(stage, null);
            }
        } catch (Throwable failure) { finish(stage, failure); }
    }

    private void finish(Stage stage, Throwable error) {
        timer.stop();
        if (pid != 0) ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroy);
        try {
            JSONObject prefs = (JSONObject) field("controls");
            prefs.put("voiceEnabled", true); prefs.put("volume", .4); prefs.put("voiceMinSeconds", 600); prefs.put("voiceMaxSeconds", 1200); prefs.put("command", "none");
            prefs.put("skin", first.getJSONArray("skins").toJavaList(JSONObject.class).stream().filter(v -> "默认".equals(v.getString("name"))).findFirst().orElseThrow().getString("id"));
            prefs.put("mode", "building");
            Files.writeString(catalog.controls(first.getString("id")), prefs.toJSONString());
            JSONObject result = new JSONObject(); result.put("passed", passed); result.put("failure", error == null ? null : error.toString());
            result.put("physicalWindowsClicksTested", false); result.put("speakerAudibilityTested", false);
            Files.writeString(report, result.toJSONString());
        } catch (Exception e) { throw new RuntimeException(e); }
        stage.close(); Platform.exit();
    }
}
