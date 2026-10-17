/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.controllers;

import cn.harryh.arkpets.ArkHomeFX;
import cn.harryh.arkpets.CompanionSupport;
import cn.harryh.arkpets.companion.CompanionVoices;
import cn.harryh.arkpets.animations.CompanionShowcase;
import com.alibaba.fastjson2.JSONObject;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import javafx.util.StringConverter;

/** Binds companion additions inside the original behavior settings page. */
public final class CompanionModule implements Controller<ArkHomeFX> {
    @FXML private Label characterName, voiceDetails, voiceVolumeValue, companionStatus;
    @FXML private CheckBox voiceEnabled;
    @FXML private ComboBox<Integer> voiceMin, voiceMax;
    @FXML private Slider voiceVolume;
    @FXML private Button playVoice, stopCharacter, attack, skill1, skill2, skill3;
    @FXML private CheckBox autoActions, battleActions, autoWardrobe, bubbleEnabled;
    @FXML private ComboBox<Integer> activityMin, activityMax, wardrobeMin, wardrobeMax;
    @FXML private ComboBox<String> bubbleTheme;
    @FXML private Button changeWardrobe, tryActivity, previewBattle, previewBubble, chooseVoices;
    @FXML private Label activityHelp, wardrobeDetails, battleHelp;
    private CompanionSupport support;
    private boolean updating;

    @Override public void initializeWith(ArkHomeFX app) {
        support = app.companions;
        activityMin.getItems().setAll(15, 30, 45, 60, 90, 120, 180, 300);
        activityMax.getItems().setAll(activityMin.getItems());
        wardrobeMin.getItems().setAll(5, 10, 15, 20, 30, 45, 60, 90, 120);
        wardrobeMax.getItems().setAll(wardrobeMin.getItems());
        bindInterval(activityMin, activityMax);
        bindInterval(wardrobeMin, wardrobeMax);
        for (CheckBox toggle : List.of(autoActions, battleActions, autoWardrobe, bubbleEnabled)) toggle.setOnAction(e -> save());
        bubbleTheme.getItems().setAll("rhodes", "light", "terminal");
        bubbleTheme.setConverter(new StringConverter<>() {
            public String toString(String value) { return Map.of("rhodes", "罗德岛 · 深色", "light", "战术白", "terminal", "蓝调终端").getOrDefault(value == null ? "" : value, ""); }
            public String fromString(String value) { return value; }
        });
        bubbleTheme.valueProperty().addListener((o, old, value) -> { if (!updating) save(); });
        changeWardrobe.setOnAction(e -> support.command("wardrobe"));
        tryActivity.setOnAction(e -> support.command("activity"));
        previewBattle.setOnAction(e -> support.command("showcase"));
        previewBubble.setOnAction(e -> support.command("previewBubble"));
        chooseVoices.setOnAction(e -> new CompanionVoicePicker(support).open(chooseVoices.getScene().getWindow()));
        for (int i = 1; i <= 120; i++) { voiceMin.getItems().add(i); voiceMax.getItems().add(i); }
        voiceEnabled.setOnAction(e -> save());
        voiceMin.valueProperty().addListener((o, old, value) -> {
            if (!updating && value != null) {
                if (voiceMax.getValue() != null && value > voiceMax.getValue()) voiceMax.setValue(value);
                save();
            }
        });
        voiceMax.valueProperty().addListener((o, old, value) -> {
            if (!updating && value != null) {
                if (voiceMin.getValue() != null && value < voiceMin.getValue()) voiceMin.setValue(value);
                save();
            }
        });
        voiceVolume.valueProperty().addListener((o, old, value) -> {
            voiceVolumeValue.setText(Math.round(value.doubleValue()) + "%");
            if (!updating) save();
        });
        playVoice.setOnAction(e -> support.command("voice"));
        stopCharacter.setOnAction(e -> support.command("exit"));
        List<Button> gestures = List.of(attack, skill1, skill2, skill3);
        for (int i = 0; i < gestures.size(); i++) {
            String command = i == 0 ? "attack" : "skill" + i;
            gestures.get(i).setOnAction(e -> support.command(
                    CompanionShowcase.supports(support.selected.getString("id")) && (command.equals("skill2") || command.equals("skill3"))
                            ? "showcase" + command.substring(5) : command));
        }
        support.onSelection = this::select;
        support.onStatus = this::refresh;
        select();
    }

    private void select() {
        if (support.selected == null) return;
        updating = true;
        characterName.setText("角色陪伴 · " + support.selected.getString("name"));
        autoActions.setSelected(support.controls.getBooleanValue("autoActions"));
        battleActions.setSelected(support.controls.getBooleanValue("battleActions"));
        autoWardrobe.setSelected(support.controls.getBooleanValue("autoWardrobe"));
        bubbleEnabled.setSelected(support.controls.getBooleanValue("bubbleEnabled"));
        bubbleTheme.setValue(support.controls.getString("bubbleTheme"));
        activityMin.setValue(support.controls.getIntValue("activityMinSeconds"));
        activityMax.setValue(support.controls.getIntValue("activityMaxSeconds"));
        wardrobeMin.setValue(Math.max(1, support.controls.getIntValue("wardrobeMinSeconds") / 60));
        wardrobeMax.setValue(Math.max(1, support.controls.getIntValue("wardrobeMaxSeconds") / 60));
        autoWardrobe.setDisable(support.selected.getJSONArray("skins").size() < 2);
        voiceEnabled.setSelected(support.controls.getBooleanValue("voiceEnabled"));
        voiceMin.setValue(Math.max(1, support.controls.getIntValue("voiceMinSeconds") / 60));
        voiceMax.setValue(Math.max(1, support.controls.getIntValue("voiceMaxSeconds") / 60));
        voiceVolume.setValue(support.controls.getDoubleValue("volume") * 100);
        voiceVolumeValue.setText(Math.round(voiceVolume.getValue()) + "%");
        boolean missing = support.selected.getJSONArray("voices").isEmpty();
        voiceEnabled.setDisable(missing);
        voiceMin.setDisable(missing);
        voiceMax.setDisable(missing);
        voiceVolume.setDisable(missing);
        updating = false;
        refresh();
    }

