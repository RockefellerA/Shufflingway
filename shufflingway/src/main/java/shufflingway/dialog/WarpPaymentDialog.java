package shufflingway.dialog;

import shufflingway.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;

/** CP payment dialog for a Warp alternate cost. */
public class WarpPaymentDialog {

    /** Callback invoked on payment confirmation. */
    @FunctionalInterface
    public interface ConfirmCallback {
        void accept(List<Integer> discards, List<Integer> backups, Map<Integer, String> elementOverrides);
    }

    private final JFrame         owner;
    private final CardData       card;
    private final int            handIdx;
    private final List<CardData> hand;
    private final CardData[]     backupCards;
    private final CardState[]    backupStates;
    private final String[]       backupUrls;
    private final List<CardData> controlledForwards;
    private final Consumer<String> onZoom;
    private final Runnable         onZoomHide;
    private final ConfirmCallback  onConfirm;

    public WarpPaymentDialog(JFrame owner, CardData card, int handIdx,
            List<CardData> hand, CardData[] backupCards, CardState[] backupStates,
            String[] backupUrls, List<CardData> controlledForwards,
            Consumer<String> onZoom, Runnable onZoomHide,
            ConfirmCallback onConfirm) {
        this.owner              = owner;
        this.card               = card;
        this.handIdx            = handIdx;
        this.hand               = hand;
        this.backupCards        = backupCards;
        this.backupStates       = backupStates;
        this.backupUrls         = backupUrls;
        this.controlledForwards = controlledForwards;
        this.onZoom             = onZoom;
        this.onZoomHide         = onZoomHide;
        this.onConfirm          = onConfirm;
    }

    public void show() {
        List<String> rawCost   = card.warpCost();
        long genericNeeded     = rawCost.stream().filter(String::isEmpty).count();
        LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
        for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
        String[] elems     = costByElem.keySet().toArray(String[]::new);
        int      totalCost = rawCost.size();

        JDialog dlg = new JDialog(owner, "Warp: " + card.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
        for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

        List<Integer> selectedBackups  = new ArrayList<>();
        List<Integer> selectedDiscards = new ArrayList<>();
        Map<Integer, String> backupElementOverrides = new LinkedHashMap<>();

        List<Integer> eligibleBackupSlots = new ArrayList<>();
        for (int i = 0; i < backupCards.length; i++) {
            if (backupCards[i] != null && backupStates[i] == CardState.ACTIVE)
                eligibleBackupSlots.add(i);
        }

        JLabel  cpLabel    = new JLabel();
        cpLabel.setFont(FontLoader.loadPixelNESFont(11));
        cpLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JButton confirmBtn = new JButton("Confirm Warp");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

        List<JLabel>  backupLbls  = new ArrayList<>();
        List<Integer> backupSlots = new ArrayList<>();
        List<JLabel>  discardLbls = new ArrayList<>();
        List<Integer> discardIdxs = new ArrayList<>();
        boolean[] canAddDiscard   = {false};

        Runnable updateAll = () -> {
            Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
            int extraCp = 0;
            for (int slot : selectedBackups) {
                if (backupElementOverrides.containsKey(slot)) {
                    String overElem = backupElementOverrides.get(slot);
                    if (cpByElem.containsKey(overElem)) cpByElem.merge(overElem, 1, Integer::sum);
                    else extraCp++;
                } else if (matchesAnyElement(backupCards[slot], elems)) {
                    cpByElem.merge(contributingElement(backupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
                } else {
                    extraCp++;
                }
            }
            for (int idx : selectedDiscards) {
                if (matchesAnyElement(hand.get(idx), elems))
                    cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
                else extraCp += 2;
            }
            int total       = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
            int unsatisfied = (int) java.util.stream.IntStream.range(0, elems.length)
                    .filter(ei -> cpByElem.getOrDefault(elems[ei], 0) < costByElem.get(elems[ei])).count();
            boolean canAddBkp = total < totalCost;
            canAddDiscard[0]  = (total < totalCost) || (unsatisfied > 0 && total + 2 <= totalCost + 2 * unsatisfied);
            boolean satisfied = cpByElem.entrySet().stream()
                    .allMatch(e -> e.getValue() >= costByElem.getOrDefault(e.getKey(), 0));
            confirmBtn.setEnabled(total >= totalCost && satisfied);

            StringBuilder sb = new StringBuilder("Warp CP: " + total + " / " + totalCost + "  (");
            boolean first = true;
            for (String e : elems) {
                if (!first) sb.append(", ");
                sb.append(e).append(": ").append(cpByElem.getOrDefault(e, 0)).append("/").append(costByElem.get(e));
                first = false;
            }
            if (genericNeeded > 0) {
                if (!first) sb.append(", ");
                sb.append("any: ").append(Math.min(extraCp, (int) genericNeeded)).append("/").append((int) genericNeeded);
                first = false;
            }
            if (first) sb.append("free");
            cpLabel.setText(sb.append(")").toString());

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
                        if (tot >= totalCost) return;
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
                                available = StandardPaymentDialog.ALL_ELEMENTS;
                            }
                            StandardPaymentDialog.showElementPicker(lbl, e, bkp.name(), available, picked -> {
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
                loadCardImage(lbl, url);
                backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
            }
            centerPanel.add(hdr); centerPanel.add(bp);
        }

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
                        if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
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
            loadCardImage(lbl, imgUrl);
            dp.add(lbl);
        }
        centerPanel.add(discHdr); centerPanel.add(dp);

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

        StringBuilder costDesc = new StringBuilder();
        boolean f = true;
        for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
            if (!f) costDesc.append(" + ");
            costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP"); f = false;
        }
        if (genericNeeded > 0) { if (!f) costDesc.append(" + "); costDesc.append((int) genericNeeded).append(" any CP"); }

        JLabel titleLabel = new JLabel(
                "Warp cost for: " + card.name() + "  (" + (costDesc.length() > 0 ? costDesc : "free") + ")",
                SwingConstants.CENTER);
        titleLabel.setFont(FontLoader.loadPixelNESFont(11));

        JPanel topPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        topPanel.add(titleLabel, java.awt.BorderLayout.NORTH);
        topPanel.add(cpLabel,    java.awt.BorderLayout.CENTER);

        JPanel mainPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        mainPanel.add(new JScrollPane(centerPanel), java.awt.BorderLayout.CENTER);
        mainPanel.add(south,                        java.awt.BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new java.awt.BorderLayout());
        dlg.getContentPane().add(topPanel,  java.awt.BorderLayout.NORTH);
        dlg.getContentPane().add(mainPanel, java.awt.BorderLayout.CENTER);
        dlg.pack(); dlg.setLocationRelativeTo(owner); dlg.setVisible(true);
    }

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

    private static void loadCardImage(JLabel lbl, String url) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
    }
}
