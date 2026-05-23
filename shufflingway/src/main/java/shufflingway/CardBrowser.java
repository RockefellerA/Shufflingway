package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

import scraper.CardScraper;

public class CardBrowser extends JDialog {

    private static final String DB_URL = scraper.AppPaths.dbUrl();
    private static final String[] COLUMNS = {
        "Serial", "Name", "Type", "Element", "Cost", "Power",
        "Rarity", "Job", "Category 1", "Category 2", "Card Text"
    };

    private static final Color LB_BG = new Color(50, 50, 50);
    private static final Color LB_FG = new Color(0xFF, 0xD7, 0x00);

    private static final int PREVIEW_W = 429;
    private static final int PREVIEW_H = 600;

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
    private final JLabel cardImageLabel;
    private final JLabel countLabel;
    private TableRowSorter<DefaultTableModel> sorter;
    private final Set<String> lbSerials = new HashSet<>();

    public CardBrowser(JFrame parent) {
        super(parent, "Card Browser", true);
        setSize(960 + PREVIEW_W, PREVIEW_H + 100);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        // --- Card table ---
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };

        JTable cardTable = new JTable(tableModel) {
            @Override
            public java.awt.Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                java.awt.Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row) && col == 0) {
                    String serial = (String) tableModel.getValueAt(convertRowIndexToModel(row), 0);
                    if (lbSerials.contains(serial)) {
                        c.setBackground(LB_BG);
                        c.setForeground(LB_FG);
                    } else {
                        c.setBackground(getBackground());
                        c.setForeground(getForeground());
                    }
                }
                return c;
            }
        };
        cardTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cardTable.getTableHeader().setReorderingAllowed(false);
        cardTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, SERIAL_ORDER);
        sorter.setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.ASCENDING)));
        cardTable.setRowSorter(sorter);
        // Hide the Card Text column from the view (still searchable via model index 10)
        cardTable.removeColumn(cardTable.getColumnModel().getColumn(10));

        // Widen Name (1), shrink Type (2), narrow Cost/Power/Rarity
        cardTable.getColumnModel().getColumn(1).setPreferredWidth(86);
        cardTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        cardTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        cardTable.getColumnModel().getColumn(4).setMaxWidth(60);
        cardTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        cardTable.getColumnModel().getColumn(5).setMaxWidth(70);
        cardTable.getColumnModel().getColumn(6).setPreferredWidth(55);
        cardTable.getColumnModel().getColumn(6).setMaxWidth(65);

        // Grey out empty cells in Job (7), Category 1 (8), Category 2 (9)
        DefaultTableCellRenderer greyIfEmpty = new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && (value == null || value.toString().isBlank())) {
                    setBackground(Color.LIGHT_GRAY);
                } else {
                    setBackground(table.getBackground());
                }
                return this;
            }
        };
        for (int col : new int[]{7, 9}) {
            cardTable.getColumnModel().getColumn(col).setCellRenderer(greyIfEmpty);
        }

        // Grey out Power (5) when value is 0 or null
        cardTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                boolean empty = value == null || Integer.valueOf(0).equals(value);
                super.getTableCellRendererComponent(table, empty ? null : value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    setBackground(empty ? Color.LIGHT_GRAY : table.getBackground());
                }
                return this;
            }
        });

        // --- Search bar ---
        String[] columnChoices = new String[COLUMNS.length + 1];
        columnChoices[0] = "All Columns";
        System.arraycopy(COLUMNS, 0, columnChoices, 1, COLUMNS.length);

        JComboBox<String> columnDropdown = new JComboBox<>(columnChoices);
        JTextField searchField = new JTextField(20);
        JButton searchButton = new JButton("🔍"); // 🔍
        JButton clearButton  = new JButton("✕");

        searchButton.addActionListener(e -> applyFilter(searchField.getText(), columnDropdown.getSelectedIndex()));
        clearButton.addActionListener(e -> {
            searchField.setText("");
            sorter.setRowFilter(null);
            updateCount();
        });
        searchField.addActionListener(e -> applyFilter(searchField.getText(), columnDropdown.getSelectedIndex()));

        // --- Update button + spinner ---
        JButton updateButton = new JButton("Update Cards");
        JProgressBar spinner = new JProgressBar();
        spinner.setIndeterminate(true);
        spinner.setVisible(false);
        spinner.setPreferredSize(new Dimension(120, updateButton.getPreferredSize().height));

        updateButton.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(
                    CardBrowser.this,
                    "This will re-fetch all cards from the Square Enix API and update the database.\nContinue?",
                    "Update Cards",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            runScrape(updateButton, spinner);
        });

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(columnDropdown);
        searchPanel.add(searchField);
        searchPanel.add(searchButton);
        searchPanel.add(clearButton);
        searchPanel.add(updateButton);
        searchPanel.add(spinner);

        // --- Full-size card image preview ---
        cardImageLabel = new JLabel("Select a card to preview", SwingConstants.CENTER);
        cardImageLabel.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
        cardImageLabel.setBorder(BorderFactory.createEtchedBorder());

        JPanel imagePanel = new JPanel(new BorderLayout());
        imagePanel.setPreferredSize(new Dimension(PREVIEW_W + 10, PREVIEW_H));
        imagePanel.add(cardImageLabel, BorderLayout.CENTER);

        // Update preview on row selection (mouse click or keyboard navigation)
        cardTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = cardTable.getSelectedRow();
                if (row >= 0) {
                    int modelRow = cardTable.convertRowIndexToModel(row);
                    loadCardImageAsync((String) tableModel.getValueAt(modelRow, 0));
                }
            }
        });

        // --- Status bar ---
        countLabel = new JLabel("", SwingConstants.RIGHT);
        countLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));

        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(countLabel, BorderLayout.EAST);

        add(searchPanel, BorderLayout.NORTH);
        add(new JScrollPane(cardTable), BorderLayout.CENTER);
        add(imagePanel, BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        SwingWorker<Void, Void> initWorker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                return null;
            }
            @Override
            protected void done() {
                if (!new java.io.File("shufflingway.db").exists()) {
                    int choice = JOptionPane.showConfirmDialog(
                            CardBrowser.this,
                            "No card database found. Fetch card data from the Square Enix API now?",
                            "No Database Found",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        runScrape(updateButton, spinner);
                    }
                } else {
                    loadCards();
                }
            }
        };
        initWorker.execute();
    }

    private void runScrape(JButton updateButton, JProgressBar spinner) {
        updateButton.setEnabled(false);
        spinner.setVisible(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                CardScraper.main(new String[0]);
                return null;
            }
            @Override
            protected void done() {
                spinner.setVisible(false);
                tableModel.setRowCount(0);
                loadCards();
                updateButton.setEnabled(true);
            }
        }.execute();
    }

    private void applyFilter(String text, int columnDropdownIndex) {
        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
        } else {
            String regex = "(?i)" + Pattern.quote(text.trim());
            if (columnDropdownIndex == 0) {
                sorter.setRowFilter(RowFilter.regexFilter(regex));
            } else {
                sorter.setRowFilter(RowFilter.regexFilter(regex, columnDropdownIndex - 1));
            }
        }
        updateCount();
    }

    private void updateCount() {
        int visible = sorter.getViewRowCount();
        int total   = tableModel.getRowCount();
        countLabel.setText(visible == total
                ? "Total Cards: " + total
                : "Showing " + visible + " of " + total + " cards");
    }

    private void loadCards() {
        String sql = "SELECT serial, name_en, type_en, element, cost, power, rarity, job_en, "
                   + "category_1, category_2, text_en, limit_break FROM cards ORDER BY serial";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String serial = rs.getString("serial");
                if (rs.getInt("limit_break") == 1) lbSerials.add(serial);
                tableModel.addRow(new Object[]{
                    serial,
                    rs.getString("name_en"),
                    rs.getString("type_en"),
                    rs.getString("element"),
                    rs.getObject("cost"),
                    rs.getObject("power"),
                    rs.getString("rarity"),
                    rs.getString("job_en"),
                    rs.getString("category_1"),
                    rs.getString("category_2"),
                    rs.getString("text_en")
                });
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading cards:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
        updateCount();
    }

    private void loadCardImageAsync(String serial) {
        cardImageLabel.setIcon(null);
        cardImageLabel.setText("Loading…");

        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                try (Connection conn = DriverManager.getConnection(DB_URL);
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT image_url FROM cards WHERE serial = ?")) {
                    ps.setString(1, serial);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String url = rs.getString("image_url");
                            if (url != null && !url.isBlank()) {
                                Image img = ImageCache.load(url);
                                if (img != null) {
                                    return new ImageIcon(
                                            img.getScaledInstance(PREVIEW_W, PREVIEW_H, Image.SCALE_SMOOTH));
                                }
                            }
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) {
                        cardImageLabel.setIcon(icon);
                        cardImageLabel.setText(null);
                    } else {
                        cardImageLabel.setIcon(null);
                        cardImageLabel.setText("No image available");
                    }
                } catch (InterruptedException | ExecutionException e) {
                    cardImageLabel.setIcon(null);
                    cardImageLabel.setText("Error loading image");
                    e.printStackTrace();
                }
            }
        }.execute();
    }
}
