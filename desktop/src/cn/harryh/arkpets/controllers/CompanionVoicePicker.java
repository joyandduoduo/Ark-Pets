/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.controllers;

import cn.harryh.arkpets.CompanionSupport;
import cn.harryh.arkpets.companion.CompanionVoices;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.StringConverter;
import java.util.*;

/** Keeps a per-character playlist inside the upstream launcher's control flow. */
public final class CompanionVoicePicker {
    private final CompanionSupport support;
    private final Set<String> selected;
    private final List<JSONObject> voices;
    private final ComboBox<JSONObject> source = new ComboBox<>();
    private final TextField search = new TextField();
    private final ListView<JSONObject> list = new ListView<>();
    private final Label count = new Label(), availability = new Label();

    public CompanionVoicePicker(CompanionSupport support) {
        this.support = support;
        voices = CompanionVoices.all(support.selected);
        selected = CompanionVoices.selected(support.selected, support.controls);
    }

    public Stage open(Window owner) {
        Stage stage = new Stage(); stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(support.selected.getString("name") + " · 轮播语音");
        Label title = new Label("选择想听的语音"); title.setStyle("-fx-font-size: 22; -fx-font-weight: bold; -fx-text-fill: #263944;");
        Label hint = new Label("勾选后立即保存。不同本体、异格与皮肤的语音可以混选；全部取消后保持安静。");
        hint.setWrapText(true); hint.setStyle("-fx-text-fill: #536877;");
        source.setId("voiceSource"); source.setMaxWidth(Double.MAX_VALUE);
        source.setConverter(new StringConverter<>() {
            public String toString(JSONObject value) { return value == null ? "" : value.getString("name"); }
            public JSONObject fromString(String value) { return null; }
        });
        source.getItems().add(new JSONObject(Map.of("id", "*", "name", "全部语音来源")));
        if (support.selected.getJSONArray("voiceSources") != null) source.getItems().addAll(support.selected.getJSONArray("voiceSources").toJavaList(JSONObject.class));
        source.getSelectionModel().selectFirst();
        search.setId("voiceSearch"); search.setPromptText("搜索名称或台词");
        HBox filters = new HBox(12, source, search); HBox.setHgrow(source, Priority.ALWAYS); HBox.setHgrow(search, Priority.ALWAYS);
        list.setId("voiceList"); list.setPlaceholder(new Label("此来源暂无可播放的普通话语音。"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(JSONObject voice, boolean empty) {
                super.updateItem(voice, empty); setText(null);
                if (empty || voice == null) { setGraphic(null); return; }
                JFXCheckBox check = new JFXCheckBox(voice.getString("title")); check.setSelected(selected.contains(voice.getString("id")));
                check.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
                check.setOnAction(e -> { if (check.isSelected()) selected.add(voice.getString("id")); else selected.remove(voice.getString("id")); persist(); });
                Label group = new Label(Objects.requireNonNullElse(voice.getString("sourceName"), support.selected.getString("name")));
                group.setStyle("-fx-font-size: 11; -fx-text-fill: #6d8190;");
                Label text = new Label(voice.getString("text").replaceAll("<[^>]*>", "")); text.setWrapText(true);
                text.setStyle("-fx-text-fill: #344b59; -fx-font-size: 13;");
                text.maxWidthProperty().bind(list.widthProperty().subtract(140));
                VBox body = new VBox(7, check, group, text); HBox.setHgrow(body, Priority.ALWAYS);
                JFXButton play = button("试听"); play.setDisable(!support.running());
                play.setOnAction(e -> support.command("voice:" + voice.getString("id")));
                HBox row = new HBox(18, body, play); row.setAlignment(Pos.TOP_LEFT); row.setPadding(new Insets(12, 10, 12, 8));
                setGraphic(row);
            }
        });
        source.valueProperty().addListener((o, before, value) -> filter());
        search.textProperty().addListener((o, before, value) -> filter());
        JFXButton all = button("勾选当前列表"), none = button("取消当前列表"), stop = button("停止试听"), done = button("完成");
        all.setId("voiceSelectAll"); none.setId("voiceSelectNone");
        all.setOnAction(e -> { list.getItems().forEach(v -> selected.add(v.getString("id"))); persist(); list.refresh(); });
        none.setOnAction(e -> { list.getItems().forEach(v -> selected.remove(v.getString("id"))); persist(); list.refresh(); });
        stop.setDisable(!support.running()); stop.setOnAction(e -> support.command("stopVoice")); done.setOnAction(e -> stage.close());
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox actions = new HBox(10, all, none, space, stop, done); actions.setAlignment(Pos.CENTER_LEFT);
        count.setId("voiceSelectedCount"); count.setStyle("-fx-text-fill: #0879b5; -fx-font-weight: bold;");
        availability.setStyle("-fx-text-fill: #607482;"); availability.setWrapText(true);
        VBox root = new VBox(13, title, hint, filters, count, availability, list, actions);
        root.setPadding(new Insets(24)); root.setStyle("-fx-background-color: #f4f7f9; -fx-font-family: 'Source Han Sans CN';");
        VBox.setVgrow(list, Priority.ALWAYS);
        stage.setScene(new Scene(root, 720, 650)); stage.setMinWidth(620); stage.setMinHeight(480);
        filter(); stage.show(); return stage;
    }

    private static JFXButton button(String text) {
        JFXButton button = new JFXButton(text);
        button.setStyle("-fx-background-color: #e5edf3; -fx-text-fill: #126b9a; -fx-padding: 8 12; -fx-background-radius: 3;");
        return button;
    }

    private void filter() {
        JSONObject group = source.getValue(); String id = group.getString("id"), query = search.getText().trim().toLowerCase(Locale.ROOT);
        list.getItems().setAll(voices.stream().filter(v -> id.equals("*") || id.equals(v.getString("sourceId")))
                .filter(v -> (v.getString("title") + v.getString("text") + v.getString("sourceName")).toLowerCase(Locale.ROOT).contains(query)).toList());
        String missing = group.containsKey("expectedCount") && group.getIntValue("expectedCount") > group.getIntValue("availableCount")
                ? "此来源有 " + (group.getIntValue("expectedCount") - group.getIntValue("availableCount")) + " 条录音尚未获取。 " : "";
        availability.setText(missing + (support.running() ? "试听会播放这一条，且不会改变勾选。" : "先启动这位角色，即可逐条试听。"));
        updateCount();
    }

    private void persist() {
        support.controls.put("selectedVoiceIds", new JSONArray(selected)); support.save(); updateCount(); support.onStatus.run();
    }

    private void updateCount() {
        long total = voices.stream().filter(v -> selected.contains(v.getString("id"))).count();
        count.setText("已勾选 " + total + " / " + voices.size() + " 条  ·  当前列表 " + list.getItems().size() + " 条" + (total == 0 ? "  ·  轮播暂停" : ""));
    }
}
