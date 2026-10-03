package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.FontMetrics;
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
import javax.swing.ToolTipManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;

import org.junit.jupiter.api.Test;

import shufflingway.graphics.AbilityToolTip;
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

	// ── The button tooltip ───────────────────────────────────────────────

	private static void moveTo(FieldSlotLabel slot, double x, double y) {
		slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_MOVED, 0, 0, (int) x, (int) y, 0, false));
	}

	@Test
	void aButtonGetsTheAbilityTooltipAndTheRestOfTheSlotKeepsTheStockOne() {
		FieldSlotLabel slot = slotWithButtons(List.of(() -> { }, () -> { }));
		moveTo(slot, button(1).getCenterX(), button(1).getCenterY());
		assertTrue(slot.createToolTip() instanceof AbilityToolTip, "over a button — even an unusable one");

		moveTo(slot, H / 2.0, H / 2.0);
		assertFalse(slot.createToolTip() instanceof AbilityToolTip, "trait tabs and counters keep theirs");
	}

	// The tooltip manager's start delay is one setting for the whole application, so the slot only
	// borrows it while the pointer is on a button.
	@Test
	void theTooltipDelayIsHalfASecondOnAButtonAndRestoredOffIt() {
		ToolTipManager ttm = ToolTipManager.sharedInstance();
		int before = ttm.getInitialDelay();
		try {
			ttm.setInitialDelay(750);
			FieldSlotLabel slot = slotWithButtons(List.of(() -> { }, () -> { }));
			moveTo(slot, button(0).getCenterX(), button(0).getCenterY());
			assertEquals(500, ttm.getInitialDelay());
			moveTo(slot, button(1).getCenterX(), button(1).getCenterY());
			assertEquals(500, ttm.getInitialDelay(), "still on a button");
			moveTo(slot, H / 2.0, H / 2.0);
			assertEquals(750, ttm.getInitialDelay(), "handed back off the buttons");

			moveTo(slot, button(0).getCenterX(), button(0).getCenterY());
			slot.dispatchEvent(new MouseEvent(slot, MouseEvent.MOUSE_EXITED, 0, 0, -1, -1, 0, false));
			assertEquals(750, ttm.getInitialDelay(), "and when the pointer leaves the slot from a button");
		} finally {
			ttm.setInitialDelay(before);
		}
	}

	@Test
	void theAbilityTooltipIsWhiteOnDarkGreyInARaisedBevel() {
		AbilityToolTip tip = new AbilityToolTip();
		assertTrue(tip.isOpaque());
		assertEquals(Color.WHITE, tip.getForeground());
		Color bg = tip.getBackground();
		assertTrue(bg.getRed() < 0x50 && bg.getRed() == bg.getGreen(), "a dark, neutral grey");
		assertTrue(tip.getBorder() instanceof CompoundBorder cb
				&& cb.getOutsideBorder() instanceof BevelBorder bevel
				&& bevel.getBevelType() == BevelBorder.RAISED, "framed by a raised bevel");
	}

	private static final String SHORT_DESCRIPTION = "<html>[Dull, Fire] → Draw 1 card.</html>";
	private static final String LONG_DESCRIPTION = "<html><font color='#ED930D'>[Ultimate Jecht Shot]</font> "
			+ "[Dull, S, Fire, any, any] → Choose 1 Forward opponent controls. Break it. If you have received "
			+ "5 or more damage, deal your opponent 1 point of damage and draw 1 card. You can only use this "
			+ "ability during your turn.</html>";

	/** How many lines of text {@code tip} lays its description out on, from its height. */
	private static double linesOf(AbilityToolTip tip, String text) {
		AbilityToolTip oneLine = new AbilityToolTip();
		oneLine.setTipText("<html>x</html>");
		int lineHeight = oneLine.getPreferredSize().height - oneLine.getInsets().top - oneLine.getInsets().bottom;
		tip.setTipText(text);
		int height = tip.getPreferredSize().height - tip.getInsets().top - tip.getInsets().bottom;
		return height / (double) lineHeight;
	}

	// A fixed width made every tooltip as wide as the longest one, so short descriptions trailed off
	// into empty grey. A short one is now exactly its own length.
	@Test
	void aShortDescriptionSitsOnOneLineAsWideAsItsText() {
		AbilityToolTip tip = new AbilityToolTip();
		assertEquals(1.0, linesOf(tip, SHORT_DESCRIPTION), 0.25);
		assertFalse(tip.getTipText().contains("width"), "no width imposed: the text sets it");
	}

	@Test
	void aLongDescriptionWrapsOntoTwoEvenLines() {
		AbilityToolTip tip = new AbilityToolTip();
		assertEquals(2.0, linesOf(tip, LONG_DESCRIPTION), 0.25, "two lines, not three");

		FontMetrics fm = tip.getFontMetrics(tip.getFont());
		int oneLineWidth = fm.stringWidth("[Ultimate Jecht Shot] [Dull, S, Fire, any, any] → Choose 1 Forward "
				+ "opponent controls. Break it. If you have received 5 or more damage, deal your opponent 1 point "
				+ "of damage and draw 1 card. You can only use this ability during your turn.");
		int textWidth = tip.getPreferredSize().width - tip.getInsets().left - tip.getInsets().right;
		assertTrue(textWidth < oneLineWidth * 0.75, "about half its one-line length, not the whole of it");
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

	// 101 cards print 《0》 (11-018H Sabin). Nothing to pay reads as a 0, the way the card prints it.
	@Test
	void anAbilityThatCostsNothingShowsAZero() {
		ActionButton.Spec spec = specFor("《0》: Draw 1 card.");
		assertTrue(spec.colors().isEmpty());
		assertEquals(ActionButton.Glyph.NONE, spec.glyph());
		assertEquals("0", spec.label());
		assertNull(specFor("Put 1 Backup into the Break Zone: Draw 1 card.").label(),
				"a cost of another kind is still a cost, not a 0");
	}

	@Test
	void aCrystalCostCarriesTheCrystal() {
		ActionButton.Spec spec = specFor("《C》: Draw 1 card.");
		assertEquals(ActionButton.Glyph.CRYSTAL, spec.glyph());
		assertNull(spec.label());
		assertEquals(ActionButton.Glyph.CRYSTAL, specFor("《C》《2》: Draw 1 card.").glyph(),
				"the Crystal outranks the generic amount");
		assertEquals(ActionButton.Glyph.DULL, specFor("《C》《Dull》: Draw 1 card.").glyph(),
				"and Dull outranks the Crystal");
	}

	private static ActionButton.Spec specFor(String text, String sourceName) {
		List<ActionAbility> parsed = CardData.parseActionAbilities(text);
		assertEquals(1, parsed.size(), "fixture must parse as one action ability: " + text);
		return MainWindow.abilityButtonSpec(parsed.get(0), sourceName, "#3366cc", true, "tip");
	}

	// BZ marks the card paying with itself (10-005C Gancanagh). Putting some other card there is a
	// different cost, which gets the dots.
	@Test
	void puttingTheCardItselfIntoTheBreakZoneReadsBZ() {
		String text = "Put Gancanagh into the Break Zone: Choose 1 Forward. Deal it 8000 damage.";
		assertEquals(ActionButton.Glyph.BREAK_ZONE, specFor(text, "Gancanagh").glyph());
		assertEquals(ActionButton.Glyph.BREAK_ZONE_OTHER, specFor(text, "Dendrobium").glyph(),
				"a Break Zone cost naming another card is not this card's own");
	}

	@Test
	void aRemoveFromGameCostCarriesTheRemovedCards() {
		assertEquals(ActionButton.Glyph.REMOVE_FROM_GAME, specFor(
				"Remove 1 Forward other than Lady Lilith from the game: Draw 1 card.", "Lady Lilith").glyph());
	}

	// The orb is drawn in the colour the owner's counters are, with how many the cost removes.
	@Test
	void aCounterCostCarriesTheOrbWithItsCount() {
		ActionButton.Spec spec = specFor("Remove 2 Ninja Counters from Edge: Draw 1 card.", "Edge");
		assertEquals(ActionButton.Glyph.COUNTERS, spec.glyph());
		assertEquals("2", spec.label());
		assertEquals("#3366cc", spec.counterColor());
		assertNull(specFor("《Dull》: Draw 1 card.", "Edge").counterColor(), "no orb, no orb colour");
	}

	@Test
	void aReturnToHandCostCarriesTheDownArrow() {
		assertEquals(ActionButton.Glyph.RETURN_TO_HAND, specFor(
				"Return 1 Forward you control to its owner's hand: Draw 1 card.", "Ursula").glyph());
	}

	// The dots mark a cost paid with something other than the card itself: its own 《Dull》 is the
	// plain arrow, dulling other Characters (10-084C Monk) is the arrow over dots.
	@Test
	void dullingOtherCharactersIsTheDullArrowOverDots() {
		assertEquals(ActionButton.Glyph.DULL_OTHERS,
				specFor("Dull 1 active Card Name Monk: Monk gains +1000 power until the end of the turn.", "Monk").glyph());
		assertEquals(ActionButton.Glyph.DULL, specFor("《Dull》: Draw 1 card.", "Monk").glyph(),
				"the card's own Dull keeps the plain arrow");
	}

	@Test
	void puttingAnotherCardIntoTheBreakZoneIsBZOverDots() {
		assertEquals(ActionButton.Glyph.BREAK_ZONE_OTHER,
				specFor("Put 1 Backup into the Break Zone: Draw 1 card.", "Mystic").glyph());
		assertEquals(ActionButton.Glyph.BREAK_ZONE,
				specFor("Put Mystic into the Break Zone: Draw 1 card.", "Mystic").glyph(), "the card itself is plain BZ");
	}

	@Test
	void millingTheDeckIsTheStackWithItsCount() {
		ActionButton.Spec spec = specFor("Put the top 2 cards of your deck into the Break Zone: Draw 1 card.", "Edea");
		assertEquals(ActionButton.Glyph.SELF_MILL, spec.glyph());
		assertEquals("2", spec.label());
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

	private static CardData backup(String name, String element, String text) {
		return new CardData(null, name, element, 2, 0, "Backup", false, 0, false, false,
				Set.of(), 0, List.of(), "", CardData.parsePrimingCost(text),
				CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	// Time Mage 1-049C: "《Ice》《Dull》". Paying the 《Dull》 turns Time Mage sideways, so it cannot
	// also be the Backup that makes the Ice — with an empty hand and no other Ice source, the
	// button must not light up, though Time Mage alone looks like an Ice Backup to dull.
	@Test
	void aBackupThatDullsForItsOwnCostCannotAlsoMakeTheCp() {
		MainWindow mw = inP1Main1();
		mw.p1BackupCards[0]  = backup("Time Mage", "Ice", "《Ice》《Dull》: Choose 1 Forward. It cannot attack this turn.");
		mw.p1BackupStates[0] = CardState.ACTIVE;
		mw.placeCardInForwardZone(forward("Target", "", ""));   // something for "Choose 1 Forward" to choose
		mw.syncSlotButtons();
		FieldSlotLabel slot = mw.fieldSlot(true, ForwardTarget.CardZone.BACKUP, 0);
		assertFalse(slot.buttons().get(0).usable(), "Time Mage cannot pay both the Ice and the Dull");

		mw.p1BackupCards[1]  = backup("Fire Mage", "Fire", "");
		mw.p1BackupStates[1] = CardState.ACTIVE;
		mw.syncSlotButtons();
		assertFalse(slot.buttons().get(0).usable(), "a Fire Backup makes no Ice");

		mw.p1BackupCards[2]  = backup("Ice Mage", "Ice", "");
		mw.p1BackupStates[2] = CardState.ACTIVE;
		mw.syncSlotButtons();
		assertTrue(slot.buttons().get(0).usable(), "a second Ice Backup makes the Ice; Time Mage pays the Dull");
	}

	private static CardData card(String name, String element, String type, String text) {
		return new CardData(null, name, element, 2, "Forward".equals(type) ? 5000 : 0, type, false, 0, false, false,
				Set.of(), 0, List.of(), "", List.of(),
				CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	private static boolean firstButtonUsable(MainWindow mw, ForwardTarget.CardZone zone, int idx) {
		mw.syncSlotButtons();
		return mw.fieldSlot(true, zone, idx).buttons().get(0).usable();
	}

	// Luca 3-023C pays four things at once: its own 《Dull》, "dull 1 active Card Name Rydia", the
	// 《S》 (a Luca from hand) and 《Fire》. When Rydia is a Fire Backup, she is the card that dulls
	// for the cost — so she cannot also make the Fire, and neither can Luca or the Luca in hand.
	@Test
	void lucasFireMustComeFromACardNoOtherPartOfItsCostUses() {
		MainWindow mw = inP1Main1();
		String text = "[[s]]Lightning Brain Buster[[/]] 《S》《Fire》《Dull》, dull 1 active Card Name Rydia: "
				+ "Choose 1 Forward. Deal it 10000 damage.";
		mw.p1BackupCards[0] = card("Luca", "Fire", "Backup", text);
		mw.p1BackupCards[1] = card("Rydia", "Fire", "Backup", "");
		mw.p1BackupStates[0] = mw.p1BackupStates[1] = CardState.ACTIVE;
		mw.gameState.getP1Hand().add(card("Luca", "Fire", "Backup", text));
		mw.placeCardInForwardZone(forward("Target", "", ""));
		assertFalse(firstButtonUsable(mw, ForwardTarget.CardZone.BACKUP, 0),
				"Luca dulls, Rydia dulls, the Luca in hand pays the S: nothing is left to make the Fire");

		mw.p1BackupCards[2] = card("Fire Mage", "Fire", "Backup", "");
		mw.p1BackupStates[2] = CardState.ACTIVE;
		assertTrue(firstButtonUsable(mw, ForwardTarget.CardZone.BACKUP, 0), "a third Fire Backup makes it");
	}

	// The card a Special's 《S》 is paid with leaves the hand for the 《S》, not for CP.
	@Test
	void theCardPayingTheSCannotAlsoPayTheCp() {
		MainWindow mw = inP1Main1();
		String text = "[[s]]Blast[[/]] 《S》《Fire》: Choose 1 Forward. Deal it 1000 damage.";
		mw.placeCardInForwardZone(card("Blaster", "Fire", "Forward", text));
		mw.gameState.getP1Hand().add(card("Blaster", "Fire", "Forward", text));
		assertFalse(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0),
				"the only Fire card in hand is the Blaster the S needs");

		mw.gameState.getP1Hand().add(card("Shiva", "Ice", "Summon", ""));
		assertFalse(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0), "an Ice card makes no Fire");

		mw.gameState.getP1Hand().add(card("Ifrit", "Fire", "Summon", ""));
		assertTrue(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0));
	}

	// Ninja 1-078C: "《Wind》, discard 1 card". With one card in hand it can pay one or the other.
	// With a second, the discard takes whichever card the Wind does not need.
	@Test
	void aDiscardCostAndTheCpCannotShareACard() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(card("Ninja", "Wind", "Forward",
				"《Wind》, discard 1 card: Choose 1 Forward. Deal it 1000 damage."));
		mw.gameState.getP1Hand().add(card("Wind Card", "Wind", "Summon", ""));
		assertFalse(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0), "one card, two costs");

		mw.gameState.getP1Hand().add(0, card("Fire Card", "Fire", "Summon", ""));
		assertTrue(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0),
				"discard the Fire card, pay the Wind with the Wind card — whichever order the hand is in");
	}

	// Palom 5-018L dulls a Forward for its cost, and a Forward never makes CP: nothing to share.
	@Test
	void dullingAForwardForTheCostTakesNothingFromTheCp() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(card("Palom", "Fire", "Forward",
				"《2》, dull 1 active Forward: Choose 1 Forward. Deal it 1000 damage."));
		mw.placeCardInForwardZone(card("Porom", "Water", "Forward", ""));
		mw.p1ForwardPlayedOnTurn.set(0, 0);
		mw.p1ForwardPlayedOnTurn.set(1, 0);
		mw.gameState.getP1Hand().add(card("Any Card", "Ice", "Summon", ""));
		assertTrue(firstButtonUsable(mw, ForwardTarget.CardZone.FORWARD, 0),
				"Porom dulls for the cost; the card in hand makes the 2 CP");
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
