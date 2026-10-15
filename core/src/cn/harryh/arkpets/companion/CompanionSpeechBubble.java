/** Copyright (c) 2026, ArkPets contributors
 * At GPL-3.0 License
 */
package cn.harryh.arkpets.companion;

import cn.harryh.arkpets.platform.HWndCtrl;
import cn.harryh.arkpets.platform.User32HWndCtrl;
import cn.harryh.arkpets.utils.Logger;
import com.alibaba.fastjson2.JSONObject;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Displays stable, non-activating dialogue anchored to the pet's physical root. */
public final class CompanionSpeechBubble {
    private static final Set<Long> WINDOWS = ConcurrentHashMap.newKeySet();
    private static final Font FONT = loadFont();
    private volatile Point anchor = new Point();
    private volatile boolean visible;
    private volatile int x, y, width, height, moves;
    private volatile float progress;
    private volatile String error = "";
    private volatile boolean disposed;
    private volatile boolean hasFocus;
    private JDialog window;
    private DialoguePanel panel;
    private Timer timer;
    private long handle, shownAt, hiddenAt;
    private boolean below, topmost;
    private volatile String theme = "rhodes";

    private static Font loadFont() {
        try (var stream = CompanionSpeechBubble.class.getResourceAsStream("/fonts/SourceHanSansCN-Regular.otf")) {
            return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(16f);
        } catch (Exception ignored) { return new Font("Source Han Sans CN", Font.PLAIN, 16); }
    }

    /** Excludes the exact native speech window from platform and collision calculations. */
    public static boolean owns(HWndCtrl candidate) {
        if (!(candidate instanceof User32HWndCtrl nativeWindow)) return false;
        if (WINDOWS.stream().anyMatch(nativeWindow::hasNativeHandle)) return true;
        return candidate.windowText.startsWith("ArkPets Companion Voice ") && nativeWindow.isCompanionBubble();
    }