    private void save() {
        if (updating || support.selected == null || voiceMin.getValue() == null || voiceMax.getValue() == null) return;
        support.controls.put("voiceEnabled", voiceEnabled.isSelected());
        support.controls.put("voiceMinSeconds", voiceMin.getValue() * 60);
        support.controls.put("voiceMaxSeconds", voiceMax.getValue() * 60);
        support.controls.put("volume", voiceVolume.getValue() / 100);
        support.controls.put("autoActions", autoActions.isSelected());
        support.controls.put("battleActions", battleActions.isSelected());
        support.controls.put("autoWardrobe", autoWardrobe.isSelected());
        support.controls.put("bubbleEnabled", bubbleEnabled.isSelected());
        if (bubbleTheme.getValue() != null) support.controls.put("bubbleTheme", bubbleTheme.getValue());
        if (activityMin.getValue() != null && activityMax.getValue() != null) {
            support.controls.put("activityMinSeconds", activityMin.getValue());
            support.controls.put("activityMaxSeconds", activityMax.getValue());
        }
        if (wardrobeMin.getValue() != null && wardrobeMax.getValue() != null) {
            support.controls.put("wardrobeMinSeconds", wardrobeMin.getValue() * 60);
            support.controls.put("wardrobeMaxSeconds", wardrobeMax.getValue() * 60);
        }
        support.save();
    }

    private void bindInterval(ComboBox<Integer> min, ComboBox<Integer> max) {
        min.valueProperty().addListener((o, old, value) -> {
            if (updating || value == null) return;
            if (max.getValue() != null && value > max.getValue()) max.setValue(value);
            save();
        });
        max.valueProperty().addListener((o, old, value) -> {
            if (updating || value == null) return;
            if (min.getValue() != null && value < min.getValue()) min.setValue(value);
            save();
        });
    }

    private void refresh() {
        if (support.selected == null) return;
        boolean running = support.running();
        boolean showcase = CompanionShowcase.supports(support.selected.getString("id"));
        previewBattle.setVisible(showcase); previewBattle.setManaged(showcase);
        previewBattle.setDisable(!running || support.state().getBooleanValue("battleShowcase"));
        activityHelp.setText(showcase
                ? "令：入场 → 战斗待机 → 普攻一次 → 二、三技能交替展示 → 退场；正面含 Die。二技能间隔施放两次，三技能多停留。点击角色可提前结束。"
                : "穿插互动、坐下与短战斗动作；结束后回日常。拖拽、手动模式和说话时会让路。");
        JSONObject state = support.state();
        wardrobeDetails.setText(!support.controls.getBooleanValue("autoWardrobe") ? "自动换装已关闭"
                : !running ? "启动后自动换装；倒计时会在重启后继续。"
                : state.getBooleanValue("wardrobePending") ? "已到换装时间，当前动作或语音结束后换装。"
                : state.getLongValue("nextWardrobeAt") > 0 ? "下次换装约 " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.getLongValue("nextWardrobeAt")))
                : "正在读取换装时间…");
        battleHelp.setText(showcase ? "技能 2 / 3 可直接预览完整展示。当前素材无独立技能 1 动作；正面含入场和 Die。"
                : "在模型页切到战斗正面或背面后可用；播放结束返回待机。");
        int count = support.selected.getJSONArray("voices").size();
        int enabled = CompanionVoices.enabled(support.selected, support.controls).size();
        playVoice.setDisable(!running || enabled == 0);
        stopCharacter.setDisable(!running);
        changeWardrobe.setDisable(!running || support.selected.getJSONArray("skins").size() < 2);
        tryActivity.setDisable(!running);
        previewBubble.setDisable(!running || count == 0);
        List<Button> buttons = List.of(attack, skill1, skill2, skill3);
        buttons.forEach(b -> b.setDisable(true));
        String requested = support.controls.getString("skin") + "/" + support.controls.getString("mode");
        if (running && requested.equals(state.getString("variant")) && !"building".equals(support.controls.getString("mode")) && state.getJSONArray("gestures") != null)
            for (int i : state.getJSONArray("gestures").toJavaList(Integer.class)) if (i >= 0 && i < buttons.size()) buttons.get(i).setDisable(false);
        if (showcase && running) {
            boolean busy = state.getBooleanValue("battleShowcase") || state.getBooleanValue("transitioning")
                    || state.getBooleanValue("voicePlaying") || (state.getString("activity") != null && !state.getString("activity").isEmpty());
            skill2.setDisable(busy); skill3.setDisable(busy);
        }
        companionStatus.setText(support.statusText());
        if (count == 0) voiceDetails.setText("来源中未提供这位角色的普通话语音。");
        else {
            String text = "已勾选 " + enabled + " / " + count + " 条普通话语音 · 一轮内不重复";
            if (enabled == 0) text += "\n尚未勾选语音，轮播保持安静。";
            if (running && support.controls.getBooleanValue("voiceEnabled") && state.getLongValue("nextVoiceAt") > 0)
                text += "\n下次约 " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.getLongValue("nextVoiceAt")));
            if (running && state.getString("voiceTitle") != null && !state.getString("voiceTitle").isEmpty())
                text += "\n最近播放：" + state.getString("voiceTitle");
            voiceDetails.setText(text);
        }
    }
}
