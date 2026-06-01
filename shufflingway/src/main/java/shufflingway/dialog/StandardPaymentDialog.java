package shufflingway.dialog;

import shufflingway.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;

/** CP payment dialog for a standard (non-LB, non-alt-cost) card play. */
public class StandardPaymentDialog {

    private final JFrame        owner;
    private final CardData      card;
    private final int           handIdx;
    private final int           cost;
    private final List<CardData> hand;
    private final CardData[]    backupCards;
    private final CardState[]   backupStates;
    private final String[]      backupUrls;
    private final List<CardData>    controlledForwards;
    private final Consumer<String>  onZoom;
    private final Runnable          onZoomHide;

    /** Callback invoked on payment confirmation. */
    @FunctionalInterface
    public interface ConfirmCallback {
        void accept(List<Integer> discards, List<Integer> backups, Map<Integer, String> elementOverrides);
    }
    /** Called on Confirm with (discardIndices, backupSlots, elementOverrides). */
    private final ConfirmCallback onConfirm;

    public StandardPaymentDialog(JFrame owner, CardData card, int handIdx, int cost,
            List<CardData> hand, CardData[] backupCards, CardState[] backupStates,
            String[] backupUrls, Consumer<String> onZoom, Runnable onZoomHide,
            List<CardData> controlledForwards, ConfirmCallback onConfirm) {
        this.owner              = owner;
        this.card               = card;
        this.handIdx            = handIdx;
        this.cost               = cost;
        this.hand               = hand;
        this.backupCards        = backupCards;
        this.backupStates       = backupStates;
        this.backupUrls         = backupUrls;
        this.onZoom             = onZoom;
        this.onZoomHide         = onZoomHide;
        this.controlledForwards = controlledForwards;
        this.onConfirm          = onConfirm;
    }

