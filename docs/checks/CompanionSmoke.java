/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.animations.*;
import cn.harryh.arkpets.companion.CompanionCatalog;
import com.alibaba.fastjson2.JSONObject;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import org.lwjgl.system.Configuration;
import javax.sound.sampled.AudioSystem;
import java.nio.file.*;
import java.util.*;

/** Checks downloaded files through the actual native Spine and audio decoders. */
public final class CompanionSmoke extends ApplicationAdapter {
    private final CompanionCatalog catalog;
    private final Path report;
    private final List<JSONObject> results = new ArrayList<>();
    private int failures;
    private CompanionSmoke(CompanionCatalog catalog, Path report) { this.catalog = catalog; this.report = report; }

    public static void main(String[] args) throws Exception {
        System.setProperty("arkpets.companion.catalog", args[0]);
        Files.createDirectories(Path.of("temp"));
        cn.harryh.arkpets.utils.Logger.initialize("logs/companion-smoke", 2);
        CompanionSmoke smoke = new CompanionSmoke(new CompanionCatalog(Path.of(args[0])), Path.of(args[1]));
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("ArkPets Companion resource check");
        config.setInitialVisible(false);
        config.setWindowedMode(320, 320);
        config.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 2, 0);
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        new Lwjgl3Application(smoke, config);
        if (smoke.failures > 0) throw new IllegalStateException(smoke.failures + " checks failed");
    }

    @Override public void create() {
        for (JSONObject character : catalog.characters()) {
            for (JSONObject skin : character.getJSONArray("skins").toJavaList(JSONObject.class)) {
                for (String mode : skin.getJSONObject("models").keySet()) {
                    JSONObject result = new JSONObject();
                    result.put("character", character.getString("name"));
                    result.put("skin", skin.getString("name"));
                    result.put("mode", mode);
                    ArkChar cha = null;
                    try {
                        JSONObject model = skin.getJSONObject("models").getJSONObject(mode);
                        ArkConfig config = JSONObject.parseObject(new String(Objects.requireNonNull(ArkConfig.class.getResourceAsStream(Const.configInternal)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), ArkConfig.class);
                        config.character_asset = catalog.resolve(model.getString("asset")).toString();
                        config.character_files = model.getJSONObject("files");
                        config.canvas_sampling_interval = 120;
                        config.transition_duration = 0;
                        config.render_animation_mixture = 0;
                        cha = new ArkChar(config, .65f);
                        GeneralBehavior behavior = new GeneralBehavior(config, cha.animList);
                        cha.adjustCanvas(behavior.getCurrentStage());
                        cha.setAnimation(behavior.defaultAnim());
                        cha.render();
                        if (System.getProperty("arkpets.check.frames") != null) {
                            Path frame = Path.of(System.getProperty("arkpets.check.frames"), character.getString("id") + "_" + skin.getString("id") + "_" + mode + ".png");
                            Files.createDirectories(frame.getParent());
                            for (int sample = 0; sample < 12; sample++) cha.render();
                            cha.camera.getFBO().begin();
                            var pixels = cn.harryh.arkpets.render.PixmapWrapper.fromCamera(cha.camera);
                            cha.camera.getFBO().end();
                            pixels.savePixmap(new com.badlogic.gdx.files.FileHandle(frame.toFile()), true);
                            pixels.dispose();
                            result.put("frame", frame.toString());
                        }
                        List<String> names = new ArrayList<>();
                        for (AnimClip clip : cha.animList) names.add(clip.fullName);
                        result.put("animations", names);
                        List<Integer> skills = new ArrayList<>();
                        for (int i = 1; i <= 3; i++) {
                            AnimData sequence = CompanionAnimations.skill(cha.animList, i, behavior.defaultAnim());
                            if (sequence == null) continue;
                            skills.add(i);
                            for (AnimData part = sequence; part != null; part = part.animNext())
                                if (part.animClip().type == AnimClip.AnimType.DIE || part.mobility() != 0)
                                    throw new IllegalStateException("Unexpected defeat or movement in skill gesture");
                        }
                        result.put("skillGestures", skills);
                        result.put("status", "loaded-and-rendered");
                    } catch (Exception e) { failures++; result.put("status", "failed"); result.put("error", e.toString()); }
                    finally { if (cha != null) cha.dispose(); }
                    results.add(result);
                }
            }
            for (JSONObject voice : character.getJSONArray("voices").toJavaList(JSONObject.class)) {
                JSONObject result = new JSONObject();
                result.put("character", character.getString("name"));
                result.put("voice", voice.getString("title"));
                try (var stream = AudioSystem.getAudioInputStream(catalog.resolve(voice.getString("file")).toFile())) {
                    if (stream.getFrameLength() <= 0) throw new IllegalStateException("Empty audio");
                    result.put("status", "decoded");
                    result.put("seconds", stream.getFrameLength() / stream.getFormat().getFrameRate());
                } catch (Exception e) { failures++; result.put("status", "failed"); result.put("error", e.toString()); }
                results.add(result);
            }
        }
        try { Files.writeString(report, JSONObject.toJSONString(Map.of("checks", results, "failures", failures))); }
        catch (Exception e) { throw new RuntimeException(e); }
        Gdx.app.exit();
    }
}