    public void show(String name, String title, String text, String theme, Point anchor, boolean topmost) {
        this.anchor = new Point(anchor);
        this.theme = theme;
        this.topmost = topmost;
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            try {
                ensureWindow();
                panel.message(name, title, text, theme);
                width = panel.getPreferredSize().width;
                height = panel.getPreferredSize().height;
                window.setSize(width, height);
                shownAt = System.currentTimeMillis();
                hiddenAt = 0;
                progress = 0;
                moves = 0;
                Rectangle work = workArea(this.anchor);
                Point logical = logicalPoint(this.anchor);
                below = logical.y - height - 10 < work.y;
                position(true);
                panel.alpha = 0;
                window.setAlwaysOnTop(topmost);
                window.setVisible(true);
                visible = true;
                timer.start();
            } catch (Exception failure) {
                error = failure.toString();
                Logger.error("CompanionBubble", "Speech bubble failed", failure);
                if (window != null) window.setVisible(false);
            }
        });
    }

    private void ensureWindow() {
        if (window != null) return;
        window = new JDialog((Frame) null, "ArkPets Companion Voice " + ProcessHandle.current().pid(), false);
        window.setUndecorated(true);
        window.setType(Window.Type.POPUP);
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setBackground(new Color(0, 0, 0, 0));
        panel = new DialoguePanel();
        panel.setOpaque(false);
        panel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { hide(); }
        });
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        window.setContentPane(panel);
        window.pack();
        handle = Pointer.nativeValue(Native.getWindowPointer(window));
        if (handle == 0) throw new IllegalStateException("Speech window has no native handle");
        User32HWndCtrl.markCompanionBubble(handle);
        WINDOWS.add(handle);
        timer = new Timer(40, event -> tick());
        timer.setCoalesce(true);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (hiddenAt > 0 && now - hiddenAt >= 180) {
            visible = false;
            window.setVisible(false);
            timer.stop();
            return;
        }
        panel.alpha = hiddenAt == 0 ? Math.min(1f, (now - shownAt) / 160f) : Math.max(0f, 1 - (now - hiddenAt) / 180f);
        panel.progress = Math.max(0, Math.min(1, progress));
        hasFocus = window.isFocused();
        panel.theme = theme;
        position(false);
        panel.repaint();
    }

    public void update(Point physicalAnchor, float progress) {
        anchor = new Point(physicalAnchor);
        this.progress = progress;
    }

    public void theme(String theme) { this.theme = theme; }

    public void hide() {
        if (!visible) return;
        SwingUtilities.invokeLater(() -> { if (visible && hiddenAt == 0) hiddenAt = System.currentTimeMillis(); });
    }

    private static GraphicsConfiguration screen(Point physical) {
        GraphicsConfiguration fallback = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle pixels = gc.getDefaultTransform().createTransformedShape(gc.getBounds()).getBounds();
            if (pixels.contains(physical)) return gc;
        }
        return fallback;
    }

    private static Point logicalPoint(Point physical) {
        try {
            var point = screen(physical).getDefaultTransform().inverseTransform(physical, null);
            return new Point((int) Math.round(point.getX()), (int) Math.round(point.getY()));
        } catch (Exception ignored) { return new Point(physical); }
    }

    private static Rectangle workArea(Point physical) {
        GraphicsConfiguration gc = screen(physical);
        Rectangle bounds = gc.getBounds();
        Insets inset = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(bounds.x + inset.left + 8, bounds.y + inset.top + 8,
                bounds.width - inset.left - inset.right - 16, bounds.height - inset.top - inset.bottom - 16);
    }

    /** Fits a fixed-size panel inside a work area without changing its chosen side. */
    public static Rectangle placement(Point head, Dimension size, Rectangle work, boolean below) {
        int x = Math.max(work.x, Math.min(head.x - size.width / 2, work.x + work.width - size.width));
        int y = below ? head.y + 12 : head.y - size.height - 10;
        y = Math.max(work.y, Math.min(y, work.y + work.height - size.height));
        return new Rectangle(x, y, size.width, size.height);
    }

    private void position(boolean initial) {
        Point head = logicalPoint(anchor);
        Rectangle box = placement(head, new Dimension(width, height), workArea(anchor), below);
        panel.tailX = Math.max(24, Math.min(width - 24, head.x - box.x));
        panel.topTail = below;
        if (initial || Math.abs(box.x - x) >= 2 || Math.abs(box.y - y) >= 2) {
            if (!initial) moves++;
            x = box.x; y = box.y;
            window.setLocation(x, y);
        }
    }

    public JSONObject status() {
        JSONObject state = new JSONObject();
        state.put("visible", visible); state.put("x", x); state.put("y", y);
        state.put("width", width); state.put("height", height); state.put("positionChanges", moves);
        state.put("nativeWindowExcluded", handle != 0 && WINDOWS.contains(handle));
        state.put("focusable", false); state.put("error", error);
        state.put("hasFocus", hasFocus);
        return state;
    }

    public void dispose() {
        disposed = true;
        SwingUtilities.invokeLater(() -> {
            if (timer != null) timer.stop();
            WINDOWS.remove(handle);
            visible = false;
            if (window != null) window.dispose();
        });
    }

    /** Exports the exact dialogue painter for visual inspection without capturing other apps. */
    public static BufferedImage preview(String name, String title, String text, String theme) {
        DialoguePanel panel = new DialoguePanel();
        panel.message(name, title, text, theme);
        panel.setSize(panel.getPreferredSize());
        panel.alpha = 1; panel.progress = .42f;
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        return image;
    }

    private static final class DialoguePanel extends JPanel {
        private String name = "", title = "", theme = "rhodes";
        private List<String> lines = List.of("");
        private float alpha = 1, progress;
        private int tailX = 190, rows = 1;
        private boolean topTail;

        void message(String name, String title, String text, String theme) {
            this.name = name; this.title = title; this.theme = theme;
            lines = wrap(text.replaceAll("<[^>]*>", ""), FONT, 332);
            rows = Math.min(5, Math.max(1, lines.size()));
            setPreferredSize(new Dimension(380, 88 + rows * 25));
        }

        private static List<String> wrap(String text, Font font, int width) {
            BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = measure.createGraphics(); g.setFont(font); FontMetrics metrics = g.getFontMetrics();
            List<String> result = new ArrayList<>(); StringBuilder line = new StringBuilder();
            for (int cp : text.codePoints().toArray()) {
                if (cp == '\r') continue;
                if (cp == '\n') { result.add(line.toString()); line.setLength(0); continue; }
                String next = new String(Character.toChars(cp));
                if (line.length() > 0 && metrics.stringWidth(line + next) > width) { result.add(line.toString()); line.setLength(0); }
                line.append(next);
            }
            if (line.length() > 0 || result.isEmpty()) result.add(line.toString());
            g.dispose(); return result;
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, Math.min(1, alpha))));
            int w = getWidth(), h = getHeight(), top = topTail ? 13 : 3, bottom = topTail ? h - 4 : h - 15;
            boolean light = "light".equals(theme), terminal = "terminal".equals(theme);
            Color background = light ? new Color(239, 243, 246, 215) : terminal ? new Color(8, 27, 37, 205) : new Color(18, 23, 29, 205);
            Color ink = light ? new Color(28, 38, 46) : new Color(235, 240, 244);
            Color muted = light ? new Color(94, 110, 123) : new Color(152, 169, 184);
            Color accent = light ? new Color(30, 105, 151) : new Color(100, 202, 241);
            Path2D body = new Path2D.Float();
            body.moveTo(12, top); body.lineTo(w - 16, top); body.lineTo(w - 4, top + 12);
            body.lineTo(w - 4, bottom); body.lineTo(4, bottom); body.lineTo(4, top + 8); body.closePath();
            g.setColor(new Color(0, 0, 0, 35)); g.translate(0, 2); g.fill(body); g.translate(0, -2);
            g.setColor(background); g.fill(body);
            Path2D tail = new Path2D.Float();
            tail.moveTo(tailX - 9, topTail ? top + 1 : bottom - 1);
            tail.lineTo(tailX, topTail ? 2 : h - 3); tail.lineTo(tailX + 9, topTail ? top + 1 : bottom - 1); tail.closePath();
            g.fill(tail);
            g.setStroke(new BasicStroke(1)); g.setColor(light ? new Color(154, 174, 187, 190) : new Color(180, 202, 217, 90)); g.draw(body);
            g.setFont(FONT.deriveFont(Font.BOLD, 16f)); g.setColor(ink); g.drawString(name, 24, top + 29);
            g.setFont(FONT.deriveFont(10f)); g.setColor(accent); g.drawString("RHODES ISLAND / VOICE", 208, top + 28);
            g.setColor(light ? new Color(170, 187, 199, 120) : new Color(145, 171, 189, 60)); g.drawLine(24, top + 43, w - 24, top + 43);
            int pages = (lines.size() + rows - 1) / rows;
            int page = Math.min(pages - 1, (int) (Math.max(0, Math.min(.999f, progress)) * pages));
            g.setFont(FONT); g.setColor(ink);
            for (int row = 0; row < rows; row++) {
                int index = page * rows + row;
                if (index < lines.size()) g.drawString(lines.get(index), 24, top + 68 + row * 25);
            }
            g.setFont(FONT.deriveFont(10f)); g.setColor(muted);
            g.drawString(pages > 1 ? "普通话  /  " + (page + 1) + " · " + pages : "普通话  /  点击收起", 24, bottom - 12);
            g.setColor(accent); g.fillRect(24, bottom - 5, Math.round((w - 48) * progress), 2);
            g.dispose();
        }
    }
}
