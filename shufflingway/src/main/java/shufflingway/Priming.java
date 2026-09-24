package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import static shufflingway.CardFilters.cardNamesOverlap;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * The Priming mechanic: choosing a version to prime into, paying the cost, and topping the
 * primed Forward with the card that comes out of the deck.
 *
 * <p>Split out of {@link MainWindow} as a vertical slice — the payment dialog, the version
 * chooser and the execution that follows them were 413 lines serving one rule and nothing else.
 * Priming <em>state</em> stays on the board where the rest of the rendering reads it
 * ({@code p1ForwardPrimedTop} and its P2 twin); only the behaviour moved.
 *
 * <p>Holds a {@link MainWindow} the same way {@link CostCalculator}, {@link DamageResolver} and
 * {@link AutoAbilityTriggers} do: the board and its refreshers stay there, and this reaches
 * them through {@code mw}.
 */
class Priming {

	private final MainWindow mw;

	Priming(MainWindow mw) { this.mw = mw; }

	/**
	 * If {@code card} is currently the primed top of a forward slot, returns the name of
	 * the primer (base) card beneath it; otherwise returns {@code null}.
	 */
	String getPrimerCardName(CardData card, boolean isP1) {
		List<CardData> primedTops = isP1 ? mw.p1ForwardPrimedTop : mw.p2ForwardPrimedTop;
		List<CardData> bases      = isP1 ? mw.p1ForwardCards      : mw.p2ForwardCards;
		for (int i = 0; i < primedTops.size(); i++)
			if (card.equals(primedTops.get(i))) return bases.get(i).name();
		return null;
	}

	/**
	 * Returns true if priming {@code targetName} onto {@code isP1}'s field would immediately
	 * violate the uniqueness rule — i.e. that player already controls a Forward, or a primed top
	 * card, of that name.
	 *
	 * <p>Only the priming player's own side is inspected. The uniqueness rule is per player, not
	 * per board: both players may control a copy of the same Character at once, and the rule
	 * process this mirrors ({@code MainWindow.sendToBreakZoneByUniquenessRule}) walks one side's
	 * zones only. Scanning both fields blocked a legal prime whenever the <em>opponent</em>
	 * happened to control the target.
	 *
	 * <p>Name overlap goes through {@link CardFilters#cardNamesOverlap} for the same reason: the
	 * rule process resolves "is also Card Name X" aliases, so a check that compared printed names
	 * alone would green-light a prime the rule then broke on arrival.
	 */
	boolean primingTargetOnField(String targetName, boolean isP1) {
		CardData target = deckTarget(targetName, isP1);
		// A multicard is exempt from the uniqueness rule, so it can never be blocked by it.
		if (target != null && target.multicard()) return false;

		List<CardData> bases = isP1 ? mw.p1ForwardCards      : mw.p2ForwardCards;
		List<CardData> tops  = isP1 ? mw.p1ForwardPrimedTop  : mw.p2ForwardPrimedTop;
		for (int i = 0; i < bases.size(); i++) {
			if (conflicts(target, targetName, bases.get(i))) return true;
			CardData top = tops.get(i);
			if (top != null && conflicts(target, targetName, top)) return true;
		}
		return false;
	}

	/**
	 * The card the prime would actually pull, so the uniqueness check can see its aliases and
	 * whether it is a multicard. Null when the target is not in the deck — in which case the
	 * prime will find nothing anyway and the printed name is all there is to compare.
	 */
	private CardData deckTarget(String targetName, boolean isP1) {
		List<CardData> matches = isP1
				? mw.gameState.findMatchingNamesInP1MainDeck(targetName)
				: mw.gameState.findMatchingNamesInP2MainDeck(targetName);
		return matches.isEmpty() ? null : matches.get(0);
	}

	private static boolean conflicts(CardData target, String targetName, CardData onField) {
		return target != null ? cardNamesOverlap(target, onField)
				: onField.name().equalsIgnoreCase(targetName);
	}

	/** @see CostCalculator#canAffordPrimingCost */
	boolean canAffordPrimingCost(CardData card) { return mw.costs.canAffordPrimingCost(card); }

	/**
	 * True when P1 currently holds a window in which they may prime. Priming is a Main Phase action:
	 * it may be taken only during its controller's own Main Phase 1 or Main Phase 2, and only while
	 * the Stack is empty.
	 *
	 * <p>All three conditions are load-bearing and the phase alone is not enough — the phase is a
	 * property of the game, not of a player, so P2's Main Phase reads as a Main Phase to P1 too.
	 * Checking it by itself let P1 prime during the opponent's turn at any priority window they were
	 * handed. This is the same shape as {@link MainWindow#castTimingWindowOpen} reduced to its
	 * non-Summon-speed arm, which is what a Main Phase action is.
	 */
	boolean primingTimingWindowOpen() {
		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		return (phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2)
				&& mw.gameState.getCurrentPlayer() == GameState.Player.P1
				&& mw.gameState.getStack().isEmpty();
	}

