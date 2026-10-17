/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.animations.*;
import cn.harryh.arkpets.companion.CompanionCatalog;
import com.alibaba.fastjson2.JSONObject;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.*;
import com.badlogic.gdx.files.FileHandle;
import com.esotericsoftware.spine.*;
import org.lwjgl.system.Configuration;
import java.nio.file.*;
import java.util.*;

/** Checks Ling's actual assets and pose-preserving animation chains in a hidden native renderer. */
public final class LingShowcaseResourceCheck extends ApplicationAdapter {
    private static CompanionCatalog catalog;
    private static Path report;
    private static Throwable failure;

    public static void main(String[] args) throws Exception {
        catalog = new CompanionCatalog(Path.of(args[0])); report = Path.of(args[1]); Files.createDirectories(report);
        cn.harryh.arkpets.utils.Logger.initialize(report.resolve("resource-log").toString(), 2);
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setInitialVisible(false); cfg.setWindowedMode(320, 320);
        cfg.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 2, 0);
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        new Lwjgl3Application(new LingShowcaseResourceCheck(), cfg);
        if (failure != null) throw new AssertionError(failure);
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    @Override public void create() {
        List<JSONObject> checks = new ArrayList<>();
        try {
            JSONObject ling = catalog.character("char_2023_ling");
            for (JSONObject skin : ling.getJSONArray("skins").toJavaList(JSONObject.class)) {
                for (String mode : List.of("battle-front", "battle-back")) {
                    JSONObject model = catalog.model(ling, skin.getString("id"), mode);
                    ArkConfig cfg = JSONObject.parseObject(new String(ArkConfig.class.getResourceAsStream(Const.configInternal).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), ArkConfig.class);
                    cfg.character_asset = catalog.resolve(model.getString("asset")).toString(); cfg.character_files = model.getJSONObject("files");
                    cfg.canvas_sampling_interval = 120; cfg.render_animation_mixture = .3f;
                    ArkChar cha = new ArkChar(cfg, .8f);
                    try {
                        GeneralBehavior behavior = new GeneralBehavior(cfg, cha.animList);
                        cha.adjustCanvas(behavior.getCurrentStage());
                        var stateField = ArkChar.class.getDeclaredField("animationState"); stateField.setAccessible(true);
                        var skeletonField = ArkChar.class.getDeclaredField("skeleton"); skeletonField.setAccessible(true);
                        AnimationState state = (AnimationState) stateField.get(cha);
                        Skeleton skeleton = (Skeleton) skeletonField.get(cha);
                        for (int skill : List.of(3, 2)) {
                            CompanionShowcase.Plan plan = CompanionShowcase.ling(cha.animList, skill, behavior.defaultAnim());
                            require(plan != null && plan.seconds() >= 25 && plan.seconds() <= 80, "Bounded presentation duration");
                            int attacks = 0, skillAttacks = 0, idleCycles = 0;
                            float skillIdleSeconds = 0;
                            for (AnimData part = plan.animation(); !part.isLoop(); part = part.animNext()) {
                                String name = part.name().toLowerCase(Locale.ROOT);
                                require(!name.contains("die") && part.isStrict() && part.mobility() == 0, "Safe finite chain");
                                if (name.equals("attack")) attacks++;
                                if (name.equals("skill_03_loop")) skillAttacks++;
                                if (name.equals("skill_03_idle")) { idleCycles++; skillIdleSeconds += part.animClip().duration; }
                            }
                            require(attacks == 1, "Exactly one normal attack");
                            if (skill == 3) require(skillAttacks == 1 && skillIdleSeconds >= 20, "Sustained skill idle with one skill attack");
                            else require(skillAttacks == 0 && idleCycles == 0, "Skill 2 uses ordinary idle");
                            cha.setSmoothCompanionChains(true); cha.resetAnimation(plan.animation());
                            int realMixes = 0;
                            String previous = cha.getPlaying().name();
                            boolean captured = false;
                            for (int tick = 0; tick < (plan.seconds() + 1) * 60; tick++) {
                                state.update(1 / 60f); state.apply(skeleton); skeleton.updateWorldTransform();
                                String current = cha.getPlaying().name();
                                require(!state.getCurrent(0).getAnimation().getName().contains("empty"), "No empty pose between chained segments");
                                if (!previous.equals(current)) {
                                    require(state.getCurrent(0).getMixingFrom() != null, "Outgoing pose retained for mixing");
                                    realMixes++;
                                }
                                previous = current;
                                if (!captured && skill == 3 && current.equals("Skill_03_Idle") && state.getCurrent(0).getTrackTime() > .6f) {
                                    cha.position.reset(cha.camera.getWidth() / 2f, 0, 1); cha.position.setToEnd();
                                    cha.render(); cha.render(); cha.camera.getFBO().begin();
                                    var pixels = cn.harryh.arkpets.render.PixmapWrapper.fromCamera(cha.camera); cha.camera.getFBO().end();
                                    pixels.savePixmap(new FileHandle(report.resolve(skin.getString("id") + "-" + mode + "-skill-idle.png").toFile()), true);
                                    pixels.dispose(); captured = true;
                                }
                            }
                            require(cha.getPlaying().isLoop() && cha.getPlaying().name().equals(behavior.defaultAnim().name()), "Returns to ordinary idle");
                            require(realMixes >= 3, "Real clip-to-clip pose mixing");
                            JSONObject result = new JSONObject(); result.put("skin", skin.getString("name")); result.put("mode", mode);
                            result.put("skill", skill); result.put("seconds", plan.seconds()); result.put("skillIdleSeconds", skillIdleSeconds);
                            result.put("normalAttacks", attacks); result.put("skillAttacks", skillAttacks); result.put("poseMixes", realMixes);
                            checks.add(result);
                        }
                    } finally { cha.dispose(); }
                }
            }
        } catch (Throwable error) { failure = error; }
        finally {
            try { Files.writeString(report.resolve("resource-checks.json"), JSONObject.toJSONString(Map.of("checks", checks, "failure", failure == null ? "" : failure.toString()))); }
            catch (Exception error) { failure = error; }
            Gdx.app.exit();
        }
    }
}
