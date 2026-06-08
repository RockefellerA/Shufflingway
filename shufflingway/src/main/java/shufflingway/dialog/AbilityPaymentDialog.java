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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;

/** CP payment dialog for an action ability activation. */
public class AbilityPaymentDialog {

    @FunctionalInterface
    public interface Callback {
        void onConfirm(List<Integer> discards, List<Integer> backups, int xValue);
    }

    private final JFrame         owner;
    private final ActionAbility  ability;
    private final CardData       source;
    private final List<CardData> hand;
    private final CardData[]     backupCards;
    private final CardState[]    backupStates;
    private final String[]       backupUrls;
    private final Consumer<String> onZoom;
    private final Runnable         onZoomHide;
    private final Callback         onConfirm;

    public AbilityPaymentDialog(JFrame owner, ActionAbility ability, CardData source,
            List<CardData> hand, CardData[] backupCards, CardState[] backupStates,
            String[] backupUrls, Consumer<String> onZoom, Runnable onZoomHide,
            Callback onConfirm) {
        this.owner        = owner;
        this.ability      = ability;
        this.source       = source;
        this.hand         = hand;
        this.backupCards  = backupCards;
        this.backupStates = backupStates;
        this.backupUrls   = backupUrls;
        this.onZoom       = onZoom;
        this.onZoomHide   = onZoomHide;
        this.onConfirm    = onConfirm;
    }

