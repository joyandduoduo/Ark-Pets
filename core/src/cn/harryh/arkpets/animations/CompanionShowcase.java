/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.animations;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Composes a bounded Ling trial with sustained idle poses and brief attacks. */
public final class CompanionShowcase {
    private CompanionShowcase() { }

    public static boolean supports(String character) { return "char_2023_ling".equals(character); }

    public record Plan(AnimData animation, int skill, float seconds) { }

    public static Plan ling(Iterable<AnimClip> clips, int skill, AnimData idle) {
        Map<String, AnimClip> names = new HashMap<>();
        for (AnimClip clip : clips)
            names.put(clip.fullName.toLowerCase(Locale.ROOT).replaceAll("_0([1-3])(?=_|$)", "_$1"), clip);
        AnimClip standing = names.get("idle");
        if (!usable(standing) || !usable(names.get("attack"))) return null;
        List<AnimClip> sequence = new ArrayList<>();
        hold(sequence, standing, between(5, 8));
        sequence.add(names.get("attack"));
        hold(sequence, standing, between(3, 5));
        if (skill == 3) {
            for (String name : List.of("skill_3_begin", "skill_3_idle", "skill_3_loop", "skill_3_end"))
                if (!usable(names.get(name))) return null;
            sequence.add(names.get("skill_3_begin"));
            hold(sequence, names.get("skill_3_idle"), between(9, 12));
            sequence.add(names.get("skill_3_loop"));
            hold(sequence, names.get("skill_3_idle"), between(11, 15));
            sequence.add(names.get("skill_3_end"));
            hold(sequence, standing, between(5, 8));
        } else if (skill == 2 && usable(names.get("skill_2"))) {
            sequence.add(names.get("skill_2"));
            hold(sequence, standing, between(16, 22));
        } else return null;
        AnimData result = idle;
        for (int i = sequence.size() - 1; i >= 0; i--)
            result = new AnimData(sequence.get(i), result, false, true, 0);
        return new Plan(result, skill, CompanionAnimations.duration(result));
    }

    private static boolean usable(AnimClip clip) { return clip != null && clip.duration > 0 && clip.duration <= 12; }
    private static int between(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }

    /** Keeps whole idle cycles so transitions never cut a pose halfway through. */
    private static void hold(List<AnimClip> sequence, AnimClip clip, int seconds) {
        int cycles = Math.min(64, Math.max(1, (int) Math.ceil(seconds / clip.duration)));
        for (int i = 0; i < cycles; i++) sequence.add(clip);
    }
}
