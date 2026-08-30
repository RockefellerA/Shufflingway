package shufflingway;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

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
        DebugCardPickerDialog.pickRepeatedWithOrigin(mw.frame, "Spawn Card on Field", this::spawnSelectedOnField);
    }

    private void spawnSelectedOnField(DebugCardPickerDialog.Selection sel) {
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        String who = isP1 ? "P1" : "P2";
        mw.gameState.getIdentity().put(card, isP1);
        // The two cases that never reach the field are settled first, so the arrival below is
        // unconditional and its "as if cast" bookkeeping cannot be recorded for a card that then
        // bailed out.
        if (!card.isForward() && !card.isMonster() && !card.isBackup()) {
            addCardToHand(card, isP1);
            mw.logEntry("[Debug] " + card.name() + " is a Summon — added to " + who + " hand instead of field.");
            return;
        }
        if (card.isBackup() && !(isP1 ? mw.hasAvailableBackupSlot() : mw.p2HasAvailableBackupSlot())) {
            JOptionPane.showMessageDialog(mw.frame, who + " has no free Backup slot.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean asCast = sel.origin() == DebugCardPickerDialog.Origin.HAND;
        // Recorded before the card lands, exactly as executePlay does it, so an enter-the-field
        // ability that counts what its controller has cast this turn counts this card too.
        // The same bookkeeping every real cast records — a later ability asking how many cards,
        // which Jobs or which names were cast this turn gets the same answer either way. Nothing
        // about the payment is simulated; a debug spawn is free.
        if (asCast) mw.noteCardCast(card, isP1);
        boolean prevCast = mw.lastCardWasCast;
        mw.lastCardWasCast = asCast;
        try {
            if (card.isForward()) {
                if (isP1) mw.placeCardInForwardZone(card); else mw.placeP2CardInForwardZone(card);
            } else if (card.isMonster()) {
                if (isP1) mw.placeCardInMonsterZone(card); else mw.placeP2CardInMonsterZone(card);
            } else {
                if (isP1) mw.placeCardInFirstBackupSlot(card); else mw.placeP2CardInFirstBackupSlot(card);
            }
        } finally {
            mw.lastCardWasCast = prevCast;
        }
        mw.logEntry("[Debug] Spawned " + card.name() + " (" + sel.serial() + ") onto " + who + " field "
                + (asCast ? "as a cast from hand." : "as an arrival from the Break Zone."));
    }

    void addToHand() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.pickRepeated(mw.frame, "Add Card to Hand", this::addSelectedToHand, this::clearHand, "Clear Hand");
    }

    private void addSelectedToHand(DebugCardPickerDialog.Selection sel) {
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
        DebugCardPickerDialog.pickRepeatedWithZone(mw.frame, "Add Card to BZ/RFP",
                this::addSelectedToHoldingZone, this::clearHoldingZone);
    }

    private void addSelectedToHoldingZone(DebugCardPickerDialog.Selection sel) {
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        // Identity first either way: both zones route the card by its owner rather than by an
        // argument, so a card whose owner is not recorded yet lands on P2's side of the board.
        mw.gameState.getIdentity().put(card, isP1);
        if (sel.zone() == DebugCardPickerDialog.Zone.RFP) {
            // Face up, and straight into the zone: the debug tool is for setting up a position, so
            // it should not fire the "instead of the Break Zone" redirects that addToBreakZone honours.
            mw.gameState.addToPermanentRfp(card);
            if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
        } else {
            mw.addToBreakZone(card);
        }
        mw.logEntry("[Debug] Added " + card.name() + " (" + sel.serial() + ") to "
                + (isP1 ? "P1" : "P2") + " " + zoneName(sel.zone()) + ".");
    }

    /** Debug helper: empties the given player's Break Zone or RFG zone and refreshes its display. */
    private void clearHoldingZone(boolean isP1, DebugCardPickerDialog.Zone zone) {
        if (zone == DebugCardPickerDialog.Zone.RFP) { clearPermanentRfp(isP1); return; }
        var bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
        int removed = bz.size();
        if (removed == 0) return;
        bz.clear();
        if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2") + "'s Break Zone.");
    }

    /**
     * Empties the given player's permanently-removed zone. The Warp zone sits behind the same RFP
     * button on the board but is left alone: its cards are waiting on counters to reach the field,
     * which is a different thing from having been removed, and nothing this dialog adds goes there.
     *
     * <p>Removed one at a time through {@code removeFromPermanentRfp} rather than by clearing the
     * list, because the zone view is unmodifiable and because that call is what also clears a
     * card's face-down flag.
     */
    private void clearPermanentRfp(boolean isP1) {
        var rfp = isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp();
        int removed = rfp.size();
        if (removed == 0) return;
        for (CardData card : new ArrayList<>(rfp)) mw.gameState.removeFromPermanentRfp(card);
        if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2")
                + "'s Removed From Game zone.");
    }

    /** How a destination zone is named in the debug log. */
    private static String zoneName(DebugCardPickerDialog.Zone zone) {
        return zone == DebugCardPickerDialog.Zone.RFP ? "Removed From Game zone" : "Break Zone";
    }

    /**
     * Debug tool: place a named counter on (or remove one from) any card on the field.
     * Counter names are freeform — they may be multi-word ("Guinea Pig") or all-caps
     * ("EXP"), so the input is used as typed with only leading/trailing whitespace
     * trimmed. Changes refresh the owning field slot immediately so they are visible
     * on the board while the dialog stays open.
     */
    void addRemoveCounters() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JTextField nameField = new JTextField(16);

        List<BoardSlot> rows = new ArrayList<>();
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Player", "Name", "Type", "Position" }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        collectBoardRows(rows, model, false);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 220));

        JDialog dialog = new JDialog(mw.frame, "Add/Remove Counters", false);

        JButton addBtn = new JButton("Add", plusMinusIcon(true, new Color(0x2e9e46)));
        addBtn.addActionListener(e -> applyCounterChange(dialog, table, rows, nameField, true));
        JButton removeBtn = new JButton("Remove", plusMinusIcon(false, new Color(0xc0392b)));
        removeBtn.addActionListener(e -> applyCounterChange(dialog, table, rows, nameField, false));

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

    /** Which field zone a debug board row came from, so a change can find the slot again. */
    private enum FieldZone { BACKUP, FORWARD, MONSTER }

    /**
     * One row of a debug board table. The card alone is enough for counters, which are keyed by
     * card, but the ACTIVE/DULL state lives in a list parallel to the zone's card list, so the
     * zone and index have to be carried too.
     */
    private record BoardSlot(boolean isP1, FieldZone zone, int index, CardData card) {}

    /**
     * Rebuilds a debug dialog's table rows from the cards currently on both fields.
     * {@code withState} adds the trailing ACTIVE/DULL column the activate/dull dialog shows.
     */
    private void collectBoardRows(List<BoardSlot> rows, DefaultTableModel model, boolean withState) {
        rows.clear();
        model.setRowCount(0);
        for (boolean isP1 : new boolean[] { true, false }) {
            CardData[] backups = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
            for (int i = 0; i < backups.length; i++) {
                if (backups[i] != null) addBoardRow(rows, model, isP1, FieldZone.BACKUP, i, backups[i], withState);
            }
            List<CardData> forwards = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
            for (int i = 0; i < forwards.size(); i++) {
                addBoardRow(rows, model, isP1, FieldZone.FORWARD, i, forwards.get(i), withState);
            }
            List<CardData> monsters = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
            for (int i = 0; i < monsters.size(); i++) {
                addBoardRow(rows, model, isP1, FieldZone.MONSTER, i, monsters.get(i), withState);
            }
        }
    }

    private void addBoardRow(List<BoardSlot> rows, DefaultTableModel model, boolean isP1,
                             FieldZone zone, int index, CardData card, boolean withState) {
        BoardSlot slot = new BoardSlot(isP1, zone, index, card);
        rows.add(slot);
        // Positions are shown one-based, matching how the slots read on the board.
        Object[] cells = withState
                ? new Object[] { isP1 ? "1" : "2", card.name(), card.type(), index + 1, stateLabel(stateOf(slot)) }
                : new Object[] { isP1 ? "1" : "2", card.name(), card.type(), index + 1 };
        model.addRow(cells);
    }

    /** Applies a single +1/-1 counter change to the selected card and refreshes its field slot. */
    private void applyCounterChange(JDialog dialog, JTable table, List<BoardSlot> rows, JTextField nameField, boolean add) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rows.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a card in the table first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Freeform name ("Guinea Pig", "EXP") — trim only leading/trailing whitespace.
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Enter a counter name first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        CardData card = rows.get(row).card();
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

    /**
     * Debug tool: set any card on the field to ACTIVE or DULL directly.
     *
     * <p>The state is written straight to the zone's state list rather than going through
     * {@code dullTarget} / the activation steps, so nothing here fires "when this is dulled" or
     * "when this is activated" triggers — same reasoning as {@link #setDamageAndCrystals}: a debug
     * tool used to set up the board a trigger is being tested on must not fire that trigger itself.
     */
    void activateDullCards() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Activate/Dull",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<BoardSlot> rows = new ArrayList<>();
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Player", "Name", "Type", "Position", "State" }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        collectBoardRows(rows, model, true);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(460, 220));

        JDialog dialog = new JDialog(mw.frame, "Activate/Dull Cards", false);

        JButton activateBtn = new JButton("Activate", arrowIcon(true, new Color(0x2e9e46)));
        activateBtn.addActionListener(e -> applyStateChange(dialog, table, model, rows, CardState.ACTIVE));
        JButton dullBtn = new JButton("Dull", arrowIcon(false, new Color(0xc0392b)));
        dullBtn.addActionListener(e -> applyStateChange(dialog, table, model, rows, CardState.DULL));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        buttons.add(activateBtn);
        buttons.add(dullBtn);

        dialog.setLayout(new BorderLayout());
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(mw.frame);
        dialog.setVisible(true);
    }

    /** Sets the selected row's card to {@code state} and refreshes its field slot. */
    private void applyStateChange(JDialog dialog, JTable table, DefaultTableModel model,
                                  List<BoardSlot> rows, CardState state) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rows.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a card in the table first.", "Debug Activate/Dull",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        BoardSlot slot = rows.get(row);
        // The dialog is modeless, so the board can move underneath it — a Forward broken while it
        // is open shifts every later index. Writing a state by a stale index would dull the wrong
        // card, so the row is re-checked against the field and the table rebuilt if it has drifted.
        if (cardAt(slot) != slot.card()) {
            collectBoardRows(rows, model, true);
            JOptionPane.showMessageDialog(dialog, "The board changed — the card list has been refreshed.",
                    "Debug Activate/Dull", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (stateOf(slot) == state) return; // already there — nothing to log
        setSlotState(slot, state);
        model.setValueAt(stateLabel(state), row, 4);
        mw.logEntry("[Debug] " + (state == CardState.ACTIVE ? "Activated " : "Dulled ") + slot.card().name()
                + " (" + (slot.isP1() ? "P1" : "P2") + " " + zoneLabel(slot.zone()) + " " + (slot.index() + 1) + ").");
    }

    /** The card currently occupying {@code slot}'s zone and index, or {@code null} if there is none. */
    private CardData cardAt(BoardSlot slot) {
        switch (slot.zone()) {
            case BACKUP: {
                CardData[] cards = slot.isP1() ? mw.p1BackupCards : mw.p2BackupCards;
                return slot.index() < cards.length ? cards[slot.index()] : null;
            }
            case FORWARD: {
                List<CardData> cards = slot.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
                return slot.index() < cards.size() ? cards.get(slot.index()) : null;
            }
            default: {
                List<CardData> cards = slot.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
                return slot.index() < cards.size() ? cards.get(slot.index()) : null;
            }
        }
    }

    /** The ACTIVE/DULL state recorded for {@code slot}, or {@code null} if the slot is gone. */
    private CardState stateOf(BoardSlot slot) {
        switch (slot.zone()) {
            case BACKUP: {
                CardState[] states = slot.isP1() ? mw.p1BackupStates : mw.p2BackupStates;
                return slot.index() < states.length ? states[slot.index()] : null;
            }
            case FORWARD: {
                List<CardState> states = slot.isP1() ? mw.p1ForwardStates : mw.p2ForwardStates;
                return slot.index() < states.size() ? states.get(slot.index()) : null;
            }
            default: {
                List<CardState> states = slot.isP1() ? mw.p1MonsterStates : mw.p2MonsterStates;
                return slot.index() < states.size() ? states.get(slot.index()) : null;
            }
        }
    }

    /** Writes {@code state} into {@code slot}'s zone and repaints that slot on the board. */
    private void setSlotState(BoardSlot slot, CardState state) {
        boolean isP1 = slot.isP1();
        int idx = slot.index();
        switch (slot.zone()) {
            case BACKUP -> {
                if (isP1) { mw.p1BackupStates[idx] = state; mw.refreshP1BackupSlot(idx); }
                else      { mw.p2BackupStates[idx] = state; mw.refreshP2BackupSlot(idx); }
            }
            case FORWARD -> {
                if (isP1) { mw.p1ForwardStates.set(idx, state); mw.refreshP1ForwardSlot(idx); }
                else      { mw.p2ForwardStates.set(idx, state); mw.refreshP2ForwardSlot(idx); }
            }
            case MONSTER -> {
                if (isP1) { mw.p1MonsterStates.set(idx, state); mw.refreshP1MonsterSlot(idx); }
                else      { mw.p2MonsterStates.set(idx, state); mw.refreshP2MonsterSlot(idx); }
            }
        }
    }

    /** How a state reads in the dialog's State column. */
    private static String stateLabel(CardState state) {
        return state == CardState.DULL ? "Dull" : "Active";
    }

    /** How a field zone is named in the debug log. */
    private static String zoneLabel(FieldZone zone) {
        return switch (zone) {
            case BACKUP  -> "Backup";
            case FORWARD -> "Forward";
            case MONSTER -> "Monster";
        };
    }

    /** Paints a small solid triangle pointing up (activate) or down (dull) in the given color. */
    private static Icon arrowIcon(boolean up, Color color) {
        int sz = 12;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        int[] xs = { 1, sz - 1, sz / 2 };
        int[] ys = up ? new int[] { sz - 2, sz - 2, 1 } : new int[] { 1, 1, sz - 2 };
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return new ImageIcon(img);
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

    /** One "label: control" row of {@link #setDamageAndCrystals}'s form, at {@code row}. */
    private static void addDialogRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 8);
        panel.add(new JLabel(label), c);

        c.gridx  = 1;
        c.insets = new Insets(2, 0, 2, 0);
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
    }

    /** A rule across both columns, separating one group of rows from the next. */
    private static void addSeparatorRow(JPanel panel, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx     = 0;
        c.gridy     = row;
        c.gridwidth = 2;
        c.fill      = GridBagConstraints.HORIZONTAL;
        c.insets    = new Insets(8, 0, 8, 0);
        panel.add(new JSeparator(SwingConstants.HORIZONTAL), c);
    }

    /**
     * Sets both players' damage counts and Crystal counts directly.
     *
     * <p>Damage is a row of 0–6 buttons because that is the whole range a player can sit at;
     * Crystals have no comparable ceiling, so they get a spinner. Both are written straight to
     * {@link GameState} rather than through the effects that normally change them, so nothing here
     * fires "you receive damage" or "gain a 《C》" triggers — this is a state setter, and a debug
     * tool that fired triggers could not be used to set up the board a trigger is being tested on.
     */
    void setDamageAndCrystals() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Damage/Crystals",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int cur1 = mw.gameState.getP1DamageZone().size();
        int cur2 = mw.gameState.getP2DamageZone().size();
        int curC1 = mw.gameState.getP1Crystals();
        int curC2 = mw.gameState.getP2Crystals();

        int[] p1Value = {cur1};
        int[] p2Value = {cur2};
        JButton[] p1Buttons = makeDamageButtons(p1Value, cur1);
        JButton[] p2Buttons = makeDamageButtons(p2Value, cur2);
        JSpinner p1Crystals = makeCrystalSpinner(curC1);
        JSpinner p2Crystals = makeCrystalSpinner(curC2);

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

        // The serial belongs with the damage rows — it is the card the added damage is dealt with,
        // and it is read only when a damage count goes up. The rule separates it from the Crystal
        // rows, which nothing above them feeds. GridBag rather than GridLayout so the separator can
        // span both columns while the labels stay in one aligned column.
        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addDialogRow(panel, row++, "P1 Damage (current: " + cur1 + "):", p1Row);
        addDialogRow(panel, row++, "P2 Damage (current: " + cur2 + "):", p2Row);
        addDialogRow(panel, row++, "Card serial (for additions):", serialField);
        addSeparatorRow(panel, row++);
        addDialogRow(panel, row++, "P1 Crystals (current: " + curC1 + "):", p1Crystals);
        addDialogRow(panel, row,   "P2 Crystals (current: " + curC2 + "):", p2Crystals);

        int result = JOptionPane.showConfirmDialog(mw.frame, panel, "Set Damage/Crystals",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        int target1 = p1Value[0];
        int target2 = p2Value[0];
        int targetC1 = (Integer) p1Crystals.getValue();
        int targetC2 = (Integer) p2Crystals.getValue();

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

        // Applied as a delta because GameState exposes add/spend rather than a setter; computing it
        // from the current count is also what keeps the total off negative.
        mw.gameState.addP1Crystals(targetC1 - curC1);
        mw.gameState.addP2Crystals(targetC2 - curC2);
        mw.refreshCrystalDisplays();

        mw.logEntry("[Debug] Damage set — P1: " + target1 + ", P2: " + target2
                + (card != null ? " (card: " + card.name() + ")" : "")
                + "; Crystals set — P1: " + targetC1 + ", P2: " + targetC2);
    }

    /** 0–20 covers any board a debug session needs; the display renders the count as a number. */
    private JSpinner makeCrystalSpinner(int initial) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, 0, 20, 1));
        spinner.setPreferredSize(new Dimension(56, 24));
        return spinner;
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
