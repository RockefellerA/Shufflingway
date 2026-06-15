package shufflingway;

import scraper.CardDatabase;
import java.awt.*;
import java.awt.event.*;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;

/**
 * Static factory methods for the "Name an Element", "Name a Job", and "Name an Element and Job" dialogs.
 * All dialogs are modal, have no cancel path, and require a confirmed selection to close.
 */
class NameSelectionDialogs {

    /**
     * Shows the element-selection dialog (interactive) or picks randomly for the AI.
     *
     * @param frame       parent frame
     * @param prompt      label text shown above the element list
     * @param interactive true = show dialog for a human player
     * @param log         receives log messages
     */
    static String selectElement(JFrame frame, String prompt, boolean interactive, Consumer<String> log) {
        return selectElement(frame, prompt, Collections.emptySet(), interactive, log);
    }

    static String selectElement(JFrame frame, String prompt, Set<String> excluded,
                                boolean interactive, Consumer<String> log) {
        if (!interactive) {
            List<String> available = new ArrayList<>();
            for (String e : ActionResolver.ELEMENT_NAMES)
                if (excluded.stream().noneMatch(e::equalsIgnoreCase)) available.add(e);
            if (available.isEmpty()) available = List.of(ActionResolver.ELEMENT_NAMES);
            String picked = available.get((int) (Math.random() * available.size()));
            log.accept("[AI] selected Element: " + picked);
            return picked;
        }
        return showElementDialog(frame, prompt, excluded);
    }

    /**
     * Shows the job-selection dialog (interactive) or picks from the AI player's field jobs.
     *
     * @param frame            parent frame
     * @param fieldJobCandidates jobs present on the acting player's field; used as the AI candidate pool
     * @param interactive      true = show dialog for a human player
     * @param log              receives log messages
     */
    static String selectJob(JFrame frame, List<String> fieldJobCandidates,
                            boolean interactive, Consumer<String> log) {
        List<String> allJobs = loadJobs(log);
        if (allJobs.isEmpty()) return null;
        if (!interactive) {
            List<String> candidates = fieldJobCandidates.isEmpty() ? allJobs : fieldJobCandidates;
            String picked = candidates.get((int) (Math.random() * candidates.size()));
            log.accept("[AI] selected Job: " + picked);
            return picked;
        }
        return showJobDialog(frame, allJobs);
    }

    /**
     * Shows the combined element + job dialog (interactive) or picks randomly for the AI.
     *
     * @param frame       parent frame
     * @param prompt      label text shown above the element picker
     * @param excluded    element names to hide from the picker (e.g. {"Light", "Dark"})
     * @param interactive true = show dialog for a human player
     * @param log         receives log messages
     */
    static String[] selectElementAndJob(JFrame frame, String prompt, Set<String> excluded,
                                        boolean interactive, Consumer<String> log) {
        if (!interactive) {
            List<String> available = new ArrayList<>();
            for (String e : ActionResolver.ELEMENT_NAMES)
                if (excluded.stream().noneMatch(e::equalsIgnoreCase)) available.add(e);
            if (available.isEmpty()) available = List.of(ActionResolver.ELEMENT_NAMES);
            String elem = available.get((int) (Math.random() * available.size()));
            List<String> jobs = loadJobs(log);
            String job = jobs.isEmpty() ? "Warrior" : jobs.get((int) (Math.random() * jobs.size()));
            log.accept("[AI] named Element: " + elem + ", Job: " + job);
            return new String[]{elem, job};
        }
        List<String> jobs = loadJobs(log);
        if (jobs.isEmpty()) return null;
        return showElementAndJobDialog(frame, prompt, jobs, excluded);
    }

    /**
     * Like {@link #selectElementAndJob(JFrame, String, Set, boolean, Consumer)} with no element exclusions.
     */
    static String[] selectElementAndJob(JFrame frame, String prompt,
                                        boolean interactive, Consumer<String> log) {
        return selectElementAndJob(frame, prompt, Collections.emptySet(), interactive, log);
    }

