/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.animations;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Composes one complete battle gesture and returns to the ordinary idle. */
public final class CompanionAnimations {
    private CompanionAnimations() {}

    public static AnimData skill(Iterable<AnimClip> clips, int number, AnimData idle) {
        if (number < 0 || number > 3) return null;
        Map<String, AnimClip> names = new LinkedHashMap<>();
        for (AnimClip clip : clips)
            names.put(clip.fullName.toLowerCase(Locale.ROOT).replaceAll("_0([1-3])(?=_|$)", "_$1"), clip);
        List<AnimClip> selected = new ArrayList<>();
        String prefix = number == 0 ? "attack" : "skill_" + number;
        for (List<String> alternatives : List.of(
                List.of(prefix + "_start", prefix + "_begin"),
                List.of(prefix, prefix + "_loop", prefix + "_loop_a", prefix + "_attack", prefix + "_attack_a", prefix + "_attack_1", prefix + "_a", prefix + "_1", "skill" + number),
                List.of(prefix + "_end"))) {
            for (String name : alternatives) {
                if (names.containsKey(name)) { selected.add(names.get(name)); break; }
            }
        }
        if (selected.isEmpty()) return null;
        AnimData sequence = idle;
        for (int i = selected.size() - 1; i >= 0; i--)
            sequence = new AnimData(selected.get(i), sequence, false, true, 0);
        return sequence;
    }
}
