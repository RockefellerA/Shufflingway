package shufflingway;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
 * (or {@code null} if cancelled). Used by the debug "spawn card to CPU" tooling.
 */
public class CardPickerDialog extends JDialog {

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

    private CardPickerDialog(JFrame parent, String title) {
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

        JButton selectButton = new JButton("Spawn");
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

        add(searchPanel, BorderLayout.NORTH);
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
        String sql = "SELECT serial, name_en, type_en, element, cost, power, text_en FROM cards ORDER BY serial";
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
     * Opens the picker modally and returns the chosen card serial, or {@code null} if cancelled.
     */
    public static String pick(JFrame parent, String title) {
        CardPickerDialog dialog = new CardPickerDialog(parent, title);
        dialog.setVisible(true);
        return dialog.selectedSerial;
    }
}
