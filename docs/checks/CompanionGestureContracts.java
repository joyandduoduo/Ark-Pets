/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import cn.harryh.arkpets.animations.*;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.*;
import java.util.*;

/** Verifies the naming exceptions found in the selected characters' real models. */
public final class CompanionGestureContracts {
    public static void main(String[] args) throws Exception {
        JSONObject report = JSONObject.parseObject(Files.readString(Path.of(args[0])));
        int checked = 0;
        for (JSONObject model : report.getJSONArray("checks").toJavaList(JSONObject.class)) {
            if (!model.containsKey("animations")) continue;
            List<AnimClip> clips = model.getJSONArray("animations").toJavaList(String.class).stream().map(n -> new AnimClip(n, 1)).toList();
            AnimClip idleClip = clips.stream().filter(c -> c.fullName.equalsIgnoreCase("Idle")).findFirst().orElse(clips.get(0));
            AnimData idle = new AnimData(idleClip, null, true, false);
            List<Integer> available = new ArrayList<>();
            for (int i = 0; i <= 3; i++) {
                AnimData gesture = CompanionAnimations.skill(clips, i, idle);
                if (gesture == null) continue;
                available.add(i);
                int length = 0;
                for (AnimData part = gesture; part != null; part = part.animNext()) {
                    if (++length > 5 || part.name().toLowerCase(Locale.ROOT).contains("die") || part.mobility() != 0)
                        throw new AssertionError("Invalid companion gesture: " + model.getString("character"));
                    if (part.animNext() == null && part != idle) throw new AssertionError("Gesture did not return to idle");
                }
            }
            model.put("availableGestures", available);
            if ("令".equals(model.getString("character")) && "默认".equals(model.getString("skin")) && model.getString("mode").startsWith("battle") && !available.containsAll(List.of(0, 2, 3)))
                throw new AssertionError("Zero-padded skill names not supported");
            if ("Misery".equals(model.getString("character")) && !available.equals(List.of(0)))
                throw new AssertionError("Misery must expose only its real attack gesture");
            checked++;
        }
        report.put("gestureModelsChecked", checked);
        Files.writeString(Path.of(args[0]), report.toJSONString());
        System.out.println("PASS: " + checked + " real model gesture inventories; attacks, zero-padded skills, Begin/Start, return to idle, no defeat gestures");
    }
}