    public void show() {
        JDialog dlg = new JDialog(owner, "Play " + card.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        String   elem  = card.element();
        String[] elems = card.elements();
        boolean  isLD  = card.isLightOrDark();
        boolean backupCpOnly = card.castBackupCpOnly();

        Map<String, Integer> bankCpByElem = new LinkedHashMap<>();
        if (isLD) bankCpByElem.put(elem, 0);
        else      for (String e : elems) bankCpByElem.put(e, 0);

        List<Integer> selectedBackups      = new ArrayList<>();
        List<Integer> selectedDiscards     = new ArrayList<>();
        Map<Integer, String> backupElementOverrides = new LinkedHashMap<>();

        List<Integer> eligibleBackupSlots = new ArrayList<>();
        for (int i = 0; i < backupCards.length; i++) {
            if (backupCards[i] != null && backupStates[i] == CardState.ACTIVE)
                eligibleBackupSlots.add(i);
        }

        Map<String, Integer> costByElem = new LinkedHashMap<>();
        if (!isLD) for (String e : elems) costByElem.put(e, 1);

        JLabel   cpLabel    = new JLabel();
        cpLabel.setFont(FontLoader.loadPixelNESFont(11));
        cpLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

        List<JLabel>  backupLbls  = new ArrayList<>();
        List<Integer> backupSlots = new ArrayList<>();
        List<JLabel>  discardLbls = new ArrayList<>();
        List<Integer> discardIdxs = new ArrayList<>();
        boolean[] canAddDiscard   = {false};

        Runnable updateAll = () -> {
            Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
            int extraCp = 0;
            List<Integer> sortedBackups = new ArrayList<>(selectedBackups);
            if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
                    (int) java.util.Arrays.stream(elems)
                            .filter(e -> backupCards[s].containsElement(e)).count()));
            for (int slot : sortedBackups) {
                if (isLD) {
                    cpByElem.merge(elem, 1, Integer::sum);
                } else if (backupElementOverrides.containsKey(slot)) {
                    String overElem = backupElementOverrides.get(slot);
                    if (cpByElem.containsKey(overElem))
                        cpByElem.merge(overElem, 1, Integer::sum);
                    else
                        extraCp++;
                } else if (matchesAnyElement(backupCards[slot], elems)) {
                    cpByElem.merge(contributingElement(backupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
                } else {
                    extraCp++;
                }
            }
            List<Integer> sortedDiscards = new ArrayList<>(selectedDiscards);
            if (!isLD) sortedDiscards.sort(Comparator.comparingInt(i ->
                    (int) java.util.Arrays.stream(elems)
                            .filter(e -> hand.get(i).containsElement(e)).count()));
            for (int idx : sortedDiscards) {
                if (isLD)
                    cpByElem.merge(elem, 2, Integer::sum);
                else if (matchesAnyElement(hand.get(idx), elems))
                    cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
                else
                    extraCp += 2;
            }
            int total          = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
            int unsatisfied    = isLD ? 0 : (int) cpByElem.values().stream().filter(v -> v < 1).count();
            boolean canAddBkp  = total < cost;
            canAddDiscard[0]   = !backupCpOnly && (isLD
                    ? total < cost
                    : (total < cost) || (extraCp == 0 && unsatisfied > 0 && total + 2 <= cost + 2 * unsatisfied));
            boolean allElems   = isLD || cpByElem.values().stream().allMatch(v -> v >= 1);
            confirmBtn.setEnabled(total >= cost && allElems);
            if (elems.length == 1) {
                cpLabel.setText("CP: " + total + " / " + cost + "  (" + elem + ")");
            } else {
                StringBuilder sb = new StringBuilder("CP: " + total + " / " + cost + "  (");
                boolean first = true;
                for (Map.Entry<String, Integer> e : cpByElem.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(": ").append(e.getValue());
                    first = false;
                }
                cpLabel.setText(sb.append(")").toString());
            }
            for (int i = 0; i < backupLbls.size(); i++) {
                JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
                lbl.setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(canAddBkp ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setBackground(sel || canAddBkp ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setCursor(sel || canAddBkp ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
            for (int i = 0; i < discardLbls.size(); i++) {
                JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
                lbl.setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setCursor(sel || canAddDiscard[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };
        updateAll.run();

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

        if (!eligibleBackupSlots.isEmpty()) {
            JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
            hdr.setFont(FontLoader.loadPixelNESFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int slot : eligibleBackupSlots) {
                JLabel lbl = makeCardLabel();
                final String url = backupUrls[slot];
                lbl.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        if (selectedBackups.remove(Integer.valueOf(slot))) {
                            backupElementOverrides.remove(slot);
                            updateAll.run();
                            return;
                        }
                        int tot = bankCpByElem.values().stream().mapToInt(Integer::intValue).sum()
                                + selectedBackups.size() + selectedDiscards.size() * 2;
                        if (tot >= cost) return;
                        CardData bkp = backupCards[slot];
                        String anyElemCat = bkp.backupCpAnyElementCategory();
                        boolean isAnyElem = bkp.backupCpAnyElement()
                                || (!anyElemCat.isEmpty()
                                    && (anyElemCat.equalsIgnoreCase(card.category1())
                                        || anyElemCat.equalsIgnoreCase(card.category2())))
                                || isGrantedAnyElement(bkp);
                        boolean isAnyElemOfFwds = bkp.backupCpAnyElementOfForwards()
                                && !controlledForwards.isEmpty();
                        if (isAnyElem || isAnyElemOfFwds) {
                            String[] available;
                            if (isAnyElemOfFwds && !isAnyElem) {
                                java.util.LinkedHashSet<String> fwdElems = new java.util.LinkedHashSet<>();
                                for (CardData fwd : controlledForwards)
                                    for (String fe : fwd.elements()) fwdElems.add(fe);
                                available = fwdElems.toArray(String[]::new);
                            } else {
                                available = ALL_ELEMENTS;
                            }
                            showElementPicker(lbl, e, bkp.name(), available, picked -> {
                                backupElementOverrides.put(slot, picked);
                                selectedBackups.add(slot);
                                updateAll.run();
                            });
                        } else {
                            selectedBackups.add(slot);
                            updateAll.run();
                        }
                    }
                    @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(url); }
                    @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                });
                loadImage(lbl, url, false);
                backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
            }
            centerPanel.add(hdr); centerPanel.add(bp);
        }

        if (!backupCpOnly) {
            JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
            discHdr.setFont(FontLoader.loadPixelNESFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int i = 0; i < hand.size(); i++) {
                if (i == handIdx) continue;
                final int hi = i; CardData hc = hand.get(i);
                boolean payable = !hc.isLightOrDark();
                JLabel lbl = makeCardLabel();
                lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                final String imgUrl = hc.imageUrl();
                if (payable) {
                    lbl.addMouseListener(new MouseAdapter() {
                        @Override public void mousePressed(MouseEvent e) {
                            if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0])
                                selectedDiscards.add(hi);
                            updateAll.run();
                        }
                        @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                        @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                    });
                    discardLbls.add(lbl); discardIdxs.add(hi);
                } else {
                    lbl.addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                        @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                    });
                }
                loadImage(lbl, imgUrl, !payable);
                dp.add(lbl);
            }
            centerPanel.add(discHdr); centerPanel.add(dp);
        }

        JLabel hint = new JLabel(
                backupCpOnly
                ? "<html><center>Backups only: dull for 1 CP each.<br>Hand discards are not allowed for this card.</center></html>"
                : "<html><center>Backups: dull for 1 CP. Hand cards (" + elem + ", non-Light/Dark): discard for 2 CP.</center></html>",
                SwingConstants.CENTER);
        hint.setFont(FontLoader.loadPixelNESFont(9));

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
        cancelBtn.addActionListener(e -> dlg.dispose());
        confirmBtn.addActionListener(e -> {
            dlg.dispose();
            onConfirm.accept(new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups),
                    new LinkedHashMap<>(backupElementOverrides));
        });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(confirmBtn); south.add(cancelBtn);

