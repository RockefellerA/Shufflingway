package shufflingway;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;

import javax.imageio.ImageIO;
import javax.swing.AbstractListModel;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import scraper.AppPaths;
import shufflingway.audio.Sound;
import shufflingway.audio.SoundEffects;
import shufflingway.graphics.TintedToggleButton;

/**
 * A binder view of a card table: the table's rows, in the table's current sort and filter, laid
 * out as a grid of card images. Binder index {@code i} is table view row {@code i}, and the two
 * share one selection, so whatever is wired to the table's selection (the preview, the Add
 * buttons) keeps working while the binder is the one on screen.
 *
 * <p>Only images already stored in the database are shown; the binder never downloads. A card
 * with no stored image is drawn as its name over the default cardback, in its element's colour.
 * Images are decoded on one background thread, most recently requested first, so the cells in
 * view load ahead of ones scrolled past.
 */
final class CardBinderView extends JList<Integer> {

    static final int THUMB_W = 143;
    static final int THUMB_H = 200;
    private static final int GAP = 5;

    /** Thumbnails kept in memory; one is ~115 KB, so this is ~35 MB at most. */
    private static final int CACHE_SIZE = 300;
    /** Loads waiting beyond this are dropped oldest-first; they were scrolled past. */
    private static final int MAX_QUEUED = 120;

    private static final float NAME_MAX_SIZE = 20f;
    private static final float NAME_MIN_SIZE = 10f;
    private static final int   NAME_PAD      = 10;
    /** The serial under the name is this fraction of the name's size, and never below the floor. */
    private static final float SERIAL_SCALE    = 0.75f;
    private static final float SERIAL_MIN_SIZE = 8f;
    private static final int   SERIAL_GAP      = 3;

    /** The wash on whichever of the list/binder buttons is pressed in; their icons tell them apart. */
    private static final Color VIEW_TINT = new Color(70, 140, 230);

    private final JTable table;
    private final int serialCol;
    private final int nameCol;
    private final int elementCol;
    private final RowListModel listModel = new RowListModel();

    /** Serial → image URL for every card with a stored image; null until read. EDT only. */
    private Map<String, String> storedUrlBySerial;
    private boolean storedIndexRequested;

    private final Map<String, BufferedImage> thumbs = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private record Job(String serial, String url) {}
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private Thread worker;

    private BufferedImage cardback;
    private boolean syncing;
    private boolean refreshQueued;

