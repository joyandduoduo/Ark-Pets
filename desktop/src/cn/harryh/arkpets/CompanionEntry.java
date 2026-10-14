/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

/** Starts JavaFX from the bundled classpath, matching the upstream launcher. */
public final class CompanionEntry {
    public static void main(String[] args) {
        javafx.application.Application.launch(CompanionLauncher.class, args);
    }
}