    public void show() {
        List<String> rawCost   = ability.cpCost();
        long genericNeeded     = rawCost.stream().filter(String::isEmpty).count();
        LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
        for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
        String[] elems   = costByElem.keySet().toArray(String[]::new);
        int      totalCost = rawCost.size();

        JDialog dlg = new JDialog(owner, "Activate: " + source.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
        for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

        List<Integer> selectedBackups  = new ArrayList<>();
        List<Integer> selectedDiscards = new ArrayList<>();

        List<Integer> eligibleBackupSlots = new ArrayList<>();
        for (int i = 0; i < backupCards.length; i++) {
            if (backupCards[i] != null && backupCards[i] != source && backupStates[i] == CardState.ACTIVE)
                eligibleBackupSlots.add(i);
        }

        JLabel  cpLabel    = new JLabel();
        cpLabel.setFont(FontLoader.loadPixelNESFont(11));
        cpLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

        List<JLabel>  backupLbls  = new ArrayList<>();
        List<Integer> backupSlots = new ArrayList<>();
        List<JLabel>  discardLbls = new ArrayList<>();
        List<Integer> discardIdxs = new ArrayList<>();
        boolean[] canAddDiscard = {false};
        boolean[] canAddBackup  = {false};
        int[]     xValueHolder  = {0};

        Runnable updateAll = () -> {
            Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
            int extraCp = 0;
            for (int slot : selectedBackups) {
                if (matchesAnyElement(backupCards[slot], elems))
                    cpByElem.merge(contributingElement(backupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
                else extraCp++;
            }
            for (int idx : selectedDiscards) {
                if (matchesAnyElement(hand.get(idx), elems))
                    cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
                else extraCp += 2;
            }
            int total       = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
            int unsatisfied = (int) java.util.stream.IntStream.range(0, elems.length)
                    .filter(ei -> cpByElem.getOrDefault(elems[ei], 0) < costByElem.get(elems[ei])).count();
            boolean satisfied = cpByElem.entrySet().stream()
                    .allMatch(en -> en.getValue() >= costByElem.getOrDefault(en.getKey(), 0));

            if (ability.hasXCost()) {
                xValueHolder[0]  = Math.max(0, total - totalCost);
                canAddBackup[0]  = true;
                canAddDiscard[0] = true;
                confirmBtn.setEnabled(satisfied);
            } else {
                int maxAllowed   = totalCost + elems.length + (totalCost % 2);
                canAddBackup[0]  = total < totalCost;
                canAddDiscard[0] = (total + 2 <= maxAllowed) && (total < totalCost || unsatisfied > 0);
                confirmBtn.setEnabled(total >= totalCost && satisfied);
            }

            StringBuilder sb = new StringBuilder("CP: " + total + " / " + totalCost + "  (");
            boolean first = true;
            for (String en : elems) {
                if (!first) sb.append(", ");
                sb.append(en).append(": ").append(cpByElem.getOrDefault(en, 0))
                  .append("/").append(costByElem.get(en));
                first = false;
            }
            if (genericNeeded > 0) {
                if (!first) sb.append(", ");
                sb.append("any: ").append(Math.min(extraCp, (int) genericNeeded))
                  .append("/").append((int) genericNeeded);
            }
            if (ability.hasXCost()) { if (!first) sb.append(", "); sb.append("X = ").append(xValueHolder[0]); first = false; }
            if (first) sb.append("free");
            cpLabel.setText(sb.append(")").toString());

            for (int i = 0; i < backupLbls.size(); i++) {
                JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
                lbl.setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(canAddBackup[0] ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setCursor(sel || canAddBackup[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
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

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        if (!eligibleBackupSlots.isEmpty()) {
            JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
            hdr.setFont(FontLoader.loadPixelNESFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int slot : eligibleBackupSlots) {
                JLabel lbl = makeCardLabel();
                final String url = backupUrls[slot];
                lbl.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent ev) {
                        if (!selectedBackups.remove(Integer.valueOf(slot)) && canAddBackup[0]) selectedBackups.add(slot);
                        updateAll.run();
                    }
                    @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(url); }
                    @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                });
                loadCardImage(lbl, url);
                backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
            }
            center.add(hdr); center.add(bp);
        }

        JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
        discHdr.setFont(FontLoader.loadPixelNESFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < hand.size(); i++) {
            final int hi = i; CardData hc = hand.get(i); boolean payable = !hc.isLightOrDark() && hc != source;
            JLabel lbl = makeCardLabel();
            lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
            lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
            lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            final String imgUrl = hc.imageUrl();
            if (payable) {
                lbl.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent ev) {
                        if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
                        updateAll.run();
                    }
                    @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                    @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                });
                discardLbls.add(lbl); discardIdxs.add(hi);
            } else {
                lbl.addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                    @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                });
            }
            loadCardImage(lbl, imgUrl);
            dp.add(lbl);
        }
        center.add(discHdr); center.add(dp);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
        cancelBtn.addActionListener(ev -> dlg.dispose());
        confirmBtn.addActionListener(ev -> {
            dlg.dispose();
            onConfirm.onConfirm(new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups), xValueHolder[0]);
        });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(confirmBtn); south.add(cancelBtn);

        // Build cost summary for title
        StringBuilder costDesc = new StringBuilder();
        boolean cf = true;
        if (ability.requiresDull())    { costDesc.append("Dull"); cf = false; }
        if (ability.isSpecial())       { if (!cf) costDesc.append(" + "); costDesc.append("S (discard ").append(source.name()).append(")"); cf = false; }
        if (ability.hasXCost())        { if (!cf) costDesc.append(" + "); costDesc.append("X CP"); cf = false; }
        if (ability.crystalCost() > 0) { if (!cf) costDesc.append(" + "); costDesc.append(ability.crystalCost()).append(" Crystal"); cf = false; }
        if (ability.selfMillCost() > 0) { if (!cf) costDesc.append(" + "); costDesc.append("mill ").append(ability.selfMillCost()); cf = false; }
        for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
            if (!cf) costDesc.append(" + ");
            costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP"); cf = false;
        }
        if (genericNeeded > 0) { if (!cf) costDesc.append(" + "); costDesc.append((int) genericNeeded).append(" any CP"); }
        for (BreakZoneCost bz : ability.breakZoneCosts()) {
            if (!cf) costDesc.append(" + ");
            costDesc.append("put ");
            if (bz.name().isEmpty()) costDesc.append(bz.count()).append(' ').append(bz.cardType());
            else costDesc.append(bz.name());
            costDesc.append(" into BZ"); cf = false;
        }
        for (RemoveFromGameCost rfg : ability.removeFromGameCosts()) {
            if (!cf) costDesc.append(" + ");
            costDesc.append("RFG ");
            if (rfg.cardName() != null) costDesc.append(rfg.cardName());
            else {
                costDesc.append(rfg.count() == -1 ? "all" : rfg.count());
                if (rfg.element()  != null) costDesc.append(' ').append(rfg.element());
                if (rfg.cardType() != null) costDesc.append(' ').append(rfg.cardType());
                else costDesc.append(" card");
            }
            costDesc.append(" (").append(rfg.zone().toLowerCase().replace('_', ' ')).append(')'); cf = false;
        }
        for (ReturnToHandCost rth : ability.returnToHandCosts()) {
            if (!cf) costDesc.append(" + ");
            costDesc.append("RTH ");
            if (rth.cardName() != null) costDesc.append(rth.cardName());
            else {
                costDesc.append(rth.count());
                if (rth.category() != null) costDesc.append(" Cat.").append(rth.category());
                if (rth.cardType() != null) costDesc.append(' ').append(rth.cardType());
            }
            cf = false;
        }
        for (CounterCost cc : ability.counterCosts()) {
            if (!cf) costDesc.append(" + ");
            costDesc.append("remove ").append(cc.count()).append(' ')
                    .append(cc.counterName()).append(" Counter(s)"); cf = false;
        }

        JLabel titleLabel = new JLabel(
                "<html><center>" + source.name() + " — " + (costDesc.length() > 0 ? costDesc : "free") + "</center></html>",
                SwingConstants.CENTER);
        titleLabel.setFont(FontLoader.loadPixelNESFont(11));

        JPanel topPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        topPanel.add(titleLabel, java.awt.BorderLayout.NORTH);
        topPanel.add(cpLabel,    java.awt.BorderLayout.CENTER);

        JPanel mainPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        mainPanel.add(new JScrollPane(center), java.awt.BorderLayout.CENTER);
        mainPanel.add(south,                   java.awt.BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new java.awt.BorderLayout());
        dlg.getContentPane().add(topPanel,  java.awt.BorderLayout.NORTH);
        dlg.getContentPane().add(mainPanel, java.awt.BorderLayout.CENTER);
        dlg.pack(); dlg.setLocationRelativeTo(owner); dlg.setVisible(true);
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
