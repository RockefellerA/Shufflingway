package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/**
 * Modal searchable card picker over the entire card database. Returns the selected card's serial
 * together with the target player (or {@code null} if cancelled). Used by the debug spawn/add tooling.
 */
public class DebugCardPickerDialog extends JDialog {

    /** A confirmed pick: the chosen card serial and which player it targets. */
    public record Selection(String serial, boolean isP1) {}

    private static final String DB_URL = scraper.AppPaths.dbUrl();
    private static final String[] COLUMNS = {"Serial", "Name", "Type", "Element", "Cost", "Power", "Card Text"};

    /** Sorts serials numerically on the set prefix (e.g. "9-001C" before "10-001H"). */
    private static final java.util.Comparator<Object> SERIAL_ORDER = (a, b) -> {
        String sa = a == null ? "" : a.toString();
        String sb = b == null ? "" : b.toString();
        int da = sa.indexOf('-'), db = sb.indexOf('-');
        if (da > 0 && db > 0) {
            try {
                int na = Integer.parseInt(sa.substring(0, da));
                int nb = Integer.parseInt(sb.substring(0, db));
                if (na != nb) return Integer.compare(na, nb);
            } catch (NumberFormatException ignored) {}
        }
        return sa.compareTo(sb);
    };

    private final DefaultTableModel tableModel;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTable table;
    private String selectedSerial = null;
    /** Target player for the spawn/add; defaults to P2. */
    private boolean targetIsP1 = false;

    private DebugCardPickerDialog(JFrame parent, String title) {
        super(parent, title, true);
        setSize(720, 520);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, SERIAL_ORDER);
        sorter.setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);
        // Hide the Card Text column from view (still searchable via model index 6)
        table.removeColumn(table.getColumnModel().getColumn(6));
        table.getColumnModel().getColumn(1).setPreferredWidth(140);

        JTextField searchField = new JTextField(24);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(searchField.getText()); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(searchField.getText()); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(searchField.getText()); }
        });

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(searchField);

        JRadioButton p1Radio = new JRadioButton("P1");
        JRadioButton p2Radio = new JRadioButton("P2", true);
        ButtonGroup targetGroup = new ButtonGroup();
        targetGroup.add(p1Radio);
        targetGroup.add(p2Radio);
        p1Radio.addActionListener(e -> targetIsP1 = true);
        p2Radio.addActionListener(e -> targetIsP1 = false);
        JPanel targetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        targetPanel.add(new JLabel("Target player:"));
        targetPanel.add(p1Radio);
        targetPanel.add(p2Radio);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(targetPanel, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.CENTER);

        JButton selectButton = new JButton("Select");
        JButton cancelButton = new JButton("Cancel");
        selectButton.addActionListener(e -> confirmSelection());
        cancelButton.addActionListener(e -> { selectedSerial = null; dispose(); });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.add(selectButton);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) confirmSelection();
            }
        });

        add(northPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(selectButton);
        getRootPane().registerKeyboardAction(
                e -> { selectedSerial = null; dispose(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        loadCards();
        searchField.requestFocusInWindow();
    }

    private void applyFilter(String text) {
        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }
        String[] parts = text.trim().split("%", -1);
        StringBuilder sb = new StringBuilder("(?i)(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".*");
            sb.append(Pattern.quote(parts[i]));
        }
        sorter.setRowFilter(RowFilter.regexFilter(sb.toString()));
    }

    private void confirmSelection() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        int modelRow = table.convertRowIndexToModel(row);
        selectedSerial = (String) tableModel.getValueAt(modelRow, 0);
        dispose();
    }

    private void loadCards() {
        String sql = "SELECT serial, name_en, type_en, element, cost, power, text_en FROM cards WHERE serial NOT LIKE 'B-%' AND serial NOT LIKE 'C-%' ORDER BY serial";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tableModel.addRow(new Object[]{
                        rs.getString("serial"), rs.getString("name_en"), rs.getString("type_en"),
                        rs.getString("element"), rs.getObject("cost"), rs.getObject("power"),
                        rs.getString("text_en")
                });
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading cards:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Opens the picker modally and returns the chosen card serial and target player,
     * or {@code null} if cancelled.
     */
    public static Selection pick(JFrame parent, String title) {
        DebugCardPickerDialog dialog = new DebugCardPickerDialog(parent, title);
        dialog.setVisible(true);
        return dialog.selectedSerial == null ? null : new Selection(dialog.selectedSerial, dialog.targetIsP1);
    }
}
