package shufflingway.menu;

import java.awt.event.ActionEvent;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

/**
 * Debug menu for the main window.
 * Only shown when debug mode is enabled; owns the card/damage spawn tools.
 * Actions are supplied as callbacks so this menu stays decoupled from the
 * package-private debug utility that implements them.
 */
public class DebugMenu extends JMenu {

    public DebugMenu(Runnable spawnOnField, Runnable addToHand, Runnable addToBreakZone,
                     Runnable addRemoveCounters, Runnable activateDullCards, Runnable setDamageAndCrystals) {
        super("Debug");

        addItem("Spawn Card on Field…",
                "Place any card directly onto the chosen player's field.",
                spawnOnField);
        addItem("Add Card to Hand…",
                "Add any card directly to the chosen player's hand.",
                addToHand);
        addItem("Add Card to BZ/RFP…",
                "Add any card directly to the chosen player's Break Zone or Removed From Game zone.",
                addToBreakZone);
        addItem("Add/Remove Counters…",
                "Place named counters on (or remove them from) any card on the field.",
                addRemoveCounters);
        addItem("Activate/Dull Cards…",
                "Set any card on the field to Active or Dull without firing dull/activate triggers.",
                activateDullCards);
        addItem("Set Damage/Crystals…",
                "Directly set P1/P2 damage zone and Crystal counts for testing damage-threshold "
                        + "triggers and Crystal costs.",
                setDamageAndCrystals);
    }

    private void addItem(String label, String tooltip, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(tooltip);
        item.addActionListener((ActionEvent e) -> action.run());
        add(item);
    }
}
