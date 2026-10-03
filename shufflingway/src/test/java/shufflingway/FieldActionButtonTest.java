package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.ImageIcon;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import shufflingway.graphics.ActionButton;
import shufflingway.graphics.CardAnimation;
import shufflingway.graphics.FieldCardIcon;
import shufflingway.graphics.FieldSlotLabel;

/**
 * Covers the action buttons along a field card's right edge: where {@link ActionButton} puts
 * them, how {@link FieldSlotLabel} routes a click on one, and which buttons the board gives each
 * side's cards. Geometry is derived from the live {@code CARD_W}/{@code CARD_H}, so the
 * assertions hold at any UI scale.
 */
class FieldActionButtonTest {

	// ── Geometry ─────────────────────────────────────────────────────────

	private static final int W = CardAnimation.CARD_W, H = CardAnimation.CARD_H, G = CardAnimation.LEFT_GUTTER;

	// An active card's right edge faces right: the buttons have the strip beside the art to
	// themselves, clear of the art and inside the canvas.
	@Test
	void activeButtonsSitInTheStripRightOfTheArt() {
		for (int n = 1; n <= 5; n++) {
			List<Ellipse2D.Float> circles = ActionButton.layout(CardState.ACTIVE, n);
			assertEquals(n, circles.size());
			for (Ellipse2D.Float c : circles) {
				assertTrue(c.x >= G + W, "clear of the art");
				assertTrue(c.x + c.width <= H, "inside the canvas");
				assertTrue(c.y >= 0 && c.y + c.height <= H, "within the card's height");
			}
			assertNoOverlap(circles);
		}
	}

	// Dulled, the card's right edge is its bottom: the buttons move to the strip below the art,
	// and the stack runs right to left so each ability keeps its spot on the turned card.
	@Test
	void dullButtonsSitInTheStripBelowTheArtMirrored() {
		List<Ellipse2D.Float> circles = ActionButton.layout(CardState.DULL, 4);
		for (Ellipse2D.Float c : circles) {
			assertTrue(c.y >= G + W, "below the dulled art");
			assertTrue(c.y + c.height <= H, "inside the canvas");
		}
		for (int i = 1; i < circles.size(); i++)
			assertTrue(circles.get(i).x < circles.get(i - 1).x, "the first ability is rightmost");
		assertNoOverlap(circles);
	}

	// More buttons than fit at the usual spacing squeeze together rather than run off the canvas.
	@Test
	void aLongStackIsSqueezedOntoTheCanvas() {
		for (Ellipse2D.Float c : ActionButton.layout(CardState.ACTIVE, 9))
			assertTrue(c.y >= 0 && c.y + c.height <= H, "every button stays on the canvas");
	}

	@Test
	void indexAtFindsEachButtonAndNothingElse() {
		for (CardState state : List.of(CardState.ACTIVE, CardState.DULL)) {
			List<Ellipse2D.Float> circles = ActionButton.layout(state, 3);
			for (int i = 0; i < circles.size(); i++)
				assertEquals(i, ActionButton.indexAt(state, 3, circles.get(i).getCenterX(), circles.get(i).getCenterY()),
						state + ": the centre of a button is that button");
			assertEquals(-1, ActionButton.indexAt(state, 3, H / 2.0, H / 2.0), state + ": the card art is no button");
		}
		Ellipse2D.Float a = ActionButton.layout(CardState.ACTIVE, 2).get(0), b = ActionButton.layout(CardState.ACTIVE, 2).get(1);
		assertEquals(-1, ActionButton.indexAt(CardState.ACTIVE, 2, a.getCenterX(), (a.y + a.height + b.y) / 2),
				"the gap between two buttons");
	}

	private static void assertNoOverlap(List<Ellipse2D.Float> circles) {
		for (int i = 1; i < circles.size(); i++) {
			Ellipse2D.Float p = circles.get(i - 1), c = circles.get(i);
			double dist = Math.hypot(p.getCenterX() - c.getCenterX(), p.getCenterY() - c.getCenterY());
			assertTrue(dist >= p.width, "neighbouring buttons must not overlap");
		}
	}

	// ── Click routing ────────────────────────────────────────────────────

