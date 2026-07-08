package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.*;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.*;

/**
 * Modal dialog that lets P1 select exactly {@code ec.count()} cards from their Break Zone
 * to remove from the game as the extra cost for a summon.  Calls {@code onConfirm} with the
 * selected {@link CardData} objects after the player clicks Confirm.
 */
public class ExtraCostBzSelectDialog {

    private final JFrame            owner;
    private final CardData          card;
    private final ExtraCost         ec;
    private final List<CardData>    eligible;
    private final Consumer<String>  onZoom;
    private final Runnable          onZoomHide;
    private final Consumer<List<CardData>> onConfirm;

    public ExtraCostBzSelectDialog(JFrame owner, CardData card, ExtraCost ec,
            List<CardData> eligible,
            Consumer<String> onZoom, Runnable onZoomHide,
            Consumer<List<CardData>> onConfirm) {
        this.owner      = owner;
        this.card       = card;
        this.ec         = ec;
        this.eligible   = eligible;
        this.onZoom     = onZoom;
        this.onZoomHide = onZoomHide;
        this.onConfirm  = onConfirm;
    }

    public void show() {
        int needed = ec.count();
        String title = "Extra Cost: " + card.name() + "  (" + ec.description() + ")";

        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Set<Integer>   selected   = new HashSet<>();
        List<JLabel>   cardLabels = new ArrayList<>();
        JPanel         cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JLabel statusLabel = new JLabel("", SwingConstants.CENTER);
        statusLabel.setFont(FontLoader.loadPixelNESFont(10));

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
        confirmBtn.setEnabled(false);

        Runnable refresh = () -> {
            int remaining = needed - selected.size();
            statusLabel.setText(remaining > 0
                    ? "Select " + remaining + " more card(s) to remove from the game."
                    : "Ready — click Confirm.");
            confirmBtn.setEnabled(selected.size() == needed);
            for (int i = 0; i < cardLabels.size(); i++) {
                cardLabels.get(i).setBorder(selected.contains(i)
                        ? CardAnimation.createCardGlowBorder(Color.ORANGE)
                        : BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
            }
        };

        // Build card grid
        for (int i = 0; i < eligible.size(); i++) {
            final int idx = i;
            CardData cd = eligible.get(i);

            JPanel wrapper = new JPanel(new BorderLayout(0, 4));
            wrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = HandPickDialog.makeCardLabel();
            lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cardLabels.add(lbl);

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selected.contains(idx)) selected.remove(idx);
                    else if (selected.size() < needed) selected.add(idx);
                    refresh.run();
                }
            });

            HandPickDialog.loadCardImage(lbl, cd.imageUrl());

            JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            wrapper.add(lbl,       BorderLayout.CENTER);
            wrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refresh.run();

        confirmBtn.addActionListener(ae -> {
            onZoomHide.run();
            List<CardData> chosen = new ArrayList<>();
            List<Integer>  sorted = new ArrayList<>(selected);
            sorted.sort(null);
            for (int i : sorted) chosen.add(eligible.get(i));
            dlg.dispose();
            onConfirm.accept(chosen);
        });

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(eligible.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JLabel titleLabel = new JLabel(
                "<html><center>" + card.name() + " — Extra Cost: " + ec.description() + "</center></html>",
                SwingConstants.CENTER);
        titleLabel.setFont(FontLoader.loadPixelNESFont(11));

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(confirmBtn,  BorderLayout.EAST);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 6));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        mainPanel.add(titleLabel,  BorderLayout.NORTH);
        mainPanel.add(scrollPane,  BorderLayout.CENTER);
        mainPanel.add(south,       BorderLayout.SOUTH);

        dlg.getContentPane().add(mainPanel);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }
}
