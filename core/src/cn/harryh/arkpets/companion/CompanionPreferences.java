/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import com.alibaba.fastjson2.JSONObject;
import java.util.Map;

/** Supplies defaults for character-specific activities, wardrobe, and subtitles. */
public final class CompanionPreferences {
    private CompanionPreferences() { }
    public static final Map<String, Object> DEFAULTS = Map.of(
            "autoActions", true, "battleActions", true,
            "activityMinSeconds", 45, "activityMaxSeconds", 120,
            "autoWardrobe", true, "wardrobeMinSeconds", 1800, "wardrobeMaxSeconds", 3600,
            "bubbleEnabled", true, "bubbleTheme", "rhodes");

    public static void defaults(JSONObject preferences) { DEFAULTS.forEach(preferences::putIfAbsent); }
}