	/** A slot showing a settled active card with two buttons, the second unusable. */
	private static FieldSlotLabel slotWithButtons(List<Runnable> actions) {
		FieldSlotLabel slot = new FieldSlotLabel(SwingConstants.CENTER, () -> null);
		slot.setSize(H, H);
		slot.setIcon(new FieldCardIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB), CardState.ACTIVE));
		slot.setButtons(List.of(
				new ActionButton.Spec(ActionButton.Kind.ABILITY, List.of(), ActionButton.Glyph.NONE, "1", true,  "first"),
				new ActionButton.Spec(ActionButton.Kind.ABILITY, List.of(), ActionButton.Glyph.DULL, null, false, "second")), actions);
		return slot;
	}

	private static Ellipse2D.Float button(int i) {
		return ActionButton.layout(CardState.ACTIVE, 2).get(i);
	}

	private static void click(FieldSlotLabel slot, double x, double y) {
		for (int id : new int[]{ MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED })
			slot.dispatchEvent(new MouseEvent(slot, id, 0, MouseEvent.BUTTON1_DOWN_MASK,
					(int) x, (int) y, 1, false, MouseEvent.BUTTON1));
	}

	/** Lets the actions a click queued with invokeLater run. */
	private static void flushEdt() throws Exception {
		SwingUtilities.invokeAndWait(() -> { });
	}

	/** Counts the presses that reach the slot's own listeners — attack selection, the menu. */
	private static int[] countPresses(FieldSlotLabel slot) {
		int[] presses = { 0 };
		slot.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) { presses[0]++; }
		});
		return presses;
	}

	@Test
	void clickingAUsableButtonRunsItsActionAndNotTheCardsListeners() throws Exception {
		int[] fired = { 0 };
		FieldSlotLabel slot = slotWithButtons(List.of(() -> fired[0]++, () -> { }));
		int[] presses = countPresses(slot);

		click(slot, button(0).getCenterX(), button(0).getCenterY());
		flushEdt();
		assertEquals(1, fired[0], "the button's action runs on release");
		assertEquals(0, presses[0], "the press never reaches the card's own handlers");
	}

	@Test
	void clickingAnUnusableButtonDoesNothingAtAll() throws Exception {
		int[] fired = { 0 };
		FieldSlotLabel slot = slotWithButtons(List.of(() -> { }, () -> fired[0]++));
		int[] presses = countPresses(slot);

		click(slot, button(1).getCenterX(), button(1).getCenterY());
		flushEdt();
		assertEquals(0, fired[0], "an unusable button does not fire");
		assertEquals(0, presses[0], "and the click is still not a click on the card behind it");
	}

	@Test
	void releasingOffTheButtonCancelsIt() throws Exception {
		int[] fired = { 0 };
		FieldSlotLabel slot = slotWithButtons(List.of(() -> fired[0]++, () -> { }));
		Ellipse2D.Float b = button(0);
		slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK,
				(int) b.getCenterX(), (int) b.getCenterY(), 1, false, MouseEvent.BUTTON1));
		slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_RELEASED, 0, 0,
				H / 2, H / 2, 1, false, MouseEvent.BUTTON1));
		flushEdt();
		assertEquals(0, fired[0], "like any button, dragging off before release backs out");
	}

	@Test
	void clicksOffTheButtonsReachTheCard() throws Exception {
		FieldSlotLabel slot = slotWithButtons(List.of(() -> { }, () -> { }));
		int[] presses = countPresses(slot);
		click(slot, H / 2.0, H / 2.0);
		assertEquals(1, presses[0], "a click on the art is still attack selection or the menu");
	}

	// Mid-rotation the slot shows an animation frame, laid out for neither state. Buttons drawn
	// over it would sit in the wrong place, so a slot only has buttons over a settled card.
	@Test
	void noButtonsOverAnAnimationFrame() throws Exception {
		int[] fired = { 0 };
		FieldSlotLabel slot = slotWithButtons(List.of(() -> fired[0]++, () -> { }));
		slot.setIcon(new ImageIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB)));
		int[] presses = countPresses(slot);
		click(slot, button(0).getCenterX(), button(0).getCenterY());
		flushEdt();
		assertEquals(0, fired[0], "no button to press");
		assertEquals(1, presses[0], "so the click goes to the card");
	}

	@Test
	void aButtonsTooltipIsItsAbility() {
		FieldSlotLabel slot = slotWithButtons(List.of(() -> { }, () -> { }));
		Ellipse2D.Float b = button(1);
		assertEquals("second", slot.getToolTipText(new MouseEvent(slot, MouseEvent.MOUSE_MOVED, 0, 0,
				(int) b.getCenterX(), (int) b.getCenterY(), 0, false)));
		assertNull(slot.getToolTipText(new MouseEvent(slot, MouseEvent.MOUSE_MOVED, 0, 0,
				H / 2, H / 2, 0, false)), "off the buttons, the slot's own tooltip (none here)");
	}

	// ── How a button reads its cost ──────────────────────────────────────

	private static ActionButton.Spec specFor(String text) {
		List<ActionAbility> parsed = CardData.parseActionAbilities(text);
		assertEquals(1, parsed.size(), "fixture must parse as one action ability: " + text);
		return MainWindow.abilityButtonSpec(parsed.get(0), true, "tip");
	}

	@Test
	void aSingleElementCostColoursTheWholeButton() {
		ActionButton.Spec spec = specFor("《Fire》: Draw 1 card.");
		assertEquals(List.of(ElementColor.FIRE.color), spec.colors());
		assertEquals(ActionButton.Glyph.NONE, spec.glyph());
		assertNull(spec.label(), "the colour says it all");
	}

	// Each Element once, in printed order: Braska 16-133S pays Fire twice, and the button still
	// splits three ways.
	@Test
	void eachDistinctElementGetsItsOwnShareInPrintedOrder() {
		assertEquals(List.of(ElementColor.EARTH.color, ElementColor.ICE.color),
				specFor("《Earth》《Ice》: Draw 1 card.").colors());
		assertEquals(List.of(ElementColor.FIRE.color, ElementColor.WIND.color, ElementColor.WATER.color),
				specFor("《Fire》《Fire》《Wind》《Water》: Draw 1 card.").colors());
	}

	@Test
	void aDullCostCarriesTheDullGlyphOverItsColours() {
		ActionButton.Spec spec = specFor("《Fire》《Ice》《Dull》: Draw 1 card.");
		assertEquals(ActionButton.Glyph.DULL, spec.glyph());
		assertEquals(2, spec.colors().size(), "the Elements still colour the face");
		assertNull(spec.label(), "one symbol at a time: the Dull arrow");
		assertEquals(ActionButton.Glyph.DULL, specFor("《Dull》: Draw 1 card.").glyph());
	}

	// Any-Element CP leaves the button neutral; the amount is what the card prints in its cost
	// circle, so a Firion with four 《1》/《2》 abilities is not four blank buttons.
	@Test
	void aGenericCostIsNeutralAndShowsItsAmount() {
		ActionButton.Spec spec = specFor("《2》: Draw 1 card.");
		assertTrue(spec.colors().isEmpty(), "no Element, no colour");
		assertEquals("2", spec.label());
		assertNull(specFor("《Earth》《1》: Draw 1 card.").label(), "an Element's colour outranks the amount");
	}

	@Test
	void aDiscardCostCarriesTheDiscardArrow() {
		ActionButton.Spec spec = specFor("Discard 1 card: Draw 1 card.");
		assertTrue(spec.colors().isEmpty());
		assertEquals(ActionButton.Glyph.DISCARD, spec.glyph());
		assertNull(spec.label());
	}

	// The S marks a Special ability on any face — including Fire's red, which is why the glyph is
	// outlined — and it is the one shown when the cost also dulls.
	@Test
	void aSpecialAbilityCarriesTheSOverEveryOtherSymbol() {
		ActionButton.Spec spec = specFor("[[s]]Grenade Bomb[[/]] 《S》《Fire》: Draw 1 card.");
		assertEquals(ActionButton.Glyph.SPECIAL, spec.glyph());
		assertEquals(List.of(ElementColor.FIRE.color), spec.colors());
		assertEquals(ActionButton.Glyph.SPECIAL,
				specFor("[[s]]Grenade Bomb[[/]] 《S》《Dull》: Draw 1 card.").glyph(), "S outranks Dull");
		assertEquals(ActionButton.Glyph.DULL,
				specFor("《Dull》, discard 1 card: Draw 1 card.").glyph(), "Dull outranks discard");
	}

	@Test
	void aCostOfAnotherKindIsNeutralAndBlank() {
		ActionButton.Spec spec = specFor("Put 1 Backup into the Break Zone: Draw 1 card.");
		assertTrue(spec.colors().isEmpty());
		assertEquals(ActionButton.Glyph.NONE, spec.glyph());
		assertNull(spec.label());
	}

	// ── Which abilities ask before they resolve ──────────────────────────

	// An ability with no CP and no X skips the payment dialog and resolves on the click, so its
	// button asks first. One with CP to pay already gets a dialog the player can cancel.
	@Test
	void onlyAbilitiesWithoutAPaymentDialogNeedConfirming() {
		MainWindow mw = inP1Main1();
		CardData card = forward("Drawer", "", "");
		ActionAbility dullOnly = CardData.parseActionAbilities("《Dull》: Draw 1 card.").get(0);
		ActionAbility withCp   = CardData.parseActionAbilities("《Fire》《Dull》: Draw 1 card.").get(0);
		ActionAbility withX    = CardData.parseActionAbilities("《X》: Draw X cards.").get(0);
		assertFalse(mw.autoAbilityTriggers.paymentOffersAWayBack(dullOnly, card, true),
				"nothing to pay in a dialog: the button has to ask");
		assertTrue(mw.autoAbilityTriggers.paymentOffersAWayBack(withCp, card, true));
		assertTrue(mw.autoAbilityTriggers.paymentOffersAWayBack(withX, card, true),
				"choosing X happens in the payment dialog");
	}

	// ── Which buttons each side gets ─────────────────────────────────────

	private static CardData forward(String name, String text, String primingTarget) {
		return new CardData(null, name, "Water", 2, 5000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), primingTarget, CardData.parsePrimingCost(text),
				CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	private static MainWindow inP1Main1() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P1);
		while (mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1) mw.gameState.advancePhase();
		mw.refreshPhaseTracker();
		return mw;
	}

	private static List<ActionButton.Spec> buttons(MainWindow mw, boolean isP1, int idx) {
		mw.syncSlotButtons();
		FieldSlotLabel slot = mw.fieldSlot(isP1, ForwardTarget.CardZone.FORWARD, idx);
		assertNotNull(slot, "the Forward's slot is a FieldSlotLabel");
		return slot.buttons();
	}

	@Test
	void p1sForwardGetsAButtonPerAbilityThatGlowsWhenTheMenuWouldAllowIt() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Drawer", "《Dull》: Draw 1 card.[[br]]《3》: Draw 1 card.", ""));
		mw.p1ForwardPlayedOnTurn.set(0, 0);   // on the field since before this turn

		List<ActionButton.Spec> specs = buttons(mw, true, 0);
		assertEquals(2, specs.size(), "one button per action ability");
		assertTrue(specs.get(0).usable(), "the 《Dull》 ability can be paid by an active Forward");
		assertFalse(specs.get(1).usable(), "nothing to pay 《3》 with");

		mw.p1ForwardStates.set(0, CardState.DULL);
		assertFalse(buttons(mw, true, 0).get(0).usable(), "dulled, the 《Dull》 cost cannot be paid");
	}

	@Test
	void p2sCardsShowOnlyAbilitiesEitherPlayerMayUse() {
		MainWindow mw = inP1Main1();
		mw.placeP2CardInForwardZone(forward("Own", "《Dull》: Draw 1 card.", ""));
		mw.placeP2CardInForwardZone(forward("Shared",
				"Discard 2 cards: Draw 1 card. Each player can use this ability.", ""));

		assertTrue(buttons(mw, false, 0).isEmpty(), "P2's own abilities are P2's to use: nothing to click");
		List<ActionButton.Spec> shared = buttons(mw, false, 1);
		assertEquals(1, shared.size(), "an ability either player may use is P1's to click too");
		assertTrue(shared.get(0).tooltip().contains("pay your own cost"));
		assertTrue(shared.get(0).tooltip().contains("Draw 1 card"), "the tooltip describes the ability");
	}

	@Test
	void aForwardThatCanBePrimedGetsAPrimingButtonUntilItIsPrimed() {
		MainWindow mw = inP1Main1();
		String text = "Priming \"Shiva (XVI)\" -- 《Ice》《2》";
		mw.placeCardInForwardZone(forward("Jill", text, "Shiva (XVI)"));

		List<ActionButton.Spec> specs = buttons(mw, true, 0);
		assertEquals(1, specs.size());
		assertEquals(ActionButton.Kind.PRIME, specs.get(0).kind());
		assertFalse(specs.get(0).usable(), "no CP to pay the Priming cost with");

		mw.p1ForwardPrimedTop.set(0, forward("Shiva (XVI)", "", ""));
		assertTrue(buttons(mw, true, 0).isEmpty(), "once primed, there is nothing left to prime");
	}

	@Test
	void anEmptySlotHasNoButtons() {
		MainWindow mw = inP1Main1();
		mw.syncSlotButtons();
		FieldSlotLabel backup = mw.fieldSlot(true, ForwardTarget.CardZone.BACKUP, 0);
		assertNotNull(backup);
		assertEquals(new ArrayList<ActionButton.Spec>(), backup.buttons());
	}
}
