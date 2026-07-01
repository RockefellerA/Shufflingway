package shufflingway;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import shufflingway.dialog.DebugCardPickerDialog;

class DebugUtility {

    private final MainWindow mw;

    DebugUtility(MainWindow mw) {
        this.mw = mw;
    }

    void spawnOnCpuField() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String serial = DebugCardPickerDialog.pick(mw.frame, "Spawn Card on CPU Field");
        if (serial == null) return;
        CardData card = mw.buildCardDataFromSerial(serial);
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + serial, "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        mw.gameState.getIdentity().put(card, false);
        if (card.isForward())      mw.placeP2CardInForwardZone(card);
        else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
        else if (card.isBackup()) {
            if (!mw.p2HasAvailableBackupSlot()) {
                JOptionPane.showMessageDialog(mw.frame, "CPU has no free Backup slot.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            mw.placeP2CardInFirstBackupSlot(card);
        } else {
            mw.gameState.getP2Hand().add(card);
            mw.refreshP2HandCountLabel();
            mw.logEntry("[Debug] " + card.name() + " is a Summon — added to CPU hand instead of field.");
            return;
        }
        mw.logEntry("[Debug] Spawned " + card.name() + " (" + serial + ") onto CPU field.");
    }

    void addToCpuHand() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String serial = DebugCardPickerDialog.pick(mw.frame, "Add Card to CPU Hand");
        if (serial == null) return;
        CardData card = mw.buildCardDataFromSerial(serial);
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + serial, "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        mw.gameState.getIdentity().put(card, false);
        mw.gameState.getP2Hand().add(card);
        mw.refreshP2HandCountLabel();
        mw.logEntry("[Debug] Added " + card.name() + " (" + serial + ") to CPU hand.");
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
