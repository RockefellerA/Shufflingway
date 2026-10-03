package shufflingway;

import java.awt.Image;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import shufflingway.graphics.CardAnimation;
import shufflingway.graphics.CardLimitBreakAnimator;
import shufflingway.graphics.CardRfpAnimator;
import shufflingway.graphics.CardSlideAnimator;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * Sequences the arrival of a card on the field so the player always sees
 * <em>animation → card → ability popups</em>, in that order.
 *
 * <p>Without this, a card is drawn in its slot and its enter-the-field abilities resolve the
 * instant it is placed, so an arrival animation would play over a card that is already there
 * (and behind any ability popup the arrival triggered).  {@link #placeWithAnim} keeps the
 * placement itself synchronous — game state is never left stale — but holds back the two visible
 * parts of it:
 * <ul>
 *   <li>the slot render, via {@link #holdSlotBlank} called from MainWindow's slot refreshers;</li>
 *   <li>the enter-the-field triggers, via {@link #fireEntersField} called from MainWindow's
 *       placement methods.</li>
 * </ul>
 * Both are released by {@link #finish} once the animation has run its course.
 *
 * <p>Extracted from MainWindow so any future "card enters the field from zone X" animation only
 * has to add a {@link Style} constant and the case that starts it.
 */
final class FieldEntryAnimator {

	/** How a card animates as it arrives on the field. */
	enum Style {
		/** Reversed removed-from-game burst — the card expands out of a flash of light. */
		RFG_RETURN,
		/** A Warp card arriving once its last counter came off; borrows the RFG burst for now. */
		WARP_IN,
		/**
		 * A card played from hand — cast, or put onto the field by an effect — sliding from its
		 * place in the hand to its slot. It travels already turned to the state it enters in, so a
		 * card that enters dull slides out dull.
		 */
		FROM_HAND,
		/** A card cast out of the Limit Break deck — it lands in its slot in a golden flash and shakes. */
		LIMIT_BREAK
	}

	private final MainWindow mw;

	/**
	 * Cards currently animating their way onto the field, mapped to the enter-the-field triggers
	 * captured while their animation plays.  A card is in here for exactly the span between its
	 * placement and {@link #finish}.
	 */
	private final Map<CardData, List<Runnable>> pending = new IdentityHashMap<>();

	/**
	 * Arrivals not yet finished with.  Unlike {@link #pending}, a card is still counted here while
	 * its held-back triggers run, so {@link #isBusy} stays true across any dialog they open — the
	 * end step must not advance the turn out from under an ability the player is still resolving.
	 */
	private int inFlight;

	/** Fully transparent slot icon: reserves an occupied slot without drawing its card yet. */
	private static ImageIcon blankIcon;

	FieldEntryAnimator(MainWindow mw) {
		this.mw = mw;
	}

	/**
	 * Runs {@code placement} and animates the card onto the field in {@code style}.  The placement
	 * happens immediately, but the card is not drawn in its slot and its enter-the-field abilities
	 * do not fire until the animation has finished.
	 */
	void placeWithAnim(CardData card, boolean isP1, Style style, Runnable placement) {
		placeWithAnim(card, isP1, style, null, placement);
	}

	/**
	 * @param origin where the card starts from, in layered-pane coordinates; read by styles that
	 *               travel ({@link Style#FROM_HAND}), ignored by those that appear in place. A
	 *               travelling style with no origin appears at its slot without moving.
	 */
	void placeWithAnim(CardData card, boolean isP1, Style style, Point origin, Runnable placement) {
		// Already mid-animation (re-entrant arrival of the same instance) — just place it.
		if (pending.containsKey(card)) { placement.run(); return; }
		pending.put(card, new ArrayList<>());
		inFlight++;
		boolean placed = false;
		try {
			placement.run();
			placed = true;
		} finally {
			if (!placed) finish(card, isP1, null);
		}
		// Deferred one EDT pass: the destination slot has no bounds until its zone is laid out.
		SwingUtilities.invokeLater(() -> play(card, isP1, style, origin));
	}

	/**
	 * Keeps a slot blank while its card is still animating its way onto the field — a transparent
	 * icon reserves the slot (so it still counts as occupied) without drawing the card.  Returns
	 * {@code true} when the caller must skip its normal slot render.
	 */
	boolean holdSlotBlank(JLabel slot, CardData card) {
		if (card == null || !pending.containsKey(card)) return false;
		if (slot != null) { slot.setIcon(blankIcon()); slot.setText(null); }
		return true;
	}

	/**
	 * Fires {@code card}'s enter-the-field auto abilities, or queues them until its arrival
	 * animation has finished so no ability popup lands on top of the animation.
	 *
	 * <p>The triggers read MainWindow's arrival flags — "was it cast from hand?", "did it Warp in?",
	 * "was one of its alternate costs taken?" — at the moment they run, and the placement that set
	 * them has long returned by then, so a queued run restores the values they had when the card
	 * actually arrived.  Without this, {@code castOnly}, {@code warpOnly} and {@code altCostOnly}
	 * abilities would silently stop firing.  The pending "will not trigger" suppression is taken
	 * the same way, so it silences this arrival and not whichever card enters next.
	 */
	void fireEntersField(CardData card, boolean isP1, boolean paidExtraCost) {
		List<Runnable> queue = pending.get(card);
		if (queue != null) {
			boolean wasCast    = mw.lastCardWasCast;
			boolean warpedIn   = mw.lastCardWarpedIn;
			boolean viaAltCost = mw.lastCardCastViaAltCost;
			// What was resolving as it arrived — "enters the field due to an ability" (21-018R Rain)
			// and the entry-cause record read it.
			CardData abilSource   = mw.currentAbilitySource;
			boolean  abilSourceP1 = mw.currentAbilitySourceIsP1;
			CardData sumSource    = mw.currentSummonSource;
			boolean  sumSourceP1  = mw.currentSummonSourceIsP1;
			boolean  isSummon     = mw.currentResolutionIsSummon;
			// "Its auto-ability will not trigger" belongs to this arrival, so it is taken now: left
			// in the count, it would silence whatever reached the field next while this card was
			// still sliding in, and leave this one to fire.
			boolean suppressed = mw.suppressAutoAbilityForNextCards > 0;
			if (suppressed) mw.suppressAutoAbilityForNextCards--;
			queue.add(() -> {
				boolean prevCast    = mw.lastCardWasCast;
				boolean prevWarp    = mw.lastCardWarpedIn;
				boolean prevAltCost = mw.lastCardCastViaAltCost;
				CardData prevAbil   = mw.currentAbilitySource;
				boolean  prevAbilP1 = mw.currentAbilitySourceIsP1;
				CardData prevSum    = mw.currentSummonSource;
				boolean  prevSumP1  = mw.currentSummonSourceIsP1;
				boolean  prevIsSum  = mw.currentResolutionIsSummon;
				int      prevSuppress = mw.suppressAutoAbilityForNextCards;
				mw.suppressAutoAbilityForNextCards = suppressed ? 1 : 0;
				mw.lastCardWasCast        = wasCast;
				mw.lastCardWarpedIn       = warpedIn;
				mw.lastCardCastViaAltCost = viaAltCost;
				mw.currentAbilitySource      = abilSource;
				mw.currentAbilitySourceIsP1  = abilSourceP1;
				mw.currentSummonSource       = sumSource;
				mw.currentSummonSourceIsP1   = sumSourceP1;
				mw.currentResolutionIsSummon = isSummon;
				try {
					mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, isP1, paidExtraCost);
				} finally {
					mw.lastCardWasCast        = prevCast;
					mw.lastCardWarpedIn       = prevWarp;
					mw.lastCardCastViaAltCost = prevAltCost;
					mw.currentAbilitySource      = prevAbil;
					mw.currentAbilitySourceIsP1  = prevAbilP1;
					mw.currentSummonSource       = prevSum;
					mw.currentSummonSourceIsP1   = prevSumP1;
					mw.currentResolutionIsSummon = prevIsSum;
					mw.suppressAutoAbilityForNextCards = prevSuppress;
				}
			});
			return;
		}
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, isP1, paidExtraCost);
	}

	/**
	 * Plays the arrival animation over the slot the card just took, then hands off to
	 * {@link #finish}.  Falls straight through to the reveal when the card has no field slot (a
	 * Summon cast out of the RFG zone goes to the stack instead) or has no artwork.
	 */
	private void play(CardData card, boolean isP1, Style style, Point origin) {
		JLabel label = mw.findFieldSlotLabel(card, isP1);
		String url   = card.imageUrl();
		if (label == null || label.getWidth() == 0 || url == null) {
			finish(card, isP1, null);
			return;
		}
		JLayeredPane lp     = mw.frame.getRootPane().getLayeredPane();
		Point        center = SwingUtilities.convertPoint(label, label.getWidth() / 2, label.getHeight() / 2, lp);
		CardState    state  = mw.fieldSlotState(card, isP1);
		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null
						: CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state);
			}
			@Override protected void done() {
				BufferedImage loaded = null;
				try { loaded = get(); } catch (InterruptedException | ExecutionException ignored) {}
				if (loaded == null) { finish(card, isP1, null); return; }
				final BufferedImage img = loaded;
				Timer t = new Timer(start(style, img, origin, center), e -> finish(card, isP1, img));
				t.setRepeats(false);
				t.start();
			}
		}.execute();
	}

	/**
	 * Starts {@code style}'s arrival animation ending on {@code center} and returns its duration in
	 * ms. {@code img} is the slot render, so it lands matching the card that replaces it.
	 */
	private int start(Style style, BufferedImage img, Point origin, Point center) {
		return switch (style) {
			case RFG_RETURN, WARP_IN -> {
				mw.rfpAnimator.startWarpIn(img, center);
				yield CardRfpAnimator.TOTAL_FRAMES * CardRfpAnimator.FRAME_MS;
			}
			case FROM_HAND -> {
				if (origin == null) yield 0;
				mw.cardSlideAnimator.startSlide(img, origin, center, 0);
				yield CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
			}
			case LIMIT_BREAK -> {
				mw.limitBreakAnimator.start(img, center);
				yield CardLimitBreakAnimator.TOTAL_FRAMES * CardLimitBreakAnimator.FRAME_MS;
			}
		};
	}

	/**
	 * Draws the card in its slot, then runs the enter-the-field triggers the animation held back.
	 * {@code rendered} is the image the animation just finished on: painting it straight into the
	 * slot guarantees the card is on screen before any ability popup, since the regular slot
	 * refresh only sets its icon once a background worker has re-rendered it.
	 */
	private void finish(CardData card, boolean isP1, BufferedImage rendered) {
		List<Runnable> queued = pending.remove(card);
		if (rendered != null) {
			JLabel slot = mw.findFieldSlotLabel(card, isP1);
			if (slot != null) { slot.setIcon(new ImageIcon(rendered)); slot.setText(null); }
		}
		mw.refreshFieldSlotFor(card, isP1);
		try {
			if (queued != null) for (Runnable r : queued) r.run();
		} finally {
			inFlight--;
		}
	}

	/**
	 * True while any card is still arriving — mid-animation, or resolving the abilities that
	 * arrival triggered. Callers that end a turn or hand over priority must wait for this to clear.
	 */
	boolean isBusy() { return inFlight > 0; }

	private static ImageIcon blankIcon() {
		if (blankIcon == null)
			blankIcon = new ImageIcon(new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB));
		return blankIcon;
	}
}
