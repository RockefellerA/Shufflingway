package shufflingway.menu;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.BiConsumer;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;

import shufflingway.CardBrowser;
import shufflingway.DeckManager;
import shufflingway.dialog.PreferencesDialog;

/**
 * File menu for the main window.
 * Owns New Game, Deck Manager, Card Browser, Preferences, and Exit items.
 */
public class FileMenu extends JMenu {

    /**
     * @param newGame New Game: the main window decides whether that means choosing decks for a
     *                game against the CPU or asking a connected opponent
     * @param onNotReadyIndicatorChanged redraws the board's Forwards when Preferences changes how a
     *                Forward that is not ready yet is marked
     */
    public FileMenu(JFrame owner, Runnable newGame, Runnable onLayoutChanged,
            BiConsumer<Boolean, String> onBoardColorChanged, Runnable onNotReadyIndicatorChanged) {
        super("File");

        JMenuItem newGameItem = new JMenuItem("New Game");
        add(newGameItem);
        newGameItem.addActionListener((ActionEvent e) -> newGame.run());

        JMenuItem deckManager = new JMenuItem("Deck Manager");
        add(deckManager);
        deckManager.addActionListener((ActionEvent e) -> {
            DeckManager dm = new DeckManager(owner);
            dm.setVisible(true);
        });

        JMenuItem cardBrowser = new JMenuItem("Card Browser");
        add(cardBrowser);
        cardBrowser.addActionListener((ActionEvent e) -> {
            CardBrowser cb = new CardBrowser(owner);
            cb.setVisible(true);
        });

        JMenuItem preferences = new JMenuItem("Preferences");
        add(preferences);
        preferences.addActionListener((ActionEvent e) -> {
            PreferencesDialog dialog = new PreferencesDialog(owner, onLayoutChanged, onBoardColorChanged,
                    onNotReadyIndicatorChanged);
            dialog.setVisible(true);
        });

        addSeparator();

        JMenuItem exit = new JMenuItem("Exit Shufflingway");
        exit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        add(exit);
        exit.addActionListener((ActionEvent e) -> {
            int result = JOptionPane.showConfirmDialog(owner,
                    "Are you sure you want to quit?", exit.getText(),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) System.exit(0);
        });
    }
}
