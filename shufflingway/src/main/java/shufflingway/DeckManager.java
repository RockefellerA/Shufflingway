package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckEntry;

public class DeckManager extends JFrame {

    private static final String[] BROWSER_COLUMNS = {
        "Serial", "Name", "Type", "Element", "Cost", "Power", "Rarity", "Job", "Category 1", "Category 2", "Card Text"
    };
    private static final String[] DECK_COLUMNS = {
        "Qty", "Serial", "Name", "Type", "Element", "Cost", "Power", "Job", "Category 1", "Category 2"
    };
    private static final int MAX_DECK_SIZE    = 50;
    private static final int MAX_LB_DECK_SIZE = 8;
    private static final int MAX_COPIES       = 3;

    private static final Pattern FFDECKS_CARD_LINE =
            Pattern.compile("^(\\d+)\\s+.+\\(([^)]+)\\)\\s*$");

    // Deck list (left panel)
    private final DefaultListModel<DeckEntry> deckListModel = new DefaultListModel<>();
    private final JList<DeckEntry> deckList = new JList<>(deckListModel);

    // Card browser (top-center)
    private final DefaultTableModel browserModel;
    private TableRowSorter<DefaultTableModel> browserSorter;
    private JTable cardTable;

    // Deck contents (bottom-center)
    private final DefaultTableModel deckModel;
    private JTable deckTable;

    private final JLabel countLabel;
    private final JLabel forwardLabel;
    private final JLabel backupLabel;
    private final JLabel summonLabel;
    private final JLabel monsterLabel;
    private final JLabel cardImageLabel;

    private static final Color LB_BG = new Color(50, 50, 50);
    private static final Color LB_FG = new Color(0xFF, 0xD7, 0x00);

    private static final Color FORMAT_INACTIVE = new Color(0x60, 0x60, 0x60);
    private static final Color FORMAT_S_COLOR  = new Color(0x15, 0x65, 0xC0);
    private static final Color FORMAT_L3_COLOR = new Color(0xE6, 0x51, 0x00);
    private static final Color FORMAT_L6_COLOR = new Color(0x2E, 0x7D, 0x32);
    private static final Color FORMAT_T_COLOR  = new Color(0x6A, 0x1B, 0x9A);

    private static final Set<String> TITLE_EXCLUDED_CATEGORIES =
            Set.of("Special", "Anniversary", "FFRK", "MQ");

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

    private static final int PREVIEW_W = 429;
    private static final int PREVIEW_H = 600;

    private DeckDatabase db;
    private int selectedDeckId = -1;
    private JButton addBtn;
    private JButton addMaxBtn;
    private Set<String> lbSerials = new HashSet<>();

    // Format legality labels
    private JLabel formatS, formatL3, formatL6, formatT;

    public DeckManager(JFrame parent) {
        super("Deck Manager");
        setSize(1600, 800);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(4, 4));

        browserModel = new DefaultTableModel(BROWSER_COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        deckModel = new DefaultTableModel(DECK_COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        countLabel = new JLabel(formatCountLabel(0, 0), SwingConstants.RIGHT);
        countLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));

        cardImageLabel = new JLabel("Select a card to preview", SwingConstants.CENTER);
        cardImageLabel.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
        cardImageLabel.setBorder(BorderFactory.createEtchedBorder());

        forwardLabel = new JLabel("0 Forward(s)", SwingConstants.RIGHT);
        backupLabel = new JLabel("0 Backup(s)", SwingConstants.RIGHT);
        summonLabel = new JLabel("0 Summon(s)", SwingConstants.RIGHT);
        monsterLabel = new JLabel("0 Monster(s)", SwingConstants.RIGHT);

        forwardLabel.setName("Forward(s)");
        backupLabel.setName("Backup(s)");
        summonLabel.setName("Summon(s)");
        monsterLabel.setName("Monster(s)");

        forwardLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));
        backupLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));
        summonLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));
        monsterLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8));

        JPanel imagePanel = new JPanel(new BorderLayout());
        imagePanel.setPreferredSize(new Dimension(PREVIEW_W + 10, PREVIEW_H));
        imagePanel.add(cardImageLabel, BorderLayout.CENTER);

        add(buildDeckListPanel(), BorderLayout.WEST);
        add(buildCenterSplit(),   BorderLayout.CENTER);
        add(imagePanel,           BorderLayout.EAST);

        // Open DB and load data
        try {
            db = new DeckDatabase();
            lbSerials = db.getLbSerials();
            loadDeckList();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(parent, "Error opening database:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }

        // Load card browser in the background so the dialog opens immediately
        loadBrowserCards();

        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (db != null) try { db.close(); } catch (SQLException ignored) {}
            }
        });
    }

    // -------------------------------------------------------------------------
    // Left panel: saved deck list
    // -------------------------------------------------------------------------

    private JPanel buildDeckListPanel() {
        deckList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deckList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DeckEntry entry = deckList.getSelectedValue();
                if (entry != null) {
                    selectedDeckId = entry.id();
                    loadDeckCards(selectedDeckId);
                }
            }
        });

        JButton newBtn    = new JButton("New");
        JButton renameBtn = new JButton("Rename");
        JButton copyBtn   = new JButton("Copy");
        JButton deleteBtn = new JButton("Delete");

        newBtn.addActionListener(e    -> onNewDeck());
        renameBtn.addActionListener(e -> onRenameDeck());
        copyBtn.addActionListener(e   -> onCopyDeck());
        deleteBtn.addActionListener(e -> onDeleteDeck());

        JButton importBtn = new JButton("Import ▾");
        JButton exportBtn = new JButton("Export ▾");
        importBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem shuffItem = new JMenuItem("Shufflingway");
            JMenuItem ffItem    = new JMenuItem("FFDecks");
            shuffItem.addActionListener(ev -> onImportDeck());
            ffItem.addActionListener(ev -> onImportFFDecks());
            menu.add(shuffItem);
            menu.add(ffItem);
            menu.show(importBtn, 0, importBtn.getHeight());
        });
        exportBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem shuffItem = new JMenuItem("Shufflingway");
            JMenuItem ffItem    = new JMenuItem("FFDecks");
            shuffItem.addActionListener(ev -> onExportDeck());
            ffItem.addActionListener(ev -> onExportFFDecks());
            menu.add(shuffItem);
            menu.add(ffItem);
            menu.show(exportBtn, 0, exportBtn.getHeight());
        });

        JPanel mgmtPanel = new JPanel(new GridLayout(2, 2, 4, 4));
        mgmtPanel.add(newBtn);
        mgmtPanel.add(renameBtn);
        mgmtPanel.add(copyBtn);
        mgmtPanel.add(deleteBtn);

        JPanel ioPanel = new JPanel(new GridLayout(1, 2, 4, 4));
        ioPanel.add(importBtn);
        ioPanel.add(exportBtn);

        JPanel southPanel = new JPanel(new BorderLayout(0, 4));
        southPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        southPanel.add(mgmtPanel, BorderLayout.NORTH);
        southPanel.add(ioPanel,   BorderLayout.SOUTH);

        JLabel title = new JLabel("My Decks", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(200, 0));
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(title,                        BorderLayout.NORTH);
        panel.add(new JScrollPane(deckList),    BorderLayout.CENTER);
        panel.add(southPanel,                   BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Center: card browser (top) + deck contents (bottom)
    // -------------------------------------------------------------------------

    private JSplitPane buildCenterSplit() {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildBrowserPanel(), buildDeckContentPanel());
        split.setResizeWeight(0.55);
        // Defer divider placement until the dialog is shown and sized
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.55));
        return split;
    }

    private JPanel buildBrowserPanel() {
        cardTable = new JTable(browserModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row) && renderer == getDefaultRenderer(Object.class)) {
                    String serial = (String) browserModel.getValueAt(convertRowIndexToModel(row), 0);
                    if (lbSerials.contains(serial) && col == 0) {
                        c.setBackground(LB_BG);
                        c.setForeground(LB_FG);
                    } else {
                        c.setBackground(getBackground());
                        c.setForeground(getForeground());
                    }
                }
                return c;
            }
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row < 0 || col < 0) return null;
                Object value = getValueAt(row, col);
                if (value == null) return null;
                String text = value.toString();
                if (text.isBlank()) return null;
                int textWidth = getFontMetrics(getFont()).stringWidth(text) + 4;
                return textWidth > getCellRect(row, col, false).width ? text : null;
            }
        };
        cardTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cardTable.getTableHeader().setReorderingAllowed(false);
        cardTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        browserSorter = new TableRowSorter<>(browserModel);
        browserSorter.setComparator(0, SERIAL_ORDER);
        browserSorter.setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(0, javax.swing.SortOrder.ASCENDING)));
        cardTable.setRowSorter(browserSorter);
        // Hide the Card Text column from the view (still searchable via model index 10)
        cardTable.removeColumn(cardTable.getColumnModel().getColumn(10));
        applyBrowserRenderers(cardTable);
        setNarrowColumns(cardTable);
        cardTable.getColumnModel().getColumn(6).setMaxWidth(55);

        cardTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int viewRow = cardTable.getSelectedRow();
                if (viewRow >= 0) {
                    int modelRow = cardTable.convertRowIndexToModel(viewRow);
                    loadCardImageAsync((String) browserModel.getValueAt(modelRow, 0));
                }
            }
        });

        // Search controls
        String[] columnChoices = new String[BROWSER_COLUMNS.length + 1];
        columnChoices[0] = "All Columns";
        System.arraycopy(BROWSER_COLUMNS, 0, columnChoices, 1, BROWSER_COLUMNS.length);

        JComboBox<String> columnDropdown = new JComboBox<>(columnChoices);
        JTextField searchField = new JTextField(20);
        JButton searchButton = new JButton("\uD83D\uDD0D"); // 🔍
        JButton clearButton  = new JButton("✕");

        Runnable doSearch = () -> applyFilter(searchField.getText(), columnDropdown.getSelectedIndex());
        searchButton.addActionListener(e -> doSearch.run());
        clearButton.addActionListener(e -> { searchField.setText(""); browserSorter.setRowFilter(null); });
        searchField.addActionListener(e -> doSearch.run());

        addBtn = new JButton("Add 1 to Deck  ▼");
        addBtn.setEnabled(false);
        addBtn.addActionListener(e -> addSelectedCard());

        addMaxBtn = new JButton("Add Max to Deck");
        addMaxBtn.setEnabled(false);
        addMaxBtn.addActionListener(e -> addMaxOfSelectedCard());

        // Double-click also adds one
        cardTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) addSelectedCard();
            }
        });

        // Refresh addMaxBtn label/state when the browser selection changes
        cardTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshAddMaxBtn();
        });

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(columnDropdown);
        searchPanel.add(searchField);
        searchPanel.add(searchButton);
        searchPanel.add(clearButton);
        searchPanel.add(addBtn);
        searchPanel.add(addMaxBtn);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Card Browser"));
        panel.add(searchPanel,                BorderLayout.NORTH);
        panel.add(new JScrollPane(cardTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDeckContentPanel() {
        deckTable = new JTable(deckModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row) && renderer == getDefaultRenderer(Object.class)) {
                    String serial = (String) deckModel.getValueAt(row, 1);
                    if (lbSerials.contains(serial) && col == 1) {
                        c.setBackground(LB_BG);
                        c.setForeground(LB_FG);
                    } else {
                        c.setBackground(getBackground());
                        c.setForeground(getForeground());
                    }
                }
                return c;
            }
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row < 0 || col < 0) return null;
                Object value = getValueAt(row, col);
                if (value == null) return null;
                String text = value.toString();
                if (text.isBlank()) return null;
                int textWidth = getFontMetrics(getFont()).stringWidth(text) + 4;
                return textWidth > getCellRect(row, col, false).width ? text : null;
            }
        };
        deckTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deckTable.getTableHeader().setReorderingAllowed(false);
        deckTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        applyDeckRenderers(deckTable);
        setDeckNarrowColumns(deckTable);

        deckTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = deckTable.getSelectedRow();
                if (row >= 0) loadCardImageAsync((String) deckModel.getValueAt(row, 1));
            }
        });

        JButton removeBtn    = new JButton("Remove 1 from Deck  ✕");
        JButton removeAllBtn = new JButton("Remove All From Deck");
        removeBtn.setEnabled(false);
        removeAllBtn.setEnabled(false);

        removeBtn.addActionListener(e -> removeSelectedCard());
        removeAllBtn.addActionListener(e -> removeAllOfSelectedCard());

        // Double-click also removes one copy
        deckTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) removeSelectedCard();
            }
        });

        // Enable/disable remove buttons based on selection and Qty
        deckTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = deckTable.getSelectedRow();
                if (row >= 0) {
                    Integer qty = (Integer) deckModel.getValueAt(row, 0);
                    removeBtn.setEnabled(true);
                    removeAllBtn.setEnabled(qty != null && qty > 1);
                } else {
                    removeBtn.setEnabled(false);
                    removeAllBtn.setEnabled(false);
                }
            }
        });

        formatS  = makeFormatLabel("S",  "Standard Constructed");
        formatL3 = makeFormatLabel("L3", "L3 Constructed");
        formatL6 = makeFormatLabel("L6", "L6 Constructed");
        formatT  = makeFormatLabel("T",  "Title");

        JPanel removePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        removePanel.add(removeBtn);
        removePanel.add(removeAllBtn);

        JPanel formatPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        formatPanel.add(new JLabel("Formats:"));
        formatPanel.add(formatS);
        formatPanel.add(formatL3);
        formatPanel.add(formatL6);
        formatPanel.add(formatT);

        JPanel btnPanel = new JPanel(new BorderLayout());
        btnPanel.add(removePanel, BorderLayout.WEST);
        btnPanel.add(formatPanel, BorderLayout.EAST);

        JPanel btnLabelsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnLabelsPanel.add(forwardLabel);
        btnLabelsPanel.add(backupLabel);
        btnLabelsPanel.add(summonLabel);
        btnLabelsPanel.add(monsterLabel);
        btnLabelsPanel.add(countLabel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Deck Contents"));
        panel.add(btnPanel,                   BorderLayout.NORTH);
        panel.add(new JScrollPane(deckTable), BorderLayout.CENTER);
        panel.add(btnLabelsPanel,             BorderLayout.SOUTH);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Card browser loading
    // -------------------------------------------------------------------------

    private void loadBrowserCards() {
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                return db.getAllCards();
            }
            @Override
            protected void done() {
                try {
                    for (Object[] row : get()) browserModel.addRow(row);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(DeckManager.this,
                            "Error loading card browser:\n" + e.getMessage(),
                            "Database Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void applyFilter(String text, int columnDropdownIndex) {
        if (text == null || text.isBlank()) {
            browserSorter.setRowFilter(null);
        } else {
            String regex = "(?i)" + Pattern.quote(text.trim());
            if (columnDropdownIndex == 0) {
                browserSorter.setRowFilter(RowFilter.regexFilter(regex));
            } else {
                browserSorter.setRowFilter(RowFilter.regexFilter(regex, columnDropdownIndex - 1));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cell renderers (mirroring CardBrowser style)
    // -------------------------------------------------------------------------

    private void applyBrowserRenderers(JTable table) {
        // Grey out Job (7) and Category 2 (9) when empty
        DefaultTableCellRenderer greyIfEmpty = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel && (value == null || value.toString().isBlank()))
                    setBackground(Color.LIGHT_GRAY);
                else
                    setBackground(t.getBackground());
                return this;
            }
        };
        for (int col : new int[]{7, 9})
            table.getColumnModel().getColumn(col).setCellRenderer(greyIfEmpty);

        // Grey out Power (5) when 0 or null
        table.getColumnModel().getColumn(5).setCellRenderer(powerRenderer());
    }

    private void applyDeckRenderers(JTable table) {
        // Grey out Power (6) when 0 or null
        table.getColumnModel().getColumn(6).setCellRenderer(powerRenderer());
        // Grey out Job (7) and Category 2 (9) when empty
        DefaultTableCellRenderer greyIfEmpty = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel && (value == null || value.toString().isBlank()))
                    setBackground(Color.LIGHT_GRAY);
                else
                    setBackground(t.getBackground());
                return this;
            }
        };
        table.getColumnModel().getColumn(7).setCellRenderer(greyIfEmpty);
        table.getColumnModel().getColumn(9).setCellRenderer(greyIfEmpty);
    }

    private DefaultTableCellRenderer powerRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                boolean empty = value == null || Integer.valueOf(0).equals(value);
                super.getTableCellRendererComponent(t, empty ? null : value, sel, focus, row, col);
                if (!sel) setBackground(empty ? Color.LIGHT_GRAY : t.getBackground());
                return this;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Deck list operations
    // -------------------------------------------------------------------------

    private void loadDeckList() throws SQLException {
        int prevId = selectedDeckId;
        deckListModel.clear();
        for (DeckEntry entry : db.getDecks()) deckListModel.addElement(entry);
        // Restore selection if the deck still exists
        for (int i = 0; i < deckListModel.size(); i++) {
            if (deckListModel.get(i).id() == prevId) {
                deckList.setSelectedIndex(i);
                return;
            }
        }
    }

    private void loadDeckCards(int deckId) {
        deckModel.setRowCount(0);
        int forward = 0, backup = 0, summon = 0, monster = 0;
        try {
            List<Object[]> rows = db.getDeckCards(deckId);
            rows.sort((a, b) -> {
                boolean aLb = lbSerials.contains((String) a[1]);
                boolean bLb = lbSerials.contains((String) b[1]);
                if (aLb != bLb) return aLb ? 1 : -1;     // non-LB above LB
                return SERIAL_ORDER.compare(a[1], b[1]); // then by serial (numeric set)
            });
            int mainTotal = 0, lbTotal = 0;
            for (Object[] row : rows) {
                deckModel.addRow(row);
                int qty = (Integer) row[0];
                if (lbSerials.contains((String) row[1])) {
                    lbTotal += qty;
                    continue;
                }
                else
                    mainTotal += qty;

                switch((String) row[3]){
                    case "Forward":
                        forward += qty;
                        break;
                    case "Backup":
                        backup += qty;
                        break;
                    case "Summon":
                        summon += qty;
                        break;
                    case "Monster":
                        monster += qty;
                        break;
                }
            }
            updateBreakdownLabels(forward, backup, summon, monster);
            updateCountLabel(mainTotal, lbTotal);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading deck:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
        refreshAddMaxBtn();
        refreshFormatLegality();
    }

    private void updateBreakdownLabels(int forward, int backup, int summon, int monster) {
        forwardLabel.setText(forward + " " + forwardLabel.getName());
        backupLabel.setText(backup + " " + backupLabel.getName());
        summonLabel.setText(summon + " " + summonLabel.getName());
        monsterLabel.setText(monster + " " + monsterLabel.getName());
    }

    private void updateCountLabel(int mainTotal, int lbTotal) {
        countLabel.setText(formatCountLabel(mainTotal, lbTotal));
    }

    private static String formatCountLabel(int mainTotal, int lbTotal) {
        return mainTotal + " / " + MAX_DECK_SIZE + " cards"
                + "  (+" + lbTotal + "/" + MAX_LB_DECK_SIZE + " LB)";
    }

    private void onNewDeck() {
        String name = JOptionPane.showInputDialog(this, "Enter deck name:", "New Deck", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        try {
            int id = db.createDeck(name.trim());
            loadDeckList();
            selectDeckById(id);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error creating deck:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onRenameDeck() {
        DeckEntry entry = deckList.getSelectedValue();
        if (entry == null) { JOptionPane.showMessageDialog(this, "Select a deck to rename."); return; }
        String name = (String) JOptionPane.showInputDialog(this, "Enter new name:", "Rename Deck",
                JOptionPane.PLAIN_MESSAGE, null, null, entry.name());
        if (name == null || name.isBlank()) return;
        try {
            db.renameDeck(entry.id(), name.trim());
            loadDeckList();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error renaming deck:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onCopyDeck() {
        DeckEntry entry = deckList.getSelectedValue();
        if (entry == null) { JOptionPane.showMessageDialog(this, "Select a deck to copy."); return; }
        String name = (String) JOptionPane.showInputDialog(this, "Enter name for copy:", "Copy Deck",
                JOptionPane.PLAIN_MESSAGE, null, null, entry.name() + " (Copy)");
        if (name == null || name.isBlank()) return;
        try {
            int newId = db.copyDeck(entry.id(), name.trim());
            loadDeckList();
            selectDeckById(newId);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error copying deck:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onDeleteDeck() {
        DeckEntry entry = deckList.getSelectedValue();
        if (entry == null) { JOptionPane.showMessageDialog(this, "Select a deck to delete."); return; }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete \"" + entry.name() + "\"? This cannot be undone.",
                "Delete Deck", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            db.deleteDeck(entry.id());
            selectedDeckId = -1;
            deckModel.setRowCount(0);
            countLabel.setText(formatCountLabel(0, 0));
            refreshFormatLegality();
            loadDeckList();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error deleting deck:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void selectDeckById(int id) {
        for (int i = 0; i < deckListModel.size(); i++) {
            if (deckListModel.get(i).id() == id) {
                deckList.setSelectedIndex(i);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Add / remove cards in the selected deck
    // -------------------------------------------------------------------------

    private void addSelectedCard() {
        if (selectedDeckId < 0) {
            JOptionPane.showMessageDialog(this, "Select or create a deck first.");
            return;
        }
        int viewRow = cardTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = cardTable.convertRowIndexToModel(viewRow);
        String serial = (String) browserModel.getValueAt(modelRow, 0);

        boolean isLb  = lbSerials.contains(serial);
        int current   = getCardCountInDeck(serial);

        if (getCombinedCardCountInDeck(serial) >= MAX_COPIES) return;
        if (isLb && getLbDeckTotal() >= MAX_LB_DECK_SIZE) {
            JOptionPane.showMessageDialog(this,
                    "LB deck is already at " + MAX_LB_DECK_SIZE + " cards.");
            return;
        }
        if (!isLb && getMainDeckTotal() >= MAX_DECK_SIZE) {
            JOptionPane.showMessageDialog(this,
                    "Main deck is already at " + MAX_DECK_SIZE + " cards.");
            return;
        }

        try {
            db.setCardCount(selectedDeckId, serial, current + 1);
            loadDeckCards(selectedDeckId);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error adding card:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addMaxOfSelectedCard() {
        if (selectedDeckId < 0) return;
        int viewRow = cardTable.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = cardTable.convertRowIndexToModel(viewRow);
        String serial = (String) browserModel.getValueAt(modelRow, 0);
        int toAdd = computeMaxAddable(serial);
        if (toAdd <= 0) return;
        int current = getCardCountInDeck(serial);
        try {
            db.setCardCount(selectedDeckId, serial, current + toAdd);
            loadDeckCards(selectedDeckId);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error adding cards:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Returns how many more copies of the browser-selected card can still be added. */
    private int computeMaxAddable(String serial) {
        if (selectedDeckId < 0) return 0;
        boolean isLb = lbSerials.contains(serial);
        int current  = getCombinedCardCountInDeck(serial);
        int byLimit  = MAX_COPIES - current;
        int byZone   = isLb ? MAX_LB_DECK_SIZE - getLbDeckTotal()
                            : MAX_DECK_SIZE    - getMainDeckTotal();
        return Math.max(0, Math.min(byLimit, byZone));
    }

    /** Updates the label and enabled state of the Add 1 and Add Max buttons based on the current browser selection. */
    private void refreshAddMaxBtn() {
        if (addMaxBtn == null) return;
        int viewRow = cardTable == null ? -1 : cardTable.getSelectedRow();
        if (viewRow < 0 || selectedDeckId < 0) {
            if (addBtn != null) addBtn.setEnabled(false);
            addMaxBtn.setText("Add Max to Deck");
            addMaxBtn.setEnabled(false);
            return;
        }
        int modelRow = cardTable.convertRowIndexToModel(viewRow);
        String serial = (String) browserModel.getValueAt(modelRow, 0);
        int max = computeMaxAddable(serial);
        if (addBtn != null) addBtn.setEnabled(max > 0);
        addMaxBtn.setText("Add " + max + " to Deck");
        addMaxBtn.setEnabled(max > 1);
    }

    private void removeSelectedCard() {
        if (selectedDeckId < 0) return;
        int viewRow = deckTable.getSelectedRow();
        if (viewRow < 0) return;
        String serial = (String) deckModel.getValueAt(viewRow, 1);
        int count     = (Integer) deckModel.getValueAt(viewRow, 0);

        try {
            db.setCardCount(selectedDeckId, serial, count - 1);
            loadDeckCards(selectedDeckId);
            restoreSelection(serial, viewRow);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error removing card:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Re-selects the row with the given serial after a reload, or the nearest row if it was removed. */
    private void restoreSelection(String serial, int fallbackViewRow) {
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            if (serial.equals(deckModel.getValueAt(i, 1))) {
                deckTable.setRowSelectionInterval(i, i);
                return;
            }
        }
        // Card was fully removed — select the row that shifted into its position, or the last row
        int target = Math.min(fallbackViewRow, deckModel.getRowCount() - 1);
        if (target >= 0) deckTable.setRowSelectionInterval(target, target);
    }

    private void removeAllOfSelectedCard() {
        if (selectedDeckId < 0) return;
        int viewRow = deckTable.getSelectedRow();
        if (viewRow < 0) return;
        String serial = (String) deckModel.getValueAt(viewRow, 1);

        try {
            db.setCardCount(selectedDeckId, serial, 0);
            loadDeckCards(selectedDeckId);
            restoreSelection(serial, viewRow);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error removing card:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // -------------------------------------------------------------------------
    // Deck count helpers (read from in-memory model to avoid extra DB queries)
    // -------------------------------------------------------------------------

    private int getCardCountInDeck(String serial) {
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            if (serial.equals(deckModel.getValueAt(i, 1)))
                return (Integer) deckModel.getValueAt(i, 0);
        }
        return 0;
    }

    /** Splits a serial on '/' and returns the trimmed components. */
    private static Set<String> splitSerial(String serial) {
        Set<String> parts = new java.util.HashSet<>();
        for (String part : serial.split("/"))
            parts.add(part.trim());
        return parts;
    }

    /**
     * Returns the total copies in the deck for a card identity, summing all deck entries
     * whose serial shares at least one component with the given serial after splitting on '/'.
     * This ensures dual-serial cards like "23-007C / 15-007C" are counted against "15-007C".
     */
    private int getCombinedCardCountInDeck(String serial) {
        Set<String> incoming = splitSerial(serial);
        int total = 0;
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            String deckSerial = (String) deckModel.getValueAt(i, 1);
            for (String part : splitSerial(deckSerial)) {
                if (incoming.contains(part)) {
                    total += (Integer) deckModel.getValueAt(i, 0);
                    break;
                }
            }
        }
        return total;
    }

    private int getMainDeckTotal() {
        int total = 0;
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            if (!lbSerials.contains((String) deckModel.getValueAt(i, 1)))
                total += (Integer) deckModel.getValueAt(i, 0);
        }
        return total;
    }

    private int getLbDeckTotal() {
        int total = 0;
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            if (lbSerials.contains((String) deckModel.getValueAt(i, 1)))
                total += (Integer) deckModel.getValueAt(i, 0);
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Deck Format Legality
    // -------------------------------------------------------------------------

    private void applyFormatState(JLabel lbl, boolean legal, Color activeColor) {
        lbl.setForeground(legal ? activeColor : FORMAT_INACTIVE);
        lbl.setFont(lbl.getFont().deriveFont(legal ? Font.BOLD : Font.PLAIN));
    }

    private JLabel makeFormatLabel(String text, String tooltip) {
        JLabel lbl = new JLabel(text);
        lbl.setToolTipText(tooltip);
        lbl.setForeground(FORMAT_INACTIVE);
        return lbl;
    }

    private void refreshFormatLegality() {
        if (formatS == null) return;

        int mainTotal = getMainDeckTotal();

        // Standard: exactly 50 main deck cards
        boolean legalS = (mainTotal == MAX_DECK_SIZE);

        // Find the highest numeric set prefix across the entire card pool
        int maxPrefix = computeMaxGlobalSetPrefix();

        // L3 / L6: all non-LB deck cards must be from the latest N sets or PR-
        boolean legalL3 = legalS && isLimitedSetLegal(3, maxPrefix);
        boolean legalL6 = legalS && isLimitedSetLegal(6, maxPrefix);

        // Title: no LB cards, 50 main cards, 30+ from one non-excluded category
        boolean legalT = checkTitleLegal();

        applyFormatState(formatS,   legalS,  FORMAT_S_COLOR);
        applyFormatState(formatL3,  legalL3, FORMAT_L3_COLOR);
        applyFormatState(formatL6,  legalL6, FORMAT_L6_COLOR);
        applyFormatState(formatT,   legalT,  FORMAT_T_COLOR);
    }

    /** Extracts the numeric set prefix from a serial (e.g. "28-001C" → 28). Returns 0 for non-numeric prefixes (PR-, B-, etc.). */
    private static int getSetPrefix(String serial) {
        if (serial == null) return 0;
        int dash = serial.indexOf('-');
        if (dash <= 0) return 0;
        try {
            return Integer.parseInt(serial.substring(0, dash));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Returns the highest numeric set prefix found in the card browser model (the global card pool). */
    private int computeMaxGlobalSetPrefix() {
        int max = 0;
        for (int i = 0; i < browserModel.getRowCount(); i++) {
            int p = getSetPrefix((String) browserModel.getValueAt(i, 0));
            if (p > max) max = p;
        }
        return max;
    }

    /**
     * Returns true if every card in the current deck belongs to the latest {@code setCount} sets or is a PR- promo.
     * LB cards are included in the check — a Title-less LB card from an old set would make L3/L6 illegal.
     */
    private boolean isLimitedSetLegal(int setCount, int maxPrefix) {
        if (maxPrefix == 0) return false;
        int minPrefix = maxPrefix - setCount + 1;
        for (int i = 0; i < deckModel.getRowCount(); i++) {
            String serial = (String) deckModel.getValueAt(i, 1);
            if (serial.startsWith("PR-")) continue;
            int prefix = getSetPrefix(serial);
            if (prefix == 0 || prefix < minPrefix) return false;
        }
        return true;
    }

    /** Returns true if the deck satisfies Title format legality. */
    private boolean checkTitleLegal() {
        // No LB cards allowed in Title
        if (getLbDeckTotal() > 0) return false;
        // Must have a full 50-card main deck
        if (getMainDeckTotal() != MAX_DECK_SIZE) return false;

        try {
            Map<String, Integer> catCounts = new HashMap<>();
            for (Object[] row : db.getDeckCardsWithCategories(selectedDeckId)) {
                String serial = (String) row[0];
                if (lbSerials.contains(serial)) continue;
                String cat1 = (String) row[1];
                String cat2 = (String) row[2];
                int qty = (Integer) row[3];
                // Use a set so a card with cat1==cat2 doesn't double-count
                Set<String> cats = new HashSet<>();
                if (cat1 != null && !cat1.isBlank()) cats.add(cat1);
                if (cat2 != null && !cat2.isBlank()) cats.add(cat2);
                for (String cat : cats) catCounts.merge(cat, qty, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : catCounts.entrySet()) {
                if (!TITLE_EXCLUDED_CATEGORIES.contains(entry.getKey()) && entry.getValue() >= 30)
                    return true;
            }
        } catch (SQLException ignored) {}
        return false;
    }

    // -------------------------------------------------------------------------
    // Import / Export
    // -------------------------------------------------------------------------

    private void onExportDeck() {
        DeckEntry entry = deckList.getSelectedValue();
        if (entry == null) { JOptionPane.showMessageDialog(this, "Select a deck to export."); return; }

        try {
            JSONArray cards = new JSONArray();
            for (Object[] row : db.getDeckCards(entry.id())) {
                cards.put(new JSONObject()
                        .put("serial", row[1])
                        .put("qty",    row[0]));
            }
            String json = new JSONObject()
                    .put("name",  entry.name())
                    .put("cards", cards)
                    .toString(2);

            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(json), null);

            JOptionPane.showMessageDialog(this,
                    "Deck JSON copied to clipboard.",
                    "Export Successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (SQLException | JSONException e) {
            JOptionPane.showMessageDialog(this, "Error exporting deck:\n" + e.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onImportDeck() {
        JTextArea textArea = new JTextArea(16, 50);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(textArea);

        int result = JOptionPane.showConfirmDialog(this, scroll,
                "Paste Deck JSON", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String text = textArea.getText().trim();
        if (text.isBlank()) return;

        try {
            JSONObject json  = new JSONObject(text);
            String deckName  = json.getString("name");
            JSONArray cards  = json.getJSONArray("cards");

            int newId = db.createDeck(deckName);
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.getJSONObject(i);
                String serial   = card.getString("serial");
                int qty         = card.getInt("qty");
                if (qty > 0) db.setCardCount(newId, serial, Math.min(qty, MAX_COPIES));
            }

            loadDeckList();
            selectDeckById(newId);
        } catch (JSONException e) {
            JOptionPane.showMessageDialog(this, "Invalid JSON format:\n" + e.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error importing deck:\n" + e.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onImportFFDecks() {
        JTextArea textArea = new JTextArea(16, 50);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        int result = JOptionPane.showConfirmDialog(this, new JScrollPane(textArea),
                "Paste FFDecks Text", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String text = textArea.getText().trim();
        if (text.isBlank()) return;

        String deckName = "Imported Deck";
        Map<String, Integer> cardMap = new LinkedHashMap<>();

        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Deck Name:")) {
                deckName = trimmed.substring("Deck Name:".length()).trim();
                continue;
            }
            Matcher m = FFDECKS_CARD_LINE.matcher(trimmed);
            if (m.matches()) {
                int qty    = Integer.parseInt(m.group(1));
                String serial = m.group(2);
                cardMap.merge(serial, qty, Integer::sum);
            }
        }

        if (cardMap.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No cards found in FFDecks format.",
                    "Import Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            List<String> missing = new ArrayList<>();
            Map<String, String> resolved = new LinkedHashMap<>();
            for (String serial : cardMap.keySet()) {
                String canonical = db.resolveSerial(serial);
                if (canonical == null) missing.add(serial);
                else resolved.put(serial, canonical);
            }

            int newId = db.createDeck(deckName);
            for (Map.Entry<String, Integer> e : cardMap.entrySet()) {
                String canonical = resolved.get(e.getKey());
                if (canonical != null) {
                    int qty = e.getValue();
                    if (qty > 0) db.setCardCount(newId, canonical, Math.min(qty, MAX_COPIES));
                }
            }

            loadDeckList();
            selectDeckById(newId);

            if (!missing.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Card serials not found: " + String.join(", ", missing),
                        "Import Warning", JOptionPane.WARNING_MESSAGE);
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, "Error importing deck:\n" + ex.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onExportFFDecks() {
        DeckEntry entry = deckList.getSelectedValue();
        if (entry == null) { JOptionPane.showMessageDialog(this, "Select a deck to export."); return; }

        try {
            List<Object[]> cards = db.getDeckCards(entry.id());
            Set<String> lbSerials = db.getLbSerials();

            String[] typeOrder = {"Forward", "Summon", "Backup", "Monster"};
            Map<String, List<Object[]>> lbByType   = new LinkedHashMap<>();
            Map<String, List<Object[]>> mainByType = new LinkedHashMap<>();
            for (String t : typeOrder) {
                lbByType.put(t, new ArrayList<>());
                mainByType.put(t, new ArrayList<>());
            }

            // getDeckCards rows: [count, serial, name_en, type_en, ...]
            for (Object[] row : cards) {
                String serial = (String) row[1];
                String type   = normalizeFFType((String) row[3]);
                (lbSerials.contains(serial) ? lbByType : mainByType).get(type).add(row);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--Generated By Shufflingway--\n");
            sb.append("Deck Name: ").append(entry.name()).append("\n\n\n");
            appendFFSection(sb, "limit break", lbByType);
            sb.append("\n");
            appendFFSection(sb, "main", mainByType);

            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sb.toString()), null);
            JOptionPane.showMessageDialog(this, "FFDecks text copied to clipboard.",
                    "Export Successful", JOptionPane.INFORMATION_MESSAGE);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error exporting deck:\n" + e.getMessage(),
                    "Export Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String normalizeFFType(String typeEn) {
        if (typeEn == null) return "Forward";
        return switch (typeEn) {
            case "Summon"  -> "Summon";
            case "Backup"  -> "Backup";
            case "Monster" -> "Monster";
            default        -> "Forward";
        };
    }

    private static void appendFFSection(StringBuilder sb, String sectionName,
            Map<String, List<Object[]>> byType) {
        int total = 0;
        for (List<Object[]> rows : byType.values())
            for (Object[] row : rows) total += (int) row[0];
        sb.append(sectionName).append("(").append(total).append("):\n\n");
        for (Map.Entry<String, List<Object[]>> e : byType.entrySet()) {
            int count = 0;
            for (Object[] row : e.getValue()) count += (int) row[0];
            sb.append(e.getKey()).append("(").append(count).append("):\n");
            for (Object[] row : e.getValue())
                sb.append(row[0]).append(" ").append(row[2]).append(" (").append(row[1]).append(")\n");
        }
    }

    // -------------------------------------------------------------------------
    // Card image preview
    // -------------------------------------------------------------------------

    private void loadCardImageAsync(String serial) {
        cardImageLabel.setIcon(null);
        cardImageLabel.setText("Loading…");

        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                try (java.sql.Connection conn = DriverManager.getConnection(scraper.AppPaths.dbUrl());
                     PreparedStatement ps = conn.prepareStatement(
                             "SELECT image_url FROM cards WHERE serial = ?")) {
                    ps.setString(1, serial);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String url = rs.getString("image_url");
                            if (url != null && !url.isBlank()) {
                                Image img = ImageCache.load(url);
                                if (img != null)
                                    return new ImageIcon(img.getScaledInstance(PREVIEW_W, PREVIEW_H, Image.SCALE_SMOOTH));
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
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Column sizing
    // -------------------------------------------------------------------------

    /** Constrains shared columns — applied to both the browser and deck tables. */
    private void setNarrowColumns(JTable table) {
        table.getColumnModel().getColumn(1).setPreferredWidth(86);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setMaxWidth(80);
        int[][] specs = {
            {0, 70,  90},  // Serial
            {3, 75, 100},  // Element
            {4, 45,  60},  // Cost
            {5, 55,  70},  // Power
        };
        for (int[] s : specs) {
            var col = table.getColumnModel().getColumn(s[0]);
            col.setPreferredWidth(s[1]);
            col.setMaxWidth(s[2]);
        }
        table.getColumnModel().getColumn(6).setPreferredWidth(70);  // Rarity (browser) / Job (deck)
    }

    /** Sizes all deck table columns independently (column order differs from the browser). */
    private void setDeckNarrowColumns(JTable table) {
        int[][] specs = {
            {0,  28,  38},  // Qty
            {1,  70,  90},  // Serial
            {3,  80,  80},  // Type
            {4,  75, 100},  // Element
            {5,  45,  60},  // Cost
            {6,  55,  70},  // Power
        };
        for (int[] s : specs) {
            var col = table.getColumnModel().getColumn(s[0]);
            col.setPreferredWidth(s[1]);
            col.setMaxWidth(s[2]);
        }
        table.getColumnModel().getColumn(2).setPreferredWidth(86);  // Name
        table.getColumnModel().getColumn(7).setPreferredWidth(70);  // Job
    }
}
