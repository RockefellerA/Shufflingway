package shufflingway;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

import shufflingway.dialog.DebugCardPickerDialog;

class DebugUtility {

    private final MainWindow mw;

    DebugUtility(MainWindow mw) {
        this.mw = mw;
    }

    void spawnOnField() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.Selection sel = DebugCardPickerDialog.pick(mw.frame, "Spawn Card on Field");
        if (sel == null) return;
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        String who = isP1 ? "P1" : "P2";
        mw.gameState.getIdentity().put(card, isP1);
        if (card.isForward()) {
            if (isP1) mw.placeCardInForwardZone(card); else mw.placeP2CardInForwardZone(card);
        } else if (card.isMonster()) {
            if (isP1) mw.placeCardInMonsterZone(card); else mw.placeP2CardInMonsterZone(card);
        } else if (card.isBackup()) {
            boolean hasSlot = isP1 ? mw.hasAvailableBackupSlot() : mw.p2HasAvailableBackupSlot();
            if (!hasSlot) {
                JOptionPane.showMessageDialog(mw.frame, who + " has no free Backup slot.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (isP1) mw.placeCardInFirstBackupSlot(card); else mw.placeP2CardInFirstBackupSlot(card);
        } else {
            addCardToHand(card, isP1);
            mw.logEntry("[Debug] " + card.name() + " is a Summon — added to " + who + " hand instead of field.");
            return;
        }
        mw.logEntry("[Debug] Spawned " + card.name() + " (" + sel.serial() + ") onto " + who + " field.");
    }

    void addToHand() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.Selection sel = DebugCardPickerDialog.pick(mw.frame, "Add Card to Hand", this::clearHand, "Clear Hand");
        if (sel == null) return;
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        mw.gameState.getIdentity().put(card, isP1);
        addCardToHand(card, isP1);
        mw.logEntry("[Debug] Added " + card.name() + " (" + sel.serial() + ") to " + (isP1 ? "P1" : "P2") + " hand.");
    }

    private void addCardToHand(CardData card, boolean isP1) {
        if (isP1) { mw.gameState.getP1Hand().add(card); mw.refreshP1HandLabel(); }
        else      { mw.gameState.getP2Hand().add(card); mw.refreshP2HandCountLabel(); }
    }

    /** Debug helper: removes every card from the given player's hand and refreshes the hand display. */
    private void clearHand(boolean isP1) {
        var hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
        int removed = hand.size();
        if (removed == 0) return;
        hand.clear();
        if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2") + "'s hand.");
    }

