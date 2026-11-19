package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;

/**
 * "Play Again?" with Yes and No, asked once the end-of-game banners are up. Modeless, so the final
 * board stays inspectable behind it. Yes and No each close it and then run their callback; closing
 * it any other way — the title bar, Escape — only dismisses the question.
 */
public class PlayAgainDialog extends JDialog {

    /**
     * @param won       whether this player won: the title reads "You Win!" if so, "Game Over" if not
     * @param onYes     run after the dialog closes on Yes
     * @param onNo      run after the dialog closes on No
     * @param noTooltip what No does beyond closing the question, or {@code null} when nothing more
     */
    public PlayAgainDialog(Window owner, boolean won, Runnable onYes, Runnable onNo, String noTooltip) {
        super(owner, won ? "You Win!" : "Game Over", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        JLabel question = new JLabel("Play Again?", SwingConstants.CENTER);
        Font base = question.getFont();
        question.setFont(base.deriveFont(Font.BOLD, base.getSize2D() * 1.4f));

        JButton yes = new JButton("Yes");
        JButton no  = new JButton("No");
        no.setToolTipText(noTooltip);
        yes.addActionListener(e -> { dispose(); onYes.run(); });
        no.addActionListener(e -> { dispose(); onNo.run(); });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(yes);
        buttons.add(no);

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(18, 40, 14, 40));
        content.add(question, BorderLayout.CENTER);
        content.add(buttons,  BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(yes);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
    }
}