    /**
     * Shows the job-or-element toggle dialog (interactive) or picks randomly for the AI.
     * Returns {@code {"job", value}} or {@code {"element", value}}.
     */
    static String[] selectJobOrElement(JFrame frame, String prompt, boolean interactive, Consumer<String> log) {
        if (!interactive) {
            if (Math.random() < 0.5) {
                String elem = ActionResolver.ELEMENT_NAMES[(int) (Math.random() * ActionResolver.ELEMENT_NAMES.length)];
                log.accept("[AI] named Element: " + elem);
                return new String[]{"element", elem};
            } else {
                List<String> jobs = loadJobs(log);
                String job = jobs.isEmpty() ? "Warrior" : jobs.get((int) (Math.random() * jobs.size()));
                log.accept("[AI] named Job: " + job);
                return new String[]{"job", job};
            }
        }
        List<String> jobs = loadJobs(log);
        if (jobs.isEmpty()) return null;
        return showJobOrElementDialog(frame, prompt, jobs);
    }

    /**
     * Shows the job-or-category toggle dialog (interactive) or picks randomly for the AI.
     * Returns {@code {"job", value}} or {@code {"category", value}}.
     */
    static String[] selectJobOrCategory(JFrame frame, String prompt, boolean interactive, Consumer<String> log) {
        if (!interactive) {
            List<String> jobs = loadJobs(log);
            List<String> cats = loadCategories(log);
            if (Math.random() < 0.5 && !cats.isEmpty()) {
                String cat = cats.get((int) (Math.random() * cats.size()));
                log.accept("[AI] named Category: " + cat);
                return new String[]{"category", cat};
            } else {
                String job = jobs.isEmpty() ? "Warrior" : jobs.get((int) (Math.random() * jobs.size()));
                log.accept("[AI] named Job: " + job);
                return new String[]{"job", job};
            }
        }
        List<String> jobs = loadJobs(log);
        List<String> cats = loadCategories(log);
        if (jobs.isEmpty() && cats.isEmpty()) return null;
        return showJobOrCategoryDialog(frame, prompt, jobs, cats);
    }

    /**
     * Collects the distinct job names from a set of field cards, splitting multi-job strings
     * (e.g. "Warrior/Rebel") into their components.
     */
    static List<String> collectFieldJobs(List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
        TreeSet<String> out = new TreeSet<>();
        for (CardData c : fwds) splitJobs(c.job(), out);
        for (CardData c : bkps) if (c != null) splitJobs(c.job(), out);
        for (CardData c : mons) splitJobs(c.job(), out);
        return new ArrayList<>(out);
    }

    // -------------------------------------------------------------------------

    private static List<String> loadJobs(Consumer<String> log) {
        try {
            return CardDatabase.loadJobs();
        } catch (SQLException e) {
            log.accept("[Job select] DB error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<String> loadCategories(Consumer<String> log) {
        try {
            return CardDatabase.loadCategories();
        } catch (SQLException e) {
            log.accept("[Category select] DB error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void splitJobs(String job, TreeSet<String> out) {
        if (job == null || job.isBlank()) return;
        for (String part : job.split("/")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    private static String showElementDialog(JFrame frame, String prompt) {
        return showElementDialog(frame, prompt, Collections.emptySet());
    }

    private static String showElementDialog(JFrame frame, String prompt, Set<String> excluded) {
        String[] result = {null};
        JDialog dialog = new JDialog(frame, "Name an Element", true);
        dialog.setResizable(false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String e : ActionResolver.ELEMENT_NAMES)
            if (excluded.stream().noneMatch(e::equalsIgnoreCase)) listModel.addElement(e);
        JList<String> elemList = new JList<>(listModel);
        elemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        elemList.setSelectedIndex(0);

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            String sel = elemList.getSelectedValue();
            if (sel != null) { result[0] = sel; dialog.dispose(); }
        });
        elemList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && elemList.getSelectedValue() != null) okButton.doClick();
            }
        });

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        String labelText = prompt.contains(" (")
                ? "<html>" + prompt.replaceFirst(" \\(", "<br>(") + "</html>"
                : prompt;
        top.add(new JLabel(labelText), BorderLayout.NORTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okButton);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(elemList), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(220, 290);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return result[0];
    }

