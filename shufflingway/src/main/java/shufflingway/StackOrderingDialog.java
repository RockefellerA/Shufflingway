package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;

import shufflingway.graphics.CardAnimation;

/**
 * Modal dialog that asks the player to order simultaneous auto-ability triggers
 * before they are pushed onto the stack.
 *
 * <p>Cards are shown left-to-right. The leftmost card is pushed first (bottom of
 * stack, resolves last); the rightmost is pushed last (top of stack, resolves
 * first). A synchronized list below shows the resolution order (top of stack
 * first) and updates live as cards are dragged.</p>
 */
final class StackOrderingDialog {

	/** Lightweight view-model: one ability + its source card + controller. */
	record Item(AutoAbility ability, CardData source, boolean controllerIsP1) {
		String displayEffect() {
			String txt = ability.effectText();
			return txt == null || txt.isBlank() ? "(no effect text)" : txt;
		}
	}

	private StackOrderingDialog() {}

	/**
	 * Shows the dialog modally and returns the player-chosen order.
	 * The returned list is in <em>resolution order</em>: index 0 = top of stack
	 * (resolves first), last index = bottom of stack (resolves last). Callers
	 * pushing onto the stack must iterate in reverse so the first-resolving
	 * ability ends up on top.
	 *
	 * @param owner   parent frame for modal docking
	 * @param header  header text (e.g. "Choose Your Trigger Order")
	 * @param items   abilities to order; must have size &gt;= 2
	 * @return the same items in the order the player chose (top of stack first)
	 */
	static List<Item> show(java.awt.Frame owner, String header, List<Item> items) {
		JDialog dialog = new JDialog(owner, "Stack Ordering", true);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		DefaultListModel<Item> cardModel = new DefaultListModel<>();
		for (Item it : items) cardModel.addElement(it);

		JList<Item> cardList = buildCardList(cardModel);
		int contentWidth = Math.min(900, (CardAnimation.CARD_W + 12) * Math.max(items.size(), 1) + 24);

		javax.swing.JTextPane effectArea = new javax.swing.JTextPane();
		effectArea.setEditable(false);
		effectArea.setFocusable(false);
		effectArea.setBackground(Color.WHITE);
		effectArea.setForeground(Color.BLACK);
		effectArea.setFont(effectArea.getFont().deriveFont((float) UiScale.scale(12)));
		effectArea.setMargin(new java.awt.Insets(6, 8, 6, 8));

		javax.swing.text.SimpleAttributeSet boldAttr  = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.SimpleAttributeSet plainAttr = new javax.swing.text.SimpleAttributeSet();
		javax.swing.text.StyleConstants.setBold(boldAttr, true);

		Runnable syncEffects = () -> {
			// Left = top of stack = resolves first. The text mirrors the card row
			// left-to-right with 1st-resolves at the top.
			javax.swing.text.StyledDocument doc = effectArea.getStyledDocument();
			try {
				doc.remove(0, doc.getLength());
				for (int i = 0; i < cardModel.size(); i++) {
					Item it = cardModel.get(i);
					String controller = it.controllerIsP1() ? "P1" : "P2";
					String prefix = ordinal(i + 1) + " (" + controller + ", " + it.source().name() + "): ";
					doc.insertString(doc.getLength(), prefix, boldAttr);
					doc.insertString(doc.getLength(), capitalizeFirst(it.displayEffect()), plainAttr);
					if (i < cardModel.size() - 1) doc.insertString(doc.getLength(), "\n\n", plainAttr);
				}
			} catch (javax.swing.text.BadLocationException ignored) {}
			effectArea.setCaretPosition(0);
		};
		syncEffects.run();
		cardModel.addListDataListener(new javax.swing.event.ListDataListener() {
			@Override public void intervalAdded(javax.swing.event.ListDataEvent e)   { syncEffects.run(); }
			@Override public void intervalRemoved(javax.swing.event.ListDataEvent e) { syncEffects.run(); }
			@Override public void contentsChanged(javax.swing.event.ListDataEvent e) { syncEffects.run(); }
		});

		JLabel headerLbl = new JLabel("<html><div style='text-align:center'>" + header
				+ "<br><span style='font-size:smaller'>Drag cards to reorder. Left = top of stack (resolves first).</span></div></html>",
				SwingConstants.CENTER);
		headerLbl.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JScrollPane cardScroll = new JScrollPane(cardList,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		cardScroll.setPreferredSize(new Dimension(
				Math.min(900, (CardAnimation.CARD_W + 12) * Math.max(items.size(), 1) + 24),
				CardAnimation.CARD_H + 40));

		JScrollPane effectScroll = new JScrollPane(effectArea,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		effectScroll.setPreferredSize(new Dimension(contentWidth, UiScale.scale(180)));

		JButton okBtn = new JButton("OK");
		okBtn.addActionListener(e -> dialog.dispose());
		JPanel btnRow = new JPanel(new BorderLayout());
		btnRow.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		btnRow.add(okBtn, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		content.add(headerLbl,    BorderLayout.NORTH);
		content.add(cardScroll,   BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout());
		south.add(effectScroll, BorderLayout.CENTER);
		south.add(btnRow,       BorderLayout.SOUTH);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);

		List<Item> ordered = new ArrayList<>(cardModel.size());
		for (int i = 0; i < cardModel.size(); i++) ordered.add(cardModel.get(i));
		return ordered;
	}

	// -------------------------------------------------------------------------

	private static JList<Item> buildCardList(DefaultListModel<Item> model) {
		JList<Item> list = new JList<>(model);
		list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
		list.setVisibleRowCount(1);
		list.setFixedCellWidth(CardAnimation.CARD_W + 12);
		list.setFixedCellHeight(CardAnimation.CARD_H + 32);
		list.setCellRenderer(new CardCellRenderer());
		list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		list.setDragEnabled(true);
		list.setDropMode(DropMode.INSERT);
		list.setTransferHandler(new ItemReorderHandler());
		list.setBackground(new Color(28, 24, 40));
		return list;
	}

	private static String capitalizeFirst(String s) {
		if (s == null || s.isEmpty()) return s;
		char first = s.charAt(0);
		char upper = Character.toUpperCase(first);
		return first == upper ? s : upper + s.substring(1);
	}

	private static String ordinal(int n) {
		String suffix;
		int mod100 = n % 100;
		if (mod100 >= 11 && mod100 <= 13) suffix = "th";
		else switch (n % 10) {
			case 1 -> suffix = "st";
			case 2 -> suffix = "nd";
			case 3 -> suffix = "rd";
			default -> suffix = "th";
		}
		return n + suffix;
	}

	// -------------------------------------------------------------------------
	// Card cell rendering — thumbnail + name. Image loaded lazily and cached.
	// -------------------------------------------------------------------------

	private static final java.util.Map<String, ImageIcon> ICON_CACHE = new java.util.HashMap<>();

	private static final class CardCellRenderer extends javax.swing.JPanel
			implements javax.swing.ListCellRenderer<Item> {

		private final JLabel imgLbl  = new JLabel("", SwingConstants.CENTER);
		private final JLabel nameLbl = new JLabel("", SwingConstants.CENTER);

		CardCellRenderer() {
			super(new BorderLayout(0, 2));
			imgLbl.setPreferredSize(new Dimension(CardAnimation.CARD_W, CardAnimation.CARD_H));
			nameLbl.setFont(FontLoader.loadPixelNESFont(9));
			nameLbl.setForeground(Color.WHITE);
			add(imgLbl,  BorderLayout.CENTER);
			add(nameLbl, BorderLayout.SOUTH);
			setOpaque(true);
		}

		@Override public Component getListCellRendererComponent(JList<? extends Item> list, Item value,
				int index, boolean isSelected, boolean cellHasFocus) {
			nameLbl.setText(value.source().name());
			setBackground(isSelected ? new Color(80, 60, 130) : new Color(28, 24, 40));
			setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createEmptyBorder(4, 4, 4, 4),
					BorderFactory.createLineBorder(
							isSelected ? new Color(200, 160, 255) : new Color(80, 60, 110),
							isSelected ? 2 : 1)));

			ImageIcon cached = ICON_CACHE.get(value.source().imageUrl());
			if (cached != null) {
				imgLbl.setIcon(cached);
				imgLbl.setText(null);
			} else {
				imgLbl.setIcon(null);
				imgLbl.setText("...");
				imgLbl.setForeground(Color.LIGHT_GRAY);
				loadAsync(value, list);
			}
			return this;
		}

		private void loadAsync(Item item, JList<? extends Item> list) {
			String url = item.source().imageUrl();
			if (url == null || url.isBlank()) return;
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(url);
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(
									CardAnimation.CARD_W, CardAnimation.CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) {
							ICON_CACHE.put(url, icon);
							list.repaint();
						}
					} catch (Exception ignored) {}
				}
			}.execute();
		}
	}

	// -------------------------------------------------------------------------
	// Drag-and-drop reorder TransferHandler
	// -------------------------------------------------------------------------

	private static final DataFlavor INDEX_FLAVOR =
			new DataFlavor(Integer.class, "Stack ordering index");

	private static final class ItemReorderHandler extends TransferHandler {

		@Override public int getSourceActions(javax.swing.JComponent c) { return MOVE; }

		@Override protected Transferable createTransferable(javax.swing.JComponent c) {
			JList<?> list = (JList<?>) c;
			int idx = list.getSelectedIndex();
			return new IndexTransferable(idx);
		}

		@Override public boolean canImport(TransferSupport support) {
			return support.isDataFlavorSupported(INDEX_FLAVOR)
					&& support.isDrop()
					&& support.getDropLocation() instanceof JList.DropLocation;
		}

		@Override public boolean importData(TransferSupport support) {
			if (!canImport(support)) return false;
			try {
				int from = (Integer) support.getTransferable().getTransferData(INDEX_FLAVOR);
				JList.DropLocation dl = (JList.DropLocation) support.getDropLocation();
				int to = dl.getIndex();
				@SuppressWarnings("unchecked")
				JList<Item> list = (JList<Item>) support.getComponent();
				DefaultListModel<Item> model = (DefaultListModel<Item>) list.getModel();
				if (from < 0 || from >= model.size() || to < 0 || to > model.size()) return false;
				Item moved = model.remove(from);
				int insertAt = to > from ? to - 1 : to;
				model.add(insertAt, moved);
				list.setSelectedIndex(insertAt);
				return true;
			} catch (UnsupportedFlavorException | IOException ex) {
				return false;
			}
		}
	}

	private static final class IndexTransferable implements Transferable {
		private final int index;
		IndexTransferable(int index) { this.index = index; }
		@Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { INDEX_FLAVOR }; }
		@Override public boolean isDataFlavorSupported(DataFlavor f) { return INDEX_FLAVOR.equals(f); }
		@Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
			return index;
		}
	}
}
