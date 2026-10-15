/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Resolves persistent voice selections, including an explicitly empty playlist. */
public final class CompanionVoices {
    private CompanionVoices() { }

    public static List<JSONObject> all(JSONObject character) {
        return character.getJSONArray("voices").toJavaList(JSONObject.class);
    }

    public static Set<String> selected(JSONObject character, JSONObject preferences) {
        JSONArray stored = preferences.getJSONArray("selectedVoiceIds");
        if (stored != null) return new LinkedHashSet<>(stored.toJavaList(String.class));
        Set<String> defaults = new LinkedHashSet<>();
        for (JSONObject voice : all(character))
            if (!voice.containsKey("defaultSelected") || voice.getBooleanValue("defaultSelected")) defaults.add(voice.getString("id"));
        return defaults;
    }

    public static List<JSONObject> enabled(JSONObject character, JSONObject preferences) {
        Set<String> ids = selected(character, preferences);
        return all(character).stream().filter(v -> ids.contains(v.getString("id"))).toList();
    }
}