    private static String showJobDialog(JFrame frame, List<String> jobs) {
        String[] result = {null};
        JDialog dialog = new JDialog(frame, "Name a Job", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JTextField searchField = new JTextField();

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String j : jobs) listModel.addElement(j);
        JList<String> jobList = new JList<>(listModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!listModel.isEmpty()) jobList.setSelectedIndex(0);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = searchField.getText().toLowerCase();
                listModel.clear();
                for (String j : jobs)
                    if (j.toLowerCase().contains(text)) listModel.addElement(j);
                if (!listModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            String sel = jobList.getSelectedValue();
            if (sel != null) { result[0] = sel; dialog.dispose(); }
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobList.getSelectedValue() != null) okButton.doClick();
            }
        });

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        top.add(new JLabel("Name a Job:"), BorderLayout.NORTH);
        top.add(searchField, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okButton);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(jobList), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(280, 420);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0];
    }

    private static String[] showElementAndJobDialog(JFrame frame, String prompt, List<String> jobs,
                                                    Set<String> excluded) {
        List<String> available = new ArrayList<>();
        for (String e : ActionResolver.ELEMENT_NAMES)
            if (excluded.stream().noneMatch(e::equalsIgnoreCase)) available.add(e);
        String[] elemItems = new String[available.size() + 1];
        elemItems[0] = "— Element —";
        for (int i = 0; i < available.size(); i++) elemItems[i + 1] = available.get(i);

        JComboBox<String> elemCombo = new JComboBox<>(elemItems);

        JTextField searchField = new JTextField();

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String j : jobs) listModel.addElement(j);
        JList<String> jobList = new JList<>(listModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!listModel.isEmpty()) jobList.setSelectedIndex(0);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = searchField.getText().toLowerCase();
                listModel.clear();
                for (String j : jobs)
                    if (j.toLowerCase().contains(text)) listModel.addElement(j);
                if (!listModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JButton okBtn = new JButton("OK");
        okBtn.setEnabled(false);
        elemCombo.addItemListener(e -> okBtn.setEnabled(elemCombo.getSelectedIndex() > 0));

        String[] result = {null, null};
        JDialog dialog = new JDialog(frame, "Name Element and Job", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        okBtn.addActionListener(e -> {
            String job = jobList.getSelectedValue();
            if (job == null) return;
            result[0] = (String) elemCombo.getSelectedItem();
            result[1] = job;
            dialog.dispose();
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobList.getSelectedValue() != null) okBtn.doClick();
            }
        });

        JPanel elemRow = new JPanel(new BorderLayout(6, 0));
        elemRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        elemRow.add(new JLabel("Element:"), BorderLayout.WEST);
        elemRow.add(elemCombo, BorderLayout.CENTER);

        JPanel jobTop = new JPanel(new BorderLayout(0, 4));
        jobTop.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        jobTop.add(new JLabel("Job:"), BorderLayout.NORTH);
        jobTop.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(new JLabel(prompt, SwingConstants.CENTER), BorderLayout.NORTH);
        top.add(elemRow, BorderLayout.CENTER);
        top.add(jobTop, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okBtn);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(jobList), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(300, 480);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0] != null ? result : null;
    }

    private static String[] showJobOrElementDialog(JFrame frame, String prompt, List<String> jobs) {
        String[] result = {null, null}; // [0] = "job"/"element", [1] = value
        JDialog dialog = new JDialog(frame, "Name a Job or Element", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        // --- Job panel (left) ---
        JTextField searchField = new JTextField();
        DefaultListModel<String> jobModel = new DefaultListModel<>();
        for (String j : jobs) jobModel.addElement(j);
        JList<String> jobList = new JList<>(jobModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!jobModel.isEmpty()) jobList.setSelectedIndex(0);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = searchField.getText().toLowerCase();
                jobModel.clear();
                for (String j : jobs)
                    if (j.toLowerCase().contains(text)) jobModel.addElement(j);
                if (!jobModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JPanel jobSearchRow = new JPanel(new BorderLayout(0, 2));
        jobSearchRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        jobSearchRow.add(new JLabel("Search:"), BorderLayout.NORTH);
        jobSearchRow.add(searchField, BorderLayout.CENTER);

        JPanel jobPanel = new JPanel(new BorderLayout(0, 4));
        jobPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        jobPanel.add(jobSearchRow, BorderLayout.NORTH);
        jobPanel.add(new JScrollPane(jobList), BorderLayout.CENTER);

        // --- Element panel (right) ---
        DefaultListModel<String> elemModel = new DefaultListModel<>();
        for (String e : ActionResolver.ELEMENT_NAMES) elemModel.addElement(e);
        JList<String> elemList = new JList<>(elemModel);
        elemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        elemList.setSelectedIndex(0);

        JPanel elemPanel = new JPanel(new BorderLayout());
        elemPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
        elemPanel.add(new JScrollPane(elemList), BorderLayout.CENTER);

        // --- Toggle radio buttons ---
        JRadioButton jobRadio  = new JRadioButton("Job",     true);
        JRadioButton elemRadio = new JRadioButton("Element", false);
        ButtonGroup  group     = new ButtonGroup();
        group.add(jobRadio);
        group.add(elemRadio);

        setPanelEnabled(elemPanel, false);

        jobRadio.addActionListener(e  -> { setPanelEnabled(jobPanel,  true);  setPanelEnabled(elemPanel, false); SwingUtilities.invokeLater(searchField::requestFocusInWindow); });
        elemRadio.addActionListener(e -> { setPanelEnabled(jobPanel,  false); setPanelEnabled(elemPanel, true); });

        // --- OK button ---
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            if (jobRadio.isSelected()) {
                String sel = jobList.getSelectedValue();
                if (sel != null) { result[0] = "job"; result[1] = sel; dialog.dispose(); }
            } else {
                String sel = elemList.getSelectedValue();
                if (sel != null) { result[0] = "element"; result[1] = sel; dialog.dispose(); }
            }
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobRadio.isSelected() && jobList.getSelectedValue() != null)
                    okButton.doClick();
            }
        });
        elemList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && elemRadio.isSelected() && elemList.getSelectedValue() != null)
                    okButton.doClick();
            }
        });

        // --- Layout ---
        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 4));
        if (prompt != null && !prompt.isBlank()) {
            JLabel lbl = new JLabel(prompt, SwingConstants.CENTER);
            toggleRow.setLayout(new BorderLayout());
            JPanel radios = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
            radios.add(jobRadio);
            radios.add(elemRadio);
            toggleRow.add(lbl,    BorderLayout.NORTH);
            toggleRow.add(radios, BorderLayout.CENTER);
        } else {
            toggleRow.add(jobRadio);
            toggleRow.add(elemRadio);
        }
        toggleRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jobPanel, elemPanel);
        splitPane.setResizeWeight(0.6);
        splitPane.setDividerSize(4);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okButton);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(toggleRow, BorderLayout.NORTH);
        dialog.add(splitPane, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(520, 440);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0] != null ? result : null;
    }

    private static String[] showJobOrCategoryDialog(JFrame frame, String prompt,
                                                    List<String> jobs, List<String> categories) {
        String[] result = {null, null}; // [0] = "job"/"category", [1] = value
        JDialog dialog = new JDialog(frame, "Name a Job or Category", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        // --- Job panel (left) ---
        JTextField jobSearch = new JTextField();
        DefaultListModel<String> jobModel = new DefaultListModel<>();
        for (String j : jobs) jobModel.addElement(j);
        JList<String> jobList = new JList<>(jobModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!jobModel.isEmpty()) jobList.setSelectedIndex(0);

        jobSearch.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = jobSearch.getText().toLowerCase();
                jobModel.clear();
                for (String j : jobs) if (j.toLowerCase().contains(text)) jobModel.addElement(j);
                if (!jobModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JPanel jobSearchRow = new JPanel(new BorderLayout(0, 2));
        jobSearchRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        jobSearchRow.add(new JLabel("Search:"), BorderLayout.NORTH);
        jobSearchRow.add(jobSearch, BorderLayout.CENTER);

        JPanel jobPanel = new JPanel(new BorderLayout(0, 4));
        jobPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        jobPanel.add(jobSearchRow, BorderLayout.NORTH);
        jobPanel.add(new JScrollPane(jobList), BorderLayout.CENTER);

        // --- Category panel (right) ---
        JTextField catSearch = new JTextField();
        DefaultListModel<String> catModel = new DefaultListModel<>();
        for (String c : categories) catModel.addElement(c);
        JList<String> catList = new JList<>(catModel);
        catList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!catModel.isEmpty()) catList.setSelectedIndex(0);

        catSearch.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = catSearch.getText().toLowerCase();
                catModel.clear();
                for (String c : categories) if (c.toLowerCase().contains(text)) catModel.addElement(c);
                if (!catModel.isEmpty()) catList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JPanel catSearchRow = new JPanel(new BorderLayout(0, 2));
        catSearchRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        catSearchRow.add(new JLabel("Search:"), BorderLayout.NORTH);
        catSearchRow.add(catSearch, BorderLayout.CENTER);

        JPanel catPanel = new JPanel(new BorderLayout(0, 4));
        catPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
        catPanel.add(catSearchRow, BorderLayout.NORTH);
        catPanel.add(new JScrollPane(catList), BorderLayout.CENTER);

        // --- Toggle radio buttons ---
        JRadioButton jobRadio = new JRadioButton("Job",      true);
        JRadioButton catRadio = new JRadioButton("Category", false);
        ButtonGroup  group    = new ButtonGroup();
        group.add(jobRadio);
        group.add(catRadio);

        setPanelEnabled(catPanel, false);

        jobRadio.addActionListener(e -> { setPanelEnabled(jobPanel, true);  setPanelEnabled(catPanel, false); SwingUtilities.invokeLater(jobSearch::requestFocusInWindow); });
        catRadio.addActionListener(e -> { setPanelEnabled(jobPanel, false); setPanelEnabled(catPanel, true);  SwingUtilities.invokeLater(catSearch::requestFocusInWindow); });

        // --- OK button ---
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            if (jobRadio.isSelected()) {
                String sel = jobList.getSelectedValue();
                if (sel != null) { result[0] = "job"; result[1] = sel; dialog.dispose(); }
            } else {
                String sel = catList.getSelectedValue();
                if (sel != null) { result[0] = "category"; result[1] = sel; dialog.dispose(); }
            }
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobRadio.isSelected() && jobList.getSelectedValue() != null)
                    okButton.doClick();
            }
        });
        catList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && catRadio.isSelected() && catList.getSelectedValue() != null)
                    okButton.doClick();
            }
        });

        // --- Layout ---
        JPanel toggleRow = new JPanel(new BorderLayout());
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
        radios.add(jobRadio);
        radios.add(catRadio);
        toggleRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        if (prompt != null && !prompt.isBlank()) {
            toggleRow.add(new JLabel(prompt, SwingConstants.CENTER), BorderLayout.NORTH);
            toggleRow.add(radios, BorderLayout.CENTER);
        } else {
            toggleRow.add(radios, BorderLayout.CENTER);
        }

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jobPanel, catPanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerSize(4);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okButton);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(toggleRow, BorderLayout.NORTH);
        dialog.add(splitPane, BorderLayout.CENTER);
        dialog.add(bottom,    BorderLayout.SOUTH);
        dialog.setSize(520, 440);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(jobSearch::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0] != null ? result : null;
    }

    private static void setPanelEnabled(JComponent c, boolean enabled) {
        c.setEnabled(enabled);
        for (Component child : c.getComponents())
            if (child instanceof JComponent) setPanelEnabled((JComponent) child, enabled);
    }
}
