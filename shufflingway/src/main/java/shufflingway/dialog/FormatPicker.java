package shufflingway.dialog;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import shufflingway.DeckFormat;

/**
 * A game's deck rules as the player sets them: a Format row of radio buttons and "Enable Banlist"
 * under it. Shown in the CPU game's New Game dialog and wherever a multiplayer host sets up a match;
 * the other player is shown the result instead.
 *
 * <p>A format that is not yet playable ({@link DeckFormat#available()}) is listed but disabled.
 */
public class FormatPicker extends JPanel {

    private final Map<DeckFormat, JRadioButton> radios = new EnumMap<>(DeckFormat.class);
    private final JCheckBox banlistBox = new JCheckBox("Enable Banlist");
    private Runnable onChange = () -> {};

    public FormatPicker(DeckFormat format, boolean banlist) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        JPanel formatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        formatRow.setOpaque(false);
        formatRow.add(new JLabel("Format:"));
        ButtonGroup group = new ButtonGroup();
        for (DeckFormat f : DeckFormat.values()) {
            JRadioButton rb = new JRadioButton(f.label());
            rb.setOpaque(false);
            rb.setEnabled(f.available());
            if (!f.available()) rb.setToolTipText(f.label() + " is not available yet.");
            rb.addActionListener(e -> onChange.run());
            group.add(rb);
            formatRow.add(rb);
            radios.put(f, rb);
        }
        formatRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        banlistBox.setOpaque(false);
        banlistBox.setToolTipText("Decks that break the banlist cannot be chosen.");
        banlistBox.addActionListener(e -> onChange.run());
        banlistBox.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        banlistBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(formatRow);
        add(banlistBox);
        setValues(format, banlist);
    }

    /** Run whenever the player changes the format or the banlist; read the new values from here. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> {};
    }

    public DeckFormat format() {
        for (Map.Entry<DeckFormat, JRadioButton> e : radios.entrySet())
            if (e.getValue().isSelected()) return e.getKey();
        return DeckFormat.STANDARD;
    }

    public boolean banlist() { return banlistBox.isSelected(); }

    /** Shows {@code format} and {@code banlist} without reporting a change. */
    public void setValues(DeckFormat format, boolean banlist) {
        DeckFormat f = format != null && format.available() ? format : DeckFormat.STANDARD;
        radios.get(f).setSelected(true);
        banlistBox.setSelected(banlist);
    }

    /** Locks or unlocks the controls; a format that is not available stays disabled. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        radios.forEach((f, rb) -> rb.setEnabled(enabled && f.available()));
        banlistBox.setEnabled(enabled);
    }
}