	/**
	 * The running-total line of the Priming payment dialog: what has been produced against what the
	 * cost asks, broken down into one segment per Element the cost names plus an {@code any} segment
	 * for its generic part — "Prime CP: 1 / 2  (Fire: 1/1, any: 0/1)".
	 *
	 * <p>{@code genericPaid} is clamped to {@code genericNeeded}: any amount of CP may be produced
	 * when paying a cost, and the excess is wasted rather than counting toward a requirement that is
	 * already met. The running total ahead of the parenthesis is left unclamped, so overpaying still
	 * reads as the "4 / 3" it is.
	 *
	 * <p>A cost that names nothing at all is a cost of nothing and never opens the dialog, so at
	 * least one segment is always present.
	 */
	static String primeCpLabel(int total, int totalCost, String[] elems,
			Map<String, Integer> cpByElem, Map<String, Integer> costByElem,
			int genericPaid, int genericNeeded) {
		List<String> segments = new ArrayList<>();
		for (String en : elems)
			segments.add(en + ": " + cpByElem.getOrDefault(en, 0) + "/" + costByElem.get(en));
		if (genericNeeded > 0)
			segments.add("any: " + Math.min(genericPaid, genericNeeded) + "/" + genericNeeded);
		return "Prime CP: " + total + " / " + totalCost + "  (" + String.join(", ", segments) + ")";
	}