    /**
     * @param table      the table to mirror; its row sorter, if any, must already be installed
     * @param serialCol  model column holding the serial
     * @param nameCol    model column holding the card name
     * @param elementCol model column holding the element ("Fire", "Water/Fire", …)
     */
    CardBinderView(JTable table, int serialCol, int nameCol, int elementCol) {
        this.table = table;
        this.serialCol = serialCol;
        this.nameCol = nameCol;
        this.elementCol = elementCol;

        setModel(listModel);
        setLayoutOrientation(HORIZONTAL_WRAP);
        setVisibleRowCount(-1);
        setFixedCellWidth(THUMB_W + 2 * GAP);
        setFixedCellHeight(THUMB_H + 2 * GAP);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setCellRenderer(new TileRenderer());

        table.getModel().addTableModelListener(e -> scheduleRefresh());
        if (table.getRowSorter() != null) table.getRowSorter().addRowSorterListener(e -> scheduleRefresh());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) syncFromTable();
        });
        addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) syncToTable();
        });
        listModel.resize();
    }

    /**
     * A table and its binder stacked in one panel, with the list/binder switch between them.
     *
     * @param content the table's and the binder's scroll panes; add it where the table's went
     * @param toggle  the two joined view buttons; {@link #barWithToggle} places them
     * @param binder  the binder, for wiring clicks and {@link #shutdown} to its window
     */
    record Switchable(JPanel content, JPanel toggle, CardBinderView binder) {
        /** {@code bar} (a left-flowing tool bar) with the toggle pinned to its right edge. */
        JPanel barWithToggle(JPanel bar) {
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            right.add(toggle);
            JPanel row = new JPanel(new BorderLayout());
            row.add(bar,   BorderLayout.CENTER);
            row.add(right, BorderLayout.EAST);
            return row;
        }

        /** Whole binder columns that fit {@code window} at its current size. */
        int columnsThatFit(Window window) {
            JScrollPane sp = layOut(window);
            return Math.max(1, (sp.getWidth() - chromeWidth(sp)) / binder.getFixedCellWidth());
        }

        /**
         * Sets {@code window}'s width so the binder shows exactly {@code columns} columns with no
         * slack beside them, its vertical scroll bar allowed for. Call once the window's content
         * is in place and before it is shown; the height is kept.
         */
        void fitWindowToColumns(Window window, int columns) {
            JScrollPane sp = layOut(window);
            int outside = window.getWidth() - sp.getWidth();
            window.setSize(outside + chromeWidth(sp) + columns * binder.getFixedCellWidth(),
                    window.getHeight());
        }

        /** Lays the window out at its current size and returns the binder's scroll pane. */
        private JScrollPane layOut(Window window) {
            if (!window.isDisplayable()) window.addNotify();  // so the frame insets are real
            window.validate();
            return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, binder);
        }

        /** The scroll pane's border plus its vertical scroll bar, which the cards cannot use. */
        private static int chromeWidth(JScrollPane sp) {
            Insets in = sp.getInsets();
            return in.left + in.right + sp.getVerticalScrollBar().getPreferredSize().width;
        }
    }

    /**
     * Builds a {@link Switchable} over {@code table}, which must already have its row sorter.
     * The list shows first. Column arguments are as for the constructor.
     */
    static Switchable around(JTable table, int serialCol, int nameCol, int elementCol) {
        CardBinderView binder = new CardBinderView(table, serialCol, nameCol, elementCol);
        JScrollPane binderScroll = new JScrollPane(binder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        binderScroll.getVerticalScrollBar().setUnitIncrement(THUMB_H / 4);

        CardLayout views = new CardLayout();
        JPanel content = new JPanel(views);
        content.add(new JScrollPane(table), "list");
        content.add(binderScroll, "binder");

        // The game window's Auto/Wait pair: the chosen view sits pressed in and tinted, and a click
        // on it is swallowed, so only an actual switch ticks.
        int iconSize = UiScale.scale(14);
        TintedToggleButton listBtn   = new TintedToggleButton(listIcon(iconSize), VIEW_TINT);
        TintedToggleButton binderBtn = new TintedToggleButton(tileIcon(iconSize), VIEW_TINT);
        listBtn.setToolTipText("List view");
        binderBtn.setToolTipText("Binder view");
        ButtonGroup group = new ButtonGroup();
        JPanel toggle = new JPanel(new GridLayout(1, 2, 0, 0));
        for (TintedToggleButton b : List.of(listBtn, binderBtn)) {
            b.setMargin(new Insets(2, 8, 2, 8));
            b.setFocusable(false);
            group.add(b);
            toggle.add(b);
        }
        listBtn.setSelected(true);
        listBtn.addActionListener(e -> {
            SoundEffects.play(Sound.TICK);
            views.show(content, "list");
            int row = table.getSelectedRow();
            if (row >= 0) table.scrollRectToVisible(table.getCellRect(row, 0, true));
        });
        binderBtn.addActionListener(e -> {
            SoundEffects.play(Sound.TICK);
            views.show(content, "binder");
            binder.revealSelection();
        });
        return new Switchable(content, toggle, binder);
    }

    /** Scrolls the table's selected card into view; call when the binder is brought on screen. */
    void revealSelection() {
        int i = getSelectedIndex();
        if (i >= 0) ensureIndexIsVisible(i);
    }

    /**
     * Takes a card image fetched elsewhere — the preview downloads what the binder will not — so
     * that card's placeholder turns into the card. EDT only.
     *
     * @param thumb the image already reduced by {@link #thumbnail}
     */
    void imageLoaded(String serial, String url, BufferedImage thumb) {
        if (thumb == null) return;
        thumbs.put(serial, thumb);
        if (storedUrlBySerial != null && url != null) storedUrlBySerial.put(serial, url);
        repaint();
    }

    /** A card image reduced to binder size; safe to call off the EDT. Null if it has no size. */
    static BufferedImage thumbnail(Image img) {
        return img == null ? null : scale(img, THUMB_W, THUMB_H);
    }

    /** Three stacked bars, for a "list view" button; drawn in the button's text colour. */
    static Icon listIcon(int size) {
        return new ViewIcon(size, false);
    }

    /** A two-by-two grid of squares, for a "binder view" button; drawn in the button's text colour. */
    static Icon tileIcon(int size) {
        return new ViewIcon(size, true);
    }

    private record ViewIcon(int size, boolean tiles) implements Icon {
        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.isEnabled() ? c.getForeground() : Color.GRAY);
                g2.translate(x, y);
                if (tiles) {
                    int gap = Math.max(2, size / 7);
                    int cell = (size - gap) / 2;
                    for (int r = 0; r < 2; r++)
                        for (int col = 0; col < 2; col++)
                            g2.fillRoundRect(col * (cell + gap), r * (cell + gap), cell, cell, 2, 2);
                } else {
                    int bar = Math.max(2, size / 6);
                    int step = (size - bar) / 2;
                    for (int i = 0; i < 3; i++)
                        g2.fillRoundRect(0, i * step, size, bar, 2, 2);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Stops the image thread. The binder still paints afterwards, from what it already has. */
    void shutdown() {
        queue.clear();
        pending.clear();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    // -------------------------------------------------------------------------
    // Mirroring the table
    // -------------------------------------------------------------------------

    /** The table's model listener can run before the table updates its sorter, so defer a tick. */
    private void scheduleRefresh() {
        if (refreshQueued) return;
        refreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            refreshQueued = false;
            syncing = true;
            try {
                listModel.resize();
            } finally {
                syncing = false;
            }
            syncFromTable();
        });
    }

    private void syncFromTable() {
        if (syncing) return;
        syncing = true;
        try {
            int row = table.getSelectedRow();
            if (row < 0 || row >= listModel.getSize()) {
                clearSelection();
            } else {
                setSelectedIndex(row);
                if (isShowing()) ensureIndexIsVisible(row);
            }
        } finally {
            syncing = false;
        }
    }

    private void syncToTable() {
        if (syncing) return;
        syncing = true;
        try {
            int i = getSelectedIndex();
            if (i < 0) table.clearSelection();
            else if (i < table.getRowCount()) table.setRowSelectionInterval(i, i);
        } finally {
            syncing = false;
        }
    }

    /** Binder index → table model row, in the table's view order. */
    private final class RowListModel extends AbstractListModel<Integer> {
        private int size;

        @Override public int getSize() { return size; }

        @Override public Integer getElementAt(int index) {
            return index < table.getRowCount() ? table.convertRowIndexToModel(index) : -1;
        }

        void resize() {
            if (size > 0) fireIntervalRemoved(this, 0, size - 1);
            size = table.getRowCount();
            if (size > 0) fireIntervalAdded(this, 0, size - 1);
        }
    }

    // -------------------------------------------------------------------------
    // Images
    // -------------------------------------------------------------------------

    /** The card's thumbnail if one is ready; otherwise null, having asked for it if it exists. */
    private BufferedImage thumbFor(String serial) {
        BufferedImage t = thumbs.get(serial);
        if (t != null) return t;
        if (storedUrlBySerial == null) {
            requestStoredIndex();
            return null;
        }
        String url = storedUrlBySerial.get(serial);
        if (url != null) request(new Job(serial, url));
        return null;
    }

    /** Whether the card is known to have no stored image, so its placeholder should be named. */
    private boolean hasNoStoredImage(String serial) {
        return storedUrlBySerial != null && !storedUrlBySerial.containsKey(serial);
    }

    private void requestStoredIndex() {
        if (storedIndexRequested) return;
        storedIndexRequested = true;
        new SwingWorker<Map<String, String>, Void>() {
            @Override
            protected Map<String, String> doInBackground() throws SQLException {
                Map<String, String> map = new HashMap<>();
                try (Connection conn = DriverManager.getConnection(AppPaths.dbUrl());
                     Statement s = conn.createStatement();
                     ResultSet rs = s.executeQuery("SELECT serial, image_url FROM cards "
                             + "WHERE image_data IS NOT NULL AND image_url IS NOT NULL")) {
                    while (rs.next()) map.put(rs.getString(1), rs.getString(2));
                }
                return map;
            }

            @Override
            protected void done() {
                try {
                    storedUrlBySerial = get();
                } catch (InterruptedException | ExecutionException e) {
                    storedUrlBySerial = new HashMap<>();
                }
                repaint();
            }
        }.execute();
    }

    private void request(Job job) {
        if (!pending.add(job.serial())) return;
        queue.offerFirst(job);
        while (queue.size() > MAX_QUEUED) {
            Job dropped = queue.pollLast();
            if (dropped != null) pending.remove(dropped.serial());
        }
        if (worker == null) {
            worker = new Thread(this::runLoads, "binder-images");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runLoads() {
        while (!Thread.currentThread().isInterrupted()) {
            Job job;
            try {
                job = queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            BufferedImage thumb = null;
            try {
                Image img = ImageCache.loadStored(job.url());
                if (img != null) thumb = scale(img, THUMB_W, THUMB_H);
            } catch (IOException | RuntimeException ignored) {
                // An unreadable image falls back to the named placeholder below.
            }
            BufferedImage loaded = thumb;
            SwingUtilities.invokeLater(() -> {
                pending.remove(job.serial());
                if (loaded != null) thumbs.put(job.serial(), loaded);
                else if (storedUrlBySerial != null) storedUrlBySerial.remove(job.serial());
                repaint();
            });
        }
    }

    private BufferedImage cardback() {
        if (cardback == null) {
            try (InputStream is = CardBinderView.class.getResourceAsStream("/cardback/default.jpg")) {
                BufferedImage img = is == null ? null : ImageIO.read(is);
                if (img != null) cardback = scale(img, THUMB_W, THUMB_H);
            } catch (IOException ignored) {
                // Painted as a plain panel below.
            }
            if (cardback == null) {
                cardback = new BufferedImage(THUMB_W, THUMB_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = cardback.createGraphics();
                g.setColor(new Color(0x2B, 0x2B, 0x3A));
                g.fillRoundRect(0, 0, THUMB_W, THUMB_H, 12, 12);
                g.dispose();
            }
        }
        return cardback;
    }

    /** Downscales by halving, then one bilinear step, which keeps a 3x reduction from aliasing. */
    private static BufferedImage scale(Image src, int w, int h) {
        int cw = src.getWidth(null);
        int ch = src.getHeight(null);
        if (cw <= 0 || ch <= 0) return null;
        Image cur = src;
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = drawScaled(cur, cw, ch);
        }
        return drawScaled(cur, w, h);
    }

    private static BufferedImage drawScaled(Image src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // -------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------

    private final class TileRenderer extends JComponent implements ListCellRenderer<Integer> {
        private String serial;
        private String name;
        private String element;
        private boolean selected;

        @Override
        public Component getListCellRendererComponent(JList<? extends Integer> list, Integer modelRow,
                int index, boolean isSelected, boolean cellHasFocus) {
            selected = isSelected;
            if (modelRow == null || modelRow < 0) {
                serial = name = element = null;
            } else {
                serial  = (String) table.getModel().getValueAt(modelRow, serialCol);
                name    = (String) table.getModel().getValueAt(modelRow, nameCol);
                element = (String) table.getModel().getValueAt(modelRow, elementCol);
            }
            setToolTipText(serial == null ? null : name + " (" + serial + ")");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (serial == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (selected) {
                    g2.setColor(CardBinderView.this.getSelectionBackground());
                    g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
                }
                BufferedImage img = thumbFor(serial);
                if (img != null) {
                    g2.drawImage(img, GAP, GAP, null);
                } else {
                    g2.drawImage(cardback(), GAP, GAP, null);
                    if (hasNoStoredImage(serial))
                        paintName(g2, name == null ? serial : name, name == null ? null : serial);
                }
            } finally {
                g2.dispose();
            }
        }

        /**
         * The name, wrapped and centred on the card, and under it the serial in a smaller size —
         * both filled in the card's element colours and outlined. The two shrink together until
         * the block fits the card. {@code serialText} may be null (the name line is the serial).
         */
        private void paintName(Graphics2D g2, String text, String serialText) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int maxW = THUMB_W - 2 * NAME_PAD;
            int maxH = THUMB_H - 2 * NAME_PAD;
            boolean pixelFont = FontLoader.canDisplayAll(text);

            Font font = null, serialFont = null;
            List<String> lines = null, serialLines = List.of();
            for (float size = NAME_MAX_SIZE; size >= NAME_MIN_SIZE; size -= 1f) {
                font       = nameFont(pixelFont, size);
                serialFont = nameFont(pixelFont, Math.max(SERIAL_MIN_SIZE, size * SERIAL_SCALE));
                FontMetrics fm = g2.getFontMetrics(font);
                FontMetrics sm = g2.getFontMetrics(serialFont);
                lines       = wrap(text, fm, maxW);
                serialLines = serialText == null ? List.of() : serialLines(serialText, sm, maxW);
                int height = lines.size() * fm.getHeight()
                        + (serialLines.isEmpty() ? 0 : SERIAL_GAP + serialLines.size() * sm.getHeight());
                boolean fits = height <= maxH;
                for (String line : lines) fits &= fm.stringWidth(line) <= maxW;
                for (String line : serialLines) fits &= sm.stringWidth(line) <= maxW;
                if (fits) break;
            }

            FontMetrics fm = g2.getFontMetrics(font);
            FontMetrics sm = g2.getFontMetrics(serialFont);
            int height = lines.size() * fm.getHeight()
                    + (serialLines.isEmpty() ? 0 : SERIAL_GAP + serialLines.size() * sm.getHeight());
            float top = GAP + (THUMB_H - height) / 2f;
            Path2D namePath = outlineOf(g2, lines, font, fm, top);
            float serialTop = top + lines.size() * fm.getHeight() + SERIAL_GAP;
            Path2D serialPath = outlineOf(g2, serialLines, serialFont, sm, serialTop);

            List<String> elements = splitElements(element);
            Color outline = outlineFor(elements);
            g2.setColor(outline);
            g2.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(namePath);
            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(serialPath);
            // Each is filled across its own width, so a multi-element name's bands sit over the
            // name and the serial's over the serial, rather than one set stretched across both.
            g2.setPaint(fillFor(elements, namePath.getBounds2D()));
            g2.fill(namePath);
            if (!serialLines.isEmpty()) {
                g2.setPaint(fillFor(elements, serialPath.getBounds2D()));
                g2.fill(serialPath);
            }
        }

        /** The outlines of {@code lines}, each centred across the card, starting at {@code top}. */
        private Path2D outlineOf(Graphics2D g2, List<String> lines, Font font, FontMetrics fm, float top) {
            FontRenderContext frc = g2.getFontRenderContext();
            Path2D path = new Path2D.Float();
            float y = top + fm.getAscent();
            for (String line : lines) {
                if (!line.isEmpty()) {
                    TextLayout tl = new TextLayout(line, font, frc);
                    float x = GAP + (THUMB_W - tl.getAdvance()) / 2f;
                    path.append(tl.getOutline(AffineTransform.getTranslateInstance(x, y)), false);
                }
                y += fm.getHeight();
            }
            return path;
        }
    }

    private static Font nameFont(boolean pixelFont, float size) {
        return pixelFont ? FontLoader.uiFontUnscaled(size) : new Font(Font.DIALOG, Font.BOLD, Math.round(size));
    }

    /**
     * The serial on one line, or split at its slashes when a reprint's pair ("Re-179L/16-129L")
     * is too wide for the card.
     */
    private static List<String> serialLines(String serial, FontMetrics fm, int maxW) {
        if (fm.stringWidth(serial) <= maxW || !serial.contains("/")) return List.of(serial);
        List<String> parts = new ArrayList<>();
        for (String p : serial.split("/")) if (!p.isBlank()) parts.add(p.strip());
        return parts;
    }

    /** Greedy word wrap; a single word wider than {@code maxW} gets a line of its own. */
    private static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && fm.stringWidth(candidate) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static List<String> splitElements(String element) {
        List<String> out = new ArrayList<>();
        if (element != null)
            for (String e : element.split("/"))
                if (!e.isBlank()) out.add(e.strip());
        return out;
    }

    /** Light prints white and Dark black; the rest take their board colour. */
    private static Color elementFill(String element) {
        if ("light".equalsIgnoreCase(element)) return Color.WHITE;
        if ("dark".equalsIgnoreCase(element))  return Color.BLACK;
        ElementColor ec = ElementColor.fromName(element);
        return ec == null ? Color.WHITE : ec.color;
    }

    /** Black text needs a white edge to read on the cardback; everything else gets a black one. */
    private static Color outlineFor(List<String> elements) {
        boolean allDark = !elements.isEmpty();
        for (String e : elements) allDark &= "dark".equalsIgnoreCase(e);
        return allDark ? Color.WHITE : Color.BLACK;
    }

    /** One colour, or side-by-side bands across the name for a multi-element card. */
    private static Paint fillFor(List<String> elements, Rectangle2D bounds) {
        if (elements.size() <= 1 || bounds.getWidth() <= 0)
            return elementFill(elements.isEmpty() ? null : elements.get(0));
        int n = elements.size();
        float[] fractions = new float[2 * n];
        Color[] colors = new Color[2 * n];
        for (int i = 0; i < n; i++) {
            // Two stops per band, a hair apart, so each band ends in a hard edge.
            fractions[2 * i]     = i == 0 ? 0f : (float) i / n + 0.0005f;
            fractions[2 * i + 1] = i == n - 1 ? 1f : (float) (i + 1) / n;
            colors[2 * i] = colors[2 * i + 1] = elementFill(elements.get(i));
        }
        return new LinearGradientPaint(
                (float) bounds.getMinX(), 0f, (float) bounds.getMaxX(), 0f, fractions, colors);
    }
}
