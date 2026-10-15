/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.controllers;

import cn.harryh.arkpets.ArkHomeFX;
import cn.harryh.arkpets.CompanionSupport;
import com.alibaba.fastjson2.JSONObject;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Binds companion additions inside the original behavior settings page. */
public final class CompanionModule implements Controller<ArkHomeFX> {
    @FXML private Label characterName, voiceDetails, voiceVolumeValue, companionStatus;
    @FXML private CheckBox voiceEnabled;
    @FXML private ComboBox<Integer> voiceMin, voiceMax;
    @FXML private Slider voiceVolume;
    @FXML private Button playVoice, stopCharacter, attack, skill1, skill2, skill3;
    private CompanionSupport support;
    private boolean updating;

    @Override public void initializeWith(ArkHomeFX app) {
        support = app.companions;
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
            gestures.get(i).setOnAction(e -> support.command(command));
        }
        support.onSelection = this::select;
        support.onStatus = this::refresh;
        select();
    }

    private void select() {
        if (support.selected == null) return;
        updating = true;
        characterName.setText("角色陪伴 · " + support.selected.getString("name"));
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
        support.save();
    }

    private void refresh() {
        if (support.selected == null) return;
        boolean running = support.running();
        JSONObject state = support.state();
        int count = support.selected.getJSONArray("voices").size();
        playVoice.setDisable(!running || count == 0);
        stopCharacter.setDisable(!running);
        List<Button> buttons = List.of(attack, skill1, skill2, skill3);
        buttons.forEach(b -> b.setDisable(true));
        String requested = support.controls.getString("skin") + "/" + support.controls.getString("mode");
        if (running && requested.equals(state.getString("variant")) && !"building".equals(support.controls.getString("mode")) && state.getJSONArray("gestures") != null)
            for (int i : state.getJSONArray("gestures").toJavaList(Integer.class)) if (i >= 0 && i < buttons.size()) buttons.get(i).setDisable(false);
        companionStatus.setText(support.statusText());
        if (count == 0) voiceDetails.setText("来源中未提供这位角色的普通话语音。");
        else {
            String text = count + " 条普通话语音 · 一轮内不重复，多角色错开播放";
            if (running && support.controls.getBooleanValue("voiceEnabled") && state.getLongValue("nextVoiceAt") > 0)
                text += "\n下次约 " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(state.getLongValue("nextVoiceAt")));
            if (running && state.getString("voiceTitle") != null && !state.getString("voiceTitle").isEmpty())
                text += "\n" + state.getString("voiceTitle") + "：" + state.getString("voiceText");
            voiceDetails.setText(text);
        }
    }
}
