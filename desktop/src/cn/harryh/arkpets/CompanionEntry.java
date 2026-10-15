/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

/** Starts JavaFX from the bundled classpath, matching the upstream launcher. */
public final class CompanionEntry {
    public static void main(String[] args) {
        String catalog = "companion-data/catalog.json";
        for (String arg : args)
            if (arg.startsWith("--catalog=")) catalog = arg.substring("--catalog=".length());
        System.setProperty("arkpets.companion.catalog", java.nio.file.Path.of(catalog).toAbsolutePath().toString());
        DesktopLauncher.main(new String[0]);
    }
}
