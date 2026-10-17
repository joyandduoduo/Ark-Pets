/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import javafx.animation.PauseTransition;
import javafx.application.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Checks the character-specific preview control inside the unchanged upstream panel. */
public final class LingShowcaseUiCheck {
    private static Throwable failure;
    public static void main(String[] args) throws Exception {
        System.setProperty("arkpets.companion.catalog", args[0]);
        System.setProperty("javax.accessibility.assistive_technologies", "");
        cn.harryh.arkpets.utils.ArgPending.argCache = new String[0];
        Files.createDirectories(Path.of("temp"));
        for (int i = 0; i < Const.serverPorts.length; i++) Const.serverPorts[i] = 20786 + i;
        cn.harryh.arkpets.utils.Logger.initialize("logs/ling-ui-check", 2);
        Application.launch(View.class, args);
        if (failure != null) throw new AssertionError(failure);
        System.exit(0);
    }
    public static final class View extends Application {
        @Override public void start(Stage stage) throws Exception {
            ArkHomeFX app = new ArkHomeFX(); app.start(stage);
            PauseTransition pause = new PauseTransition(Duration.seconds(5));
            pause.setOnFinished(e -> {
                try {
                    if (app.modelsModule == null || app.behaviorModule == null || app.settingsModule == null) throw new AssertionError("Original modules missing");
                    app.companions.select("char_2023_ling");
                    Button preview = (Button) stage.getScene().lookup("#previewBattle");
                    if (preview == null || !preview.isVisible() || !preview.isManaged()) throw new AssertionError("Ling preview missing");
                    Label help = (Label) stage.getScene().lookup("#activityHelp");
                    if (!help.getText().contains("普攻一次")) throw new AssertionError("Ling help missing");
                    app.companions.select("char_2024_chyue");
                    if (preview.isVisible() || preview.isManaged()) throw new AssertionError("Trial leaked to another character");
                    app.companions.select("char_2023_ling");
                    app.rootModule.moduleWrapperComposer.activate(1);
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    var image = stage.getScene().getRoot().snapshot(null, null);
                    BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < png.getHeight(); y++)
                        for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                    ImageIO.write(png, "png", Path.of(getParameters().getRaw().get(1)).toFile());
                    Files.writeString(Path.of("ui-check.txt"), "Original modules present; Ling preview visible; other characters unchanged; native scene captured.");
                } catch (Throwable ex) { failure = ex; }
                finally { app.config.launcher_solid_exit = false; app.stop(); stage.close(); Platform.exit(); }
            }); pause.play();
        }
    }
}
