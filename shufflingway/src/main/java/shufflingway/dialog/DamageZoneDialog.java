package shufflingway.dialog;

import shufflingway.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.*;

public class DamageZoneDialog {

    public static void show(JFrame owner, List<CardData> zone, String title,
                             Consumer<String> onZoom, Runnable onZoomHide) {
        if (zone.isEmpty()) return;

        JDialog dlg = new JDialog(owner, title + " (" + zone.size() + " cards)", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        for (CardData cd : zone) {
            JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
            cardWrapper.setBackground(cardsPanel.getBackground());

            JLabel lbl = new JLabel("...", SwingConstants.CENTER);
            lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
            lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
            lbl.setOpaque(true);
            lbl.setBackground(Color.DARK_GRAY);
            lbl.setBorder(cd.exBurst()
                    ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                    : BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) {
                    if (lbl.getIcon() != null) onZoom.accept(cd.imageUrl());
                }
                @Override public void mouseExited(MouseEvent e) { onZoomHide.run(); }
            });

            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(cd.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = buf.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
                    g2.dispose();
                    return new ImageIcon(buf);
                }
                @Override protected void done() {
                    try {
                        ImageIcon icon = get();
                        if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();

            final String labelText = cd.name() + (cd.exBurst() ? " EX" : "");
            JLabel nameLabel = cd.exBurst() ? new JLabel(labelText, SwingConstants.CENTER) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    int x = (getWidth() - fm.stringWidth(labelText)) / 2;
                    int y = fm.getAscent() + (getHeight() - fm.getHeight()) / 2;
                    g2.setColor(new Color(0, 0, 0, 180));
                    g2.drawString(labelText, x + 1, y + 1);
                    g2.setColor(Color.YELLOW);
                    g2.drawString(labelText, x, y);
                    g2.dispose();
                }
            } : new JLabel(labelText, SwingConstants.CENTER);
            nameLabel.setFont(FontLoader.loadPixelNESFont(9));
            nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

            cardWrapper.add(lbl,       BorderLayout.CENTER);
            cardWrapper.add(nameLabel, BorderLayout.SOUTH);
            cardsPanel.add(cardWrapper);
        }

        JScrollPane scrollPane = new JScrollPane(cardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(
                Math.min(zone.size() * (CARD_W + 16) + 16, 900),
                CARD_H + 60));

        JButton closeBtn = new JButton("Close");
        closeBtn.setFont(FontLoader.loadPixelNESFont(11));
        closeBtn.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(closeBtn);
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }
}