	/**
	 * Payment dialog for the Priming ability cost. On confirm, searches the
	 * main deck for the target card and places it on top of the priming forward.
	 */
	void showPrimingPaymentDialog(CardData card, int slotIdx) {
		List<String> rawCost = mw.effectivePrimingCost(card, true);
		long genericNeeded = rawCost.stream().filter(String::isEmpty).count();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems   = costByElem.keySet().toArray(String[]::new);
		int totalCost    = rawCost.size();

		// If cost is empty, no dialog needed — go straight to execution
		if (totalCost == 0) {
			executePriming(card, slotIdx, new ArrayList<>(), new ArrayList<>());
			return;
		}

		JDialog dlg = new JDialog(mw.frame, "Prime: " + card.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<CardData> hand = mw.gameState.getP1Hand();

		Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
		for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < mw.p1BackupCards.length; i++) {
			if (mw.p1BackupCards[i] != null && mw.p1BackupStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(mw.p1BackupCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm (Prime)");
		confirmBtn.setFont(FontLoader.loadPixelFont(11));

		List<JLabel>   backupLbls  = new ArrayList<>();
		List<Integer>  backupSlots = new ArrayList<>();
		List<JLabel>   discardLbls = new ArrayList<>();
		List<Integer>  discardIdxs = new ArrayList<>();

		boolean[] canAddDiscard = {false};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			for (int slot : selectedBackups) {
				if (matchesAnyElement(mw.p1BackupCards[slot], elems))
					cpByElem.merge(contributingElement(mw.p1BackupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
				else extraCp++;
			}
			for (int idx : selectedDiscards) {
				if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
				else extraCp += 2;
			}
			int total      = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			// Any amount of CP may be produced when paying a cost; excess beyond the cost is wasted.
			boolean canAddBackup = true;
			canAddDiscard[0] = true;
			boolean satisfied = cpByElem.entrySet().stream()
					.allMatch(en -> en.getValue() >= costByElem.getOrDefault(en.getKey(), 0));
			confirmBtn.setEnabled(total >= totalCost && satisfied);

			cpLabel.setText(primeCpLabel(total, totalCost, elems, cpByElem, costByElem,
					extraCp, (int) genericNeeded));

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(sel ? MainWindow.createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddBackup ? Color.GRAY : new Color(80,80,80), 1));
				lbl.setBackground(sel || canAddBackup ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddBackup ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(sel ? MainWindow.createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80,80,80), 1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddDiscard[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
			hdr.setFont(FontLoader.loadPixelFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(Color.DARK_GRAY); lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelFont(10)); lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = mw.p1BackupUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						if (!selectedBackups.remove(Integer.valueOf(slot))) selectedBackups.add(slot);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(url); }
					@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
			}
			centerPanel.add(hdr); centerPanel.add(bp);
		}

		JLabel discardHdr = new JLabel("Hand — discard for 2 CP each:");
		discardHdr.setFont(FontLoader.loadPixelFont(9)); discardHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
		Set<String> primingLdGrants = mw.lightDarkDiscardGrants(true);
		for (int i = 0; i < hand.size(); i++) {
			final int hi = i; CardData hc = hand.get(i);
			boolean payable = CpPaymentUtils.canDiscardForCp(hc, primingLdGrants);
			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true); lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50,50,50));
			lbl.setForeground(Color.WHITE); lbl.setFont(FontLoader.loadPixelFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80,80,80), 1));
			lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			final String imgUrl = hc.imageUrl();
			if (payable) {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
				});
				discardLbls.add(lbl); discardIdxs.add(hi);
			} else {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
				});
			}
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(imgUrl);
					return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();
			dp.add(lbl);
		}
		centerPanel.add(discardHdr); centerPanel.add(dp);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelFont(11));
		cancelBtn.addActionListener(ev -> dlg.dispose());
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			executePriming(card, slotIdx, new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups));
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn); buttonPanel.add(cancelBtn);

		StringBuilder costDesc = new StringBuilder();
		boolean f = true;
		for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
			if (!f) costDesc.append(" + ");
			costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP"); f = false;
		}
		if (genericNeeded > 0) { if (!f) costDesc.append(" + "); costDesc.append((int) genericNeeded).append(" any CP"); }
		JLabel titleLabel = new JLabel(
				"Priming cost for: " + card.name() + "  (" + (costDesc.length() > 0 ? costDesc : "free") + ")",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH); topPanel.add(cpLabel, BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(centerPanel), BorderLayout.CENTER);
		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel, BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack(); dlg.setLocationRelativeTo(mw.frame); dlg.setVisible(true);
	}

	/**
	 * What a local Priming has to tell the opponent once it is settled: the payment, as indices
	 * into the hand and Backup row as they stood before it was taken, and the deck as it stood
	 * before the search, which the chosen card's position and the new order are read against.
	 */
	private record LocalPriming(List<Integer> discards, List<Integer> backups, List<CardData> deckBefore) {}

	/**
	 * Pays the Priming cost, searches the main deck for the target card, and if
	 * found places it as the top card of the primed forward.  The deck is shuffled
	 * after the search regardless of whether the card was found.
	 */
	void executePriming(CardData card, int slotIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices) {
		LocalPriming sent = new LocalPriming(List.copyOf(discardIndices), List.copyOf(backupDullIndices),
				new ArrayList<>(mw.gameState.getP1MainDeck()));
		payPrimingCost(true, card, discardIndices, backupDullIndices);

		// Search deck — find all versions of the target card.  Multiple copies of the same
		// printing are one choice, not several, so only distinct versions reach the dialog.
		String target = card.primingTarget();
		List<CardData> matches = MainWindow.distinctVersions(mw.gameState.findMatchingNamesInP1MainDeck(target));

		if (matches.size() <= 1) {
			finishLocalPriming(card, slotIdx, matches.isEmpty() ? null : matches.get(0), sent);
		} else {
			// Multiple printings found — let the player choose; the dialog finishes the Priming
			showPrimingVersionSelectDialog(matches, card, slotIdx, sent);
		}
	}

	/**
	 * Pays {@code card}'s Priming cost for {@code isP1}: dulls the Backups, discards the hand
	 * cards for CP, and spends and clears the bank. The same for both seats, so the opponent's
	 * client pays a replicated Priming exactly as the player who chose it did.
	 */
	private void payPrimingCost(boolean isP1, CardData card,
			List<Integer> discardIndices, List<Integer> backupDullIndices) {
		// Re-read rather than passed in from the dialog: the discount is a board condition, and the
		// board can have moved between the dialog opening and the payment being confirmed.
		List<String> rawCost = mw.effectivePrimingCost(card, isP1);
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);
		CardData[]     backupCards  = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
		CardState[]    backupStates = isP1 ? mw.p1BackupStates : mw.p2BackupStates;
		List<CardData> hand         = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();

		for (int bi : backupDullIndices) {
			backupStates[bi] = CardState.DULL;
			if (isP1) mw.animateDullBackup(bi, true); else mw.animateDullP2Backup(bi, true);
			String cpElem = matchesAnyElement(backupCards[bi], elems)
					? contributingElement(backupCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.addCp(isP1, cpElem, 1);
		}
		List<Integer> discardOrder = new ArrayList<>(discardIndices);
		discardOrder.sort(Collections.reverseOrder());
		for (int di : discardOrder) {
			CardData discarded = hand.get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.addCp(isP1, cpElem, 2);
			mw.playerBreakFromHand(isP1, di);
		}
		for (String e : elems) { mw.spendCp(isP1, e, mw.cpForElement(isP1, e)); mw.clearCp(isP1, e); }
	}

	/**
	 * Takes {@code chosen} out of the deck (when the search found one), shuffles, tells the
	 * opponent, and tops the primed Forward with it.
	 *
	 * <p>Sent before the card is placed, so the opponent holds the Priming before any trigger it
	 * sets off asks them anything. The shuffle draws from this deck's shared stream, so the
	 * opponent's replay comes out the same; the new order goes along so they can check that.
	 */
	private void finishLocalPriming(CardData card, int slotIdx, CardData chosen, LocalPriming sent) {
		if (chosen != null) mw.gameState.removeFromP1MainDeck(chosen);
		mw.shuffleP1MainDeck();
		mw.sendToOpponent(RemoteOpponent.primeAction(card, slotIdx, sent.discards(), sent.backups(),
				sent.deckBefore(), new ArrayList<>(mw.gameState.getP1MainDeck())));
		if (chosen != null) applyPrimedCard(chosen, card, slotIdx, true);
		else mw.logEntry("Priming: \"" + card.primingTarget() + "\" not found in deck — no card placed");
		mw.refreshP1HandLabel();
		mw.refreshP1BreakLabel();
	}

	/**
	 * The opponent's Priming, replayed from their PRIME action: the same payment, the same card
	 * out of the deck, the same shuffle, and {@code chosen} (null when their search found nothing)
	 * on top of their Forward in {@code slotIdx}. {@link RemoteOpponent} has already checked that
	 * every index fits this board.
	 *
	 * <p>The deck is shuffled here from its own stream rather than simply set to the order they
	 * sent: the shuffle is what keeps this client's stream for their deck level with theirs, so
	 * the next search shuffles the same way on both sides. {@code sentDeckOrder} is then the
	 * check — a different order means the two copies had already drifted apart.
	 */
	void executeRemotePriming(CardData card, int slotIdx, List<Integer> discardIndices,
			List<Integer> backupDullIndices, CardData chosen, List<CardData> sentDeckOrder) {
		payPrimingCost(false, card, new ArrayList<>(discardIndices), new ArrayList<>(backupDullIndices));
		java.util.Deque<CardData> deck = mw.gameState.getP2MainDeck();
		if (chosen != null) deck.removeIf(c -> c == chosen);
		mw.shuffleDeck(false);
		List<CardData> shuffled = new ArrayList<>(deck);
		boolean same = shuffled.size() == sentDeckOrder.size();
		for (int i = 0; same && i < shuffled.size(); i++) same = shuffled.get(i) == sentDeckOrder.get(i);
		if (!same) {
			mw.reportDesync("opponent's deck after Priming is in a different order here");
			deck.clear();
			deck.addAll(sentDeckOrder);
			mw.refreshP2DeckLabel();
		}
		if (chosen != null) applyPrimedCard(chosen, card, slotIdx, false);
		else mw.logEntry("[P2] Priming: \"" + card.primingTarget() + "\" not found in deck — no card placed");
		mw.refreshP2HandCountLabel();
		mw.refreshP2BreakLabel();
	}

	/** Places {@code chosen} as the primed top card on {@code isP1}'s {@code slotIdx} and logs the action. */
	void applyPrimedCard(CardData chosen, CardData primingCard, int slotIdx, boolean isP1) {
		(isP1 ? mw.p1ForwardPrimedTop : mw.p2ForwardPrimedTop).set(slotIdx, chosen);
		mw.logEntry((isP1 ? "" : "[P2] ") + "Primed: \"" + primingCard.name() + "\" topped with \"" + chosen.name() + "\"");
		if (isP1) mw.refreshP1ForwardSlot(slotIdx); else mw.refreshP2ForwardSlot(slotIdx);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForPrimedInto(primingCard, chosen, isP1);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(primingCard, isP1);
	}

	/**
	 * Shows a modal dialog letting the player pick which version of the priming
	 * target to pull from the deck when multiple printings are present.
	 * Closing without a choice auto-selects the first match.
	 */
	private void showPrimingVersionSelectDialog(List<CardData> matches, CardData primingCard, int slotIdx,
			LocalPriming sent) {
		JDialog dlg = new JDialog(mw.frame,
				"Choose version: " + primingCard.primingTarget() + " (" + matches.size() + " found)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		// Holds the picked version; defaults to first match so closing without a click auto-picks.
		CardData[] picked = { matches.get(0) };

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));

		for (CardData candidate : matches) {
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) mw.showZoomAt(candidate.imageUrl());
					lbl.setBorder(MainWindow.createCardGlowBorder(Color.YELLOW));
				}
				@Override public void mouseExited(MouseEvent e) {
					mw.hideZoom();
					lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				}
				@Override public void mousePressed(MouseEvent e) {
					picked[0] = candidate;
					dlg.dispose();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(candidate.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JLabel hint = new JLabel("Click a card to select it", SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelFont(9));

		dlg.getContentPane().setLayout(new BorderLayout(0, 6));
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(hint, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(mw.frame);
		dlg.setVisible(true); // blocks until a card is clicked (dlg.dispose())

		// Execution resumes here after dialog closes
		finishLocalPriming(primingCard, slotIdx, picked[0], sent);
	}
}
