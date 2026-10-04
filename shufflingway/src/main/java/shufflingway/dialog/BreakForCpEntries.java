package shufflingway.dialog;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import shufflingway.CardData;
import shufflingway.FontLoader;
import shufflingway.ImageCache;
import shufflingway.graphics.CardAnimation;

import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * The "Break" copies a CP payment window shows for Backups that may be put into the Break Zone to
 * produce CP — Sherlotta 8-053H and her reprint, "you may put Sherlotta into the Break Zone to
 * produce 1 CP of any Element in order to pay a CP cost."
 *
 * <p>A second copy of the Backup rather than a mode on the first, because the two payments are
 * independent: her own reminder text says the break "can be in addition to dulling Sherlotta for
 * CP", so the player has to be able to take each on its own. The break copy is offered whatever
 * state the Backup is in — breaking is not dulling, and an already-dulled Sherlotta can still be
 * broken.
 *
 * <p>Shared by every payment window rather than repeated in each: "a CP cost" is any of them, and
 * five copies of the selection map, the accounting and the picker would be five places for the
 * rule to drift. Each window differs only in how it banks CP, which {@link #contribute} takes as
 * an argument.
 */
final class BreakForCpEntries {

    /** Border colour of an unselected break copy — distinct from the yellow of a dull selection. */
    private static final Color BREAK_ORANGE = new Color(200, 120, 40);

    private final Map<Integer, Integer> slots;
    private final CardData[]  backupCards;
    private final String[]    backupUrls;
    private final Consumer<String> onZoom;
    private final Runnable         onZoomHide;

    /** Slot to the Element the player pointed its CP at; a slot absent here is not being broken. */
    private final Map<Integer, String> chosen = new LinkedHashMap<>();
    private final List<JLabel>  labels     = new ArrayList<>();
    private final List<Integer> labelSlots = new ArrayList<>();

    /**
     * @param slots       backup slots that may be broken for CP, mapped to how much each produces
     *                    (see {@code MainWindow.breakForCpBackupSlots}); empty when none apply
     * @param backupCards the row the slots index, as the window sees it — a slot the window has
     *                    blanked (backup CP suppressed, or a card being spent elsewhere) is skipped
     */
    BreakForCpEntries(Map<Integer, Integer> slots, CardData[] backupCards, String[] backupUrls,
            Consumer<String> onZoom, Runnable onZoomHide) {
        this.slots       = slots == null ? Map.of() : slots;
        this.backupCards = backupCards;
        this.backupUrls  = backupUrls;
        this.onZoom      = onZoom;
        this.onZoomHide  = onZoomHide;
    }

    /** Whether there is anything to offer; windows use it to decide whether to open a Backups row. */
    boolean isEmpty() {
        return slots.isEmpty();
    }

    /** What the player selected, in the shape {@code MainWindow.breakBackupsForCp} takes. */
    Map<Integer, String> selection() {
        return new LinkedHashMap<>(chosen);
    }

    /** The line to append to a Backups row header when there is anything to offer. */
    static String headerSuffix() {
        return ", or break the orange copy for CP of any Element";
    }

    /**
     * Adds one break copy per eligible slot to {@code row}.
     *
     * @param lockedElement the only Element the cost will accept, or {@code null} when the player
     *                      picks; a locked cost skips the picker, since there is nothing to choose
     * @param updateAll     the window's recalculation, run after every selection change
     */
    void addTo(JPanel row, String lockedElement, Runnable updateAll) {
        for (Map.Entry<Integer, Integer> e : slots.entrySet()) {
            final int slot = e.getKey();
            if (slot < 0 || slot >= backupCards.length) continue;
            final CardData bkp = backupCards[slot];
            if (bkp == null) continue;
            JLabel lbl = makeCardLabel();
            lbl.setBorder(BorderFactory.createLineBorder(BREAK_ORANGE, 2));
            lbl.setToolTipText("Break " + bkp.name() + " for " + e.getValue() + " CP of any Element");
            final String url = backupUrls[slot];
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent ev) {
                    if (chosen.remove(slot) != null) { updateAll.run(); return; }
                    if (lockedElement != null) {
                        chosen.put(slot, lockedElement);
                        updateAll.run();
                        return;
                    }
                    StandardPaymentDialog.showElementPicker(lbl, ev, "Break " + bkp.name(),
                            StandardPaymentDialog.ALL_ELEMENTS, picked -> {
                                chosen.put(slot, picked);
                                updateAll.run();
                            });
                }
                @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(url); }
                @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
            });
            loadImage(lbl, url);
            JPanel cell = new JPanel();
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setOpaque(false);
            lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            JLabel caption = new JLabel("Break", SwingConstants.CENTER);
            caption.setFont(FontLoader.loadPixelFont(9));
            caption.setForeground(new Color(255, 170, 80));
            caption.setAlignmentX(Component.CENTER_ALIGNMENT);
            cell.add(lbl);
            cell.add(caption);
            labels.add(lbl);
            labelSlots.add(slot);
            row.add(cell);
        }
    }

    /**
     * Banks the CP the current selection produces into {@code cpByElem}.
     *
     * <p>The Element is always one the player picked, so it lands where they pointed it — unless
     * the window banks every payment under one Element ({@code forcedElement}, the Light/Dark
     * casts), or the Element is not one this cost tracks, in which case it is generic CP.
     *
     * @param forcedElement the single Element this window banks under, or {@code null} to use the
     *                      picked one
     * @return the CP that went to the generic pile, for the caller to add to its own running total
     */
    int contribute(Map<String, Integer> cpByElem, String forcedElement) {
        int generic = 0;
        for (Map.Entry<Integer, String> br : chosen.entrySet()) {
            int amount = slots.getOrDefault(br.getKey(), 1);
            if (forcedElement != null)                    cpByElem.merge(forcedElement, amount, Integer::sum);
            else if (cpByElem.containsKey(br.getValue())) cpByElem.merge(br.getValue(), amount, Integer::sum);
            else                                          generic += amount;
        }
        return generic;
    }

    /** Each selected break's slot, picked Element and CP produced, in selection order. */
    void forEachChoice(BreakChoice action) {
        for (Map.Entry<Integer, String> br : chosen.entrySet())
            action.accept(br.getKey(), br.getValue(), slots.getOrDefault(br.getKey(), 1));
    }

    @FunctionalInterface
    interface BreakChoice {
        void accept(int slot, String element, int amount);
    }

    /** Repaints the break copies to match the current selection; called from the window's updateAll. */
    void refresh() {
        for (int i = 0; i < labels.size(); i++) {
            JLabel lbl = labels.get(i);
            lbl.setBorder(chosen.containsKey(labelSlots.get(i))
                    ? CardAnimation.createCardGlowBorder(Color.ORANGE)
                    : BorderFactory.createLineBorder(BREAK_ORANGE, 2));
        }
    }

    private static JLabel makeCardLabel() {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(FontLoader.loadPixelFont(10));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return lbl;
    }

    private static void loadImage(JLabel lbl, String url) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                if (img == null) return null;
                BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buf.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                g2.dispose();
                return new ImageIcon(buf);
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
    }
}
