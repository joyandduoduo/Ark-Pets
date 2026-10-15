/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets;

/** Routes the former check name to the upstream-module integration check.
 * Compiles together with UpstreamCompanionCheck.java.
 */
public final class CompanionUiContract {
    public static void main(String[] args) { UpstreamCompanionCheck.Entry.main(args); }
    public static final class Entry {
        public static void main(String[] args) { UpstreamCompanionCheck.Entry.main(args); }
    }
}
