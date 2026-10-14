/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads the small, explicitly selected character collection. */
public final class CompanionCatalog {
    public final Path directory;
    public final JSONObject data;

    public CompanionCatalog(Path file) throws IOException {
        directory = file.toAbsolutePath().normalize().getParent();
        data = JSONObject.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        if (data == null || data.getJSONArray("characters") == null)
            throw new IOException("角色目录格式无效");
    }

    public List<JSONObject> characters() {
        return data.getJSONArray("characters").toJavaList(JSONObject.class);
    }

    public JSONObject character(String id) {
        return characters().stream().filter(c -> id.equals(c.getString("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到角色：" + id));
    }

    public JSONObject model(JSONObject character, String skinId, String mode) {
        for (JSONObject skin : character.getJSONArray("skins").toJavaList(JSONObject.class))
            if (skinId.equals(skin.getString("id"))) {
                JSONObject model = skin.getJSONObject("models").getJSONObject(mode);
                if (model != null) return model;
            }
        throw new IllegalArgumentException("该外观没有所选模型");
    }

    public Path resolve(String relative) {
        Path path = directory.resolve(relative).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("素材路径超出角色目录");
        return path;
    }

    public Path controls(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("角色编号无效");
        Path runtime = directory.resolve("runtime");
        Files.createDirectories(runtime);
        return runtime.resolve(id + ".json");
    }
}