    void addToBreakZone() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.Selection sel = DebugCardPickerDialog.pick(mw.frame, "Add Card to BZ", this::clearBreakZone, "Clear BZ");
        if (sel == null) return;
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        mw.gameState.getIdentity().put(card, isP1);
        mw.addToBreakZone(card);
        mw.logEntry("[Debug] Added " + card.name() + " (" + sel.serial() + ") to " + (isP1 ? "P1" : "P2") + " Break Zone.");
    }

    /** Debug helper: removes every card from the given player's Break Zone and refreshes its display. */
    private void clearBreakZone(boolean isP1) {
        var bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
        int removed = bz.size();
        if (removed == 0) return;
        bz.clear();
        if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2") + "'s Break Zone.");
    }

    /**
     * Debug tool: place a named counter on (or remove one from) any card on the field.
     * Counter names are singular proper nouns — spaces are rejected as typed and
     * capitalization is normalized ("ARISE" → "Arise"). Changes refresh the owning
     * field slot immediately so they are visible on the board while the dialog stays open.
     */
    void addRemoveCounters() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JTextField nameField = new JTextField(16);
        // Counters are singular proper nouns — silently drop any whitespace typed or pasted.
        ((AbstractDocument) nameField.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a) throws BadLocationException {
                fb.insertString(off, stripSpaces(s), a);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a) throws BadLocationException {
                fb.replace(off, len, stripSpaces(s), a);
            }
            private String stripSpaces(String s) {
                return s == null ? null : s.replaceAll("\\s+", "");
            }
        });

        List<CardData> rowCards = new ArrayList<>();
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Player", "Name", "Type", "Position" }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        collectBoardRows(rowCards, model);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 220));

        JDialog dialog = new JDialog(mw.frame, "Add/Remove Counters", false);

        JButton addBtn = new JButton("Add", plusMinusIcon(true, new Color(0x2e9e46)));
        addBtn.addActionListener(e -> applyCounterChange(dialog, table, rowCards, nameField, true));
        JButton removeBtn = new JButton("Remove", plusMinusIcon(false, new Color(0xc0392b)));
        removeBtn.addActionListener(e -> applyCounterChange(dialog, table, rowCards, nameField, false));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Counter name:"));
        top.add(nameField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        buttons.add(addBtn);
        buttons.add(removeBtn);

        dialog.setLayout(new BorderLayout());
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(mw.frame);
        dialog.setVisible(true);
    }

    /** Rebuilds the counter dialog's table rows from the cards currently on both fields. */
    private void collectBoardRows(List<CardData> rowCards, DefaultTableModel model) {
        rowCards.clear();
        model.setRowCount(0);
        for (boolean isP1 : new boolean[] { true, false }) {
            CardData[] backups = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
            for (int i = 0; i < backups.length; i++) {
                if (backups[i] != null) addBoardRow(rowCards, model, isP1, backups[i], i + 1);
            }
            List<CardData> forwards = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
            for (int i = 0; i < forwards.size(); i++) addBoardRow(rowCards, model, isP1, forwards.get(i), i + 1);
            List<CardData> monsters = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
            for (int i = 0; i < monsters.size(); i++) addBoardRow(rowCards, model, isP1, monsters.get(i), i + 1);
        }
    }

    private void addBoardRow(List<CardData> rowCards, DefaultTableModel model, boolean isP1, CardData card, int position) {
        rowCards.add(card);
        model.addRow(new Object[] { isP1 ? "1" : "2", card.name(), card.type(), position });
    }

    /** Applies a single +1/-1 counter change to the selected card and refreshes its field slot. */
    private void applyCounterChange(JDialog dialog, JTable table, List<CardData> rowCards, JTextField nameField, boolean add) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rowCards.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a card in the table first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String raw = nameField.getText().trim();
        if (raw.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Enter a counter name first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Normalize to a proper noun: first letter capitalized, rest lowercase.
        String name = Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase();
        CardData card = rowCards.get(row);
        if (add) {
            mw.gameState.placeCounters(card, name, 1);
            mw.logEntry("[Debug] Added 1 " + name + " Counter to " + card.name()
                    + "  [now: " + mw.gameState.getCountersMap(card) + "]");
        } else {
            if (mw.gameState.removeCounters(card, name, 1) == 0) return; // no such counter — do nothing
            mw.logEntry("[Debug] Removed 1 " + name + " Counter from " + card.name()
                    + "  [now: " + mw.gameState.getCountersMap(card) + "]");
        }
        refreshCounterOwnerSlot(card);
    }

    /** Refreshes whichever field slot currently holds {@code card}, if any (updates the on-screen counter badge). */
    private void refreshCounterOwnerSlot(CardData card) {
        for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
            if (mw.p1ForwardCards.get(i) == card) { mw.refreshP1ForwardSlot(i); return; }
        }
        for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
            if (mw.p2ForwardCards.get(i) == card) { mw.refreshP2ForwardSlot(i); return; }
        }
        for (int i = 0; i < mw.p1BackupCards.length; i++) {
            if (mw.p1BackupCards[i] == card) { mw.refreshP1BackupSlot(i); return; }
        }
        for (int i = 0; i < mw.p2BackupCards.length; i++) {
            if (mw.p2BackupCards[i] == card) { mw.refreshP2BackupSlot(i); return; }
        }
        for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
            if (mw.p1MonsterCards.get(i) == card) { mw.refreshP1MonsterSlot(i); return; }
        }
        for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
            if (mw.p2MonsterCards.get(i) == card) { mw.refreshP2MonsterSlot(i); return; }
        }
    }

    /** Paints a small round-capped {@code +} (or {@code −}) icon in the given color. */
    private static Icon plusMinusIcon(boolean plus, Color color) {
        int sz = 12;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int mid = sz / 2;
        g.drawLine(2, mid, sz - 3, mid);
        if (plus) g.drawLine(mid, 2, mid, sz - 3);
        g.dispose();
        return new ImageIcon(img);
    }

    void setDamage() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Damage", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int cur1 = mw.gameState.getP1DamageZone().size();
        int cur2 = mw.gameState.getP2DamageZone().size();

        int[] p1Value = {cur1};
        int[] p2Value = {cur2};
        JButton[] p1Buttons = makeDamageButtons(p1Value, cur1);
        JButton[] p2Buttons = makeDamageButtons(p2Value, cur2);

        JPanel p1Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (JButton b : p1Buttons) p1Row.add(b);
        JPanel p2Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (JButton b : p2Buttons) p2Row.add(b);

        JTextField serialField = new JTextField(10);
        String HINT = "(optional)";
        serialField.setForeground(Color.GRAY);
        serialField.setText(HINT);
        serialField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (serialField.getText().equals(HINT)) {
                    serialField.setText("");
                    serialField.setForeground(Color.BLACK);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                if (serialField.getText().isEmpty()) {
                    serialField.setForeground(Color.GRAY);
                    serialField.setText(HINT);
                }
            }
        });

        JPanel panel = new JPanel(new GridLayout(3, 2, 8, 4));
        panel.add(new JLabel("P1 Damage (current: " + cur1 + "):"));
        panel.add(p1Row);
        panel.add(new JLabel("P2 Damage (current: " + cur2 + "):"));
        panel.add(p2Row);
        panel.add(new JLabel("Card serial (for additions):"));
        panel.add(serialField);

        int result = JOptionPane.showConfirmDialog(mw.frame, panel, "Set Damage Counts",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        int target1 = p1Value[0];
        int target2 = p2Value[0];

        CardData card = null;
        if (target1 > cur1 || target2 > cur2) {
            String serial = serialField.getText().trim();
            if (serial.isEmpty() || serial.equals(HINT)) serial = "1-001H";
            card = mw.buildCardDataFromSerial(serial);
            if (card == null) {
                JOptionPane.showMessageDialog(mw.frame, "Card not found: " + serial,
                        "Debug Damage", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        List<CardData> dz1 = mw.gameState.getP1DamageZone();
        if (target1 > cur1) {
            for (int i = 0; i < target1 - cur1; i++) dz1.add(card);
        } else {
            for (int i = cur1 - 1; i >= target1; i--) dz1.remove(i);
        }
        mw.refreshDamageZoneSlots(true);

        List<CardData> dz2 = mw.gameState.getP2DamageZone();
        if (target2 > cur2) {
            for (int i = 0; i < target2 - cur2; i++) { dz2.add(card); mw.p2DamageCount++; }
        } else {
            for (int i = cur2 - 1; i >= target2; i--) dz2.remove(i);
            mw.p2DamageCount = target2;
        }
        mw.refreshDamageZoneSlots(false);

        mw.logEntry("[Debug] Damage set — P1: " + target1 + ", P2: " + target2
                + (card != null ? " (card: " + card.name() + ")" : ""));
    }

    private JButton[] makeDamageButtons(int[] valueHolder, int initial) {
        JButton[] buttons = new JButton[7];
        for (int i = 0; i <= 6; i++) {
            int idx = i;
            buttons[i] = new JButton(String.valueOf(i));
            buttons[i].setPreferredSize(new Dimension(28, 28));
            buttons[i].setMargin(new Insets(0, 0, 0, 0));
            buttons[i].setFocusPainted(false);
            buttons[i].addActionListener(e -> {
                valueHolder[0] = idx;
                applyDamageButtonColors(buttons, idx);
            });
        }
        applyDamageButtonColors(buttons, initial);
        return buttons;
    }

    private void applyDamageButtonColors(JButton[] buttons, int value) {
        for (int i = 0; i < buttons.length; i++) {
            boolean filled = i <= value;
            buttons[i].setOpaque(filled);
            buttons[i].setContentAreaFilled(filled);
            buttons[i].setBackground(filled ? Color.RED : null);
        }
    }
}