        JLabel title = new JLabel("Pay for: " + card.name() + "  (Cost " + cost + " " + elem + " CP)",
                SwingConstants.CENTER);
        title.setFont(FontLoader.loadPixelNESFont(11));

        JPanel topPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        topPanel.add(title,   java.awt.BorderLayout.NORTH);
        topPanel.add(cpLabel, java.awt.BorderLayout.CENTER);
        topPanel.add(hint,    java.awt.BorderLayout.SOUTH);

        JPanel mainPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        mainPanel.add(new JScrollPane(centerPanel), java.awt.BorderLayout.CENTER);
        mainPanel.add(south,                        java.awt.BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new java.awt.BorderLayout());
        dlg.getContentPane().add(topPanel,  java.awt.BorderLayout.NORTH);
        dlg.getContentPane().add(mainPanel, java.awt.BorderLayout.CENTER);
        dlg.pack(); dlg.setLocationRelativeTo(owner); dlg.setVisible(true);
    }

    /** Returns true if any on-field card (backup or forward) grants {@code backup} any-element CP. */
    private boolean isGrantedAnyElement(CardData backup) {
        for (CardData b : backupCards) {
            if (b != null) {
                BackupCpGrant grant = b.backupCpGrant();
                if (grant != null && grant.appliesTo(backup)) return true;
            }
        }
        for (CardData fwd : controlledForwards) {
            BackupCpGrant grant = fwd.backupCpGrant();
            if (grant != null && grant.appliesTo(backup)) return true;
        }
        return false;
    }

    static final String[] ALL_ELEMENTS =
            {"Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark"};

    static void showElementPicker(java.awt.Component anchor, MouseEvent trigger,
            String backupName, String[] elements, Consumer<String> onPick) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem header = new JMenuItem(backupName + " — choose element:");
        header.setFont(FontLoader.loadPixelNESFont(9));
        header.setEnabled(false);
        popup.add(header);
        popup.addSeparator();
        for (String elem : elements) {
            JMenuItem item = new JMenuItem(elem);
            item.setFont(FontLoader.loadPixelNESFont(10));
            ElementColor ec = ElementColor.fromName(elem);
            if (ec != null) {
                item.setBackground(ec.color);
                item.setForeground(Color.WHITE);
                item.setOpaque(true);
            }
            item.addActionListener(ae -> onPick.accept(elem));
            popup.add(item);
        }
        popup.show(anchor, trigger.getX(), trigger.getY());
    }

    private static JLabel makeCardLabel() {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(FontLoader.loadPixelNESFont(10));
        lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return lbl;
    }

    private static void loadImage(JLabel lbl, String url, boolean greyscale) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                if (img == null) return null;
                if (!greyscale) return new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buf.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                g2.dispose();
                return new ImageIcon(new ColorConvertOp(
                        ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(buf, null));
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
    }
}
