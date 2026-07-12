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

    public DebugMenu(Runnable spawnOnField, Runnable addToHand, Runnable setDamage) {
        super("Debug");

        addItem("Spawn Card on Field…",
                "Place any card directly onto the chosen player's field.",
                spawnOnField);
        addItem("Add Card to Hand…",
                "Add any card directly to the chosen player's hand.",
                addToHand);
        addItem("Set Damage Counts…",
                "Directly set P1/P2 damage zone counts for testing damage-threshold triggers.",
                setDamage);
    }

    private void addItem(String label, String tooltip, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(tooltip);
        item.addActionListener((ActionEvent e) -> action.run());
        add(item);
    }
}
