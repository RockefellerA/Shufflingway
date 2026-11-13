package shufflingway.menu;

import java.awt.event.ActionEvent;
import java.util.function.Consumer;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

/**
 * Debug menu for the main window.
 * Only shown when debug mode is enabled; owns the card/damage spawn tools.
 * Actions are supplied as callbacks so this menu stays decoupled from the
 * package-private debug utility that implements them.
 */
public class DebugMenu extends JMenu {

    /**
     * @param cpuAcceptsEveryOffer initial state of the "CPU Accepts Every 'You May'" switch
     * @param setCpuAcceptsEveryOffer called with the switch's new state when it is toggled
     */
    public DebugMenu(Runnable spawnOnField, Runnable addToHand, Runnable addToBreakZone,
                     Runnable addRemoveCounters, Runnable activateDullBreakCards, Runnable setDamageAndCrystals,
                     boolean cpuAcceptsEveryOffer, Consumer<Boolean> setCpuAcceptsEveryOffer) {
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
        addItem("Activate/Dull/Break…",
                "Set any card on the field to Active or Dull without firing dull/activate triggers, "
                        + "or break it.",
                activateDullBreakCards);
        addItem("Set Damage/Crystals…",
                "Directly set P1/P2 damage zone and Crystal counts for testing damage-threshold "
                        + "triggers and Crystal costs.",
                setDamageAndCrystals);

        addSeparator();
        JCheckBoxMenuItem acceptsEvery = new JCheckBoxMenuItem("CPU Accepts Every 'You May'", cpuAcceptsEveryOffer);
        acceptsEvery.setToolTipText("The CPU takes every optional offer it is given — a \"you may\", an optional "
                + "cost, a chance to use an ability again — instead of weighing it. For playtesting card text.");
        acceptsEvery.addActionListener((ActionEvent e) -> setCpuAcceptsEveryOffer.accept(acceptsEvery.isSelected()));
        add(acceptsEvery);
    }

    private void addItem(String label, String tooltip, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(tooltip);
        item.addActionListener((ActionEvent e) -> action.run());
        add(item);
    }
}
