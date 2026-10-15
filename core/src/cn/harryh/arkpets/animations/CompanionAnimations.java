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

    /** Gets the duration of a finite gesture, stopping before its looping idle. */
    public static float duration(AnimData gesture) {
        float seconds = 0;
        for (AnimData part = gesture; part != null && !part.isLoop(); part = part.animNext()) seconds += part.animClip().duration;
        return seconds;
    }

    /** Lists finite daily gestures that can safely return to idle. */
    public static List<AnimData> daily(Iterable<AnimClip> clips, AnimData idle, boolean allowSit, boolean allowSleep, boolean allowInteract, boolean allowSpecial) {
        List<AnimData> result = new ArrayList<>();
        for (AnimClip clip : clips) {
            if (!clip.stage.equals(idle.animClip().stage) || clip.duration <= 0 || clip.duration > 12) continue;
            boolean allowed = switch (clip.type) {
                case INTERACT -> allowInteract;
                case SPECIAL -> allowSpecial;
                case IDLE -> !clip.fullName.equals(idle.name());
                case SIT -> allowSit;
                case SLEEP -> allowSleep;
                default -> false;
            };
            if (allowed && clip.modifier == AnimClip.AnimModifier.NONE)
                result.add(new AnimData(clip, idle, false, true, 0));
        }
        return result;
    }

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
