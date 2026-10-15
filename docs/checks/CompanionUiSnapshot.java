/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Exports this app's scene, without capturing the Windows desktop or login screen. */
public final class CompanionUiSnapshot {
    public static void main(String[] args) { Application.launch(View.class, args); }
    public static final class View extends Application {
        @Override public void start(Stage stage) throws Exception {
            System.setProperty("arkpets.companion.catalog", getParameters().getNamed().get("catalog"));
            ArkHomeFX app = new ArkHomeFX();
            app.start(stage);
            PauseTransition delay = new PauseTransition(Duration.seconds(4));
            delay.setOnFinished(event -> {
                try {
                    var image = stage.getScene().getRoot().snapshot(null, null);
                    BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    var reader = image.getPixelReader();
                    for (int y = 0; y < png.getHeight(); y++)
                        for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, reader.getArgb(x, y));
                    ImageIO.write(png, "png", Path.of(getParameters().getNamed().get("output")).toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { app.stop(); stage.close(); Platform.exit(); }
            });
            delay.play();
        }
    }
}
