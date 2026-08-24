package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import org.junit.jupiter.api.Test;

import shufflingway.graphics.CardAnimation;
import shufflingway.graphics.TraitTab;

/**
 * Covers the trait-tab geometry that the hover tooltip depends on. Everything here is derived
 * from {@link TraitTab#layout} rather than hard-coded pixels, so the assertions hold at any UI
 * scale — {@code CARD_W}/{@code CARD_H} vary with the display.
 */
class TraitTabTest {

	private static final List<CardData.Trait> ALL_GLYPHS = List.of(
			CardData.Trait.HASTE, CardData.Trait.BRAVE,
			CardData.Trait.FIRST_STRIKE, CardData.Trait.CANNOT_BE_BROKEN,
			CardData.Trait.PRIMING);

	/** The centre of the on-screen half of {@code tab} — where a player would actually point. */
	private static int[] visibleCentre(TraitTab.Tab tab, CardState state) {
		Rectangle2D vis = tab.bounds().createIntersection(TraitTab.visibleStrip(state));
		assertFalse(vis.isEmpty(), "each tab should have an on-screen half");
		return new int[]{ (int) Math.round(vis.getCenterX()), (int) Math.round(vis.getCenterY()) };
	}

	@Test
	void traitAtFindsEachTabAtItsVisibleCentreWhenActive() {
		assertTraitAtMatchesLayout(CardState.ACTIVE);
	}

	// Dull cards render rotated, which moves the tabs to the strip above the art and reverses
	// their order along the edge — hit-testing has to follow the same mirroring.
	@Test
	void traitAtFindsEachTabAtItsVisibleCentreWhenDull() {
		assertTraitAtMatchesLayout(CardState.DULL);
	}

	private static void assertTraitAtMatchesLayout(CardState state) {
		List<TraitTab.Tab> tabs = TraitTab.layout(state, ALL_GLYPHS);
		assertEquals(ALL_GLYPHS.size(), tabs.size(), "one tab per drawable trait");
		for (TraitTab.Tab tab : tabs) {
			int[] p = visibleCentre(tab, state);
			assertEquals(tab.trait(), TraitTab.traitAt(state, ALL_GLYPHS, p[0], p[1]),
					"pointing at a tab should report the trait drawn there");
		}
	}

	// Half of every tab is clipped away behind the card art. That half is not visible, so it must
	// not answer to the pointer either — otherwise the card face would sprout invisible hotspots.
	@Test
	void traitAtIgnoresTheClippedHalfOfATab() {
		List<TraitTab.Tab> tabs = TraitTab.layout(CardState.ACTIVE, ALL_GLYPHS);
		Rectangle strip = TraitTab.visibleStrip(CardState.ACTIVE);
		Rectangle2D.Float b = tabs.get(0).bounds();

		int hiddenX = strip.x + strip.width + 1;      // just past the clip, still inside the tab
		int midY    = (int) Math.round(b.getCenterY());
		assertTrue(hiddenX < b.x + b.width, "the tab should straddle the clip edge");
		assertNull(TraitTab.traitAt(CardState.ACTIVE, ALL_GLYPHS, hiddenX, midY),
				"the half hidden behind the art must not be hoverable");
	}

	@Test
	void traitAtReturnsNullBetweenAndBeyondTabs() {
		List<TraitTab.Tab> tabs = TraitTab.layout(CardState.ACTIVE, ALL_GLYPHS);
		Rectangle2D.Float first  = tabs.get(0).bounds();
		Rectangle2D.Float second = tabs.get(1).bounds();

		int gapY = (int) Math.round((first.y + first.height + second.y) / 2f);
		int x    = (int) Math.round(first.getCenterX());
		assertTrue(gapY > first.y + first.height && gapY < second.y, "should land in the gap");
		assertNull(TraitTab.traitAt(CardState.ACTIVE, ALL_GLYPHS, x, gapY), "gap between tabs");

		assertNull(TraitTab.traitAt(CardState.ACTIVE, ALL_GLYPHS, 0, 0), "corner is above the stack");
		assertNull(TraitTab.traitAt(CardState.ACTIVE, List.of(),
				(int) first.getCenterX(), (int) first.getCenterY()), "no traits, no hits");
	}

	// A card only shows tabs for the traits it has, so the stack shifts as traits come and go.
	// Hit-testing is driven by the same list that was rendered, so it has to shift with it.
	@Test
	void traitAtTracksASmallerTraitList() {
		List<CardData.Trait> one = List.of(CardData.Trait.BRAVE);
		List<TraitTab.Tab> tabs = TraitTab.layout(CardState.ACTIVE, one);
		assertEquals(1, tabs.size());
		int[] p = visibleCentre(tabs.get(0), CardState.ACTIVE);
		assertEquals(CardData.Trait.BRAVE, TraitTab.traitAt(CardState.ACTIVE, one, p[0], p[1]));

		// The lone tab is centred, so it sits where neither the first nor last of a full stack would.
		List<TraitTab.Tab> full = TraitTab.layout(CardState.ACTIVE, ALL_GLYPHS);
		assertNull(TraitTab.traitAt(CardState.ACTIVE, one,
				(int) full.get(0).bounds().getCenterX(), (int) full.get(0).bounds().y),
				"the full stack's top slot is empty when the card has one trait");
	}

	// Guards the pairing the tooltip relies on: a trait with a tab always has text to show, and
	// adding a glyph without writing its description would fail here rather than at hover time.
	@Test
	void everyTraitWithAGlyphHasADescriptionAndViceVersa() {
		for (CardData.Trait t : CardData.Trait.values()) {
			for (boolean primed : new boolean[]{ false, true }) {
				if (TraitTab.hasGlyph(t)) {
					assertNotNull(TraitTab.description(t, primed), t + " has a tab, so it needs a description");
					assertFalse(TraitTab.description(t, primed).isBlank(),
							t + " description must not be blank");
				} else {
					assertNull(TraitTab.description(t, primed),
							t + " has no tab, so it should have no description");
				}
			}
		}
		assertNull(TraitTab.description(null, false), "null trait is not a tab");
	}

	// renderTraitTabs and traitAt now share layout(); this pins the drawing half of that contract,
	// since a hit area is only correct if the tab was painted where the layout said.
	@Test
	void renderTraitTabsPaintsOnlyInsideTheVisibleStrip() {
		BufferedImage canvas = new BufferedImage(
				CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
		TraitTab.renderTraitTabs(canvas, CardState.ACTIVE, ALL_GLYPHS, false);

		Rectangle strip = TraitTab.visibleStrip(CardState.ACTIVE);
		boolean paintedInStrip = false;
		for (int y = 0; y < canvas.getHeight(); y++) {
			for (int x = 0; x < canvas.getWidth(); x++) {
				boolean opaque = (canvas.getRGB(x, y) >>> 24) != 0;
				if (!opaque) continue;
				assertTrue(strip.contains(x, y), "tab pixel painted outside the clip at " + x + "," + y);
				paintedInStrip = true;
			}
		}
		assertTrue(paintedInStrip, "every drawable trait should have drawn something");
	}

	/**
	 * The widest channel spread over every painted pixel. The tab chrome and the white line art are
	 * all neutral greys, and antialiasing blends greys into greys — so any real chroma on the canvas
	 * came from a glyph's coloured accent and nothing else.
	 */
	private static int maxChroma(BufferedImage canvas) {
		int worst = 0;
		for (int y = 0; y < canvas.getHeight(); y++) {
			for (int x = 0; x < canvas.getWidth(); x++) {
				int argb = canvas.getRGB(x, y);
				if ((argb >>> 24) == 0) continue;
				int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
				worst = Math.max(worst, Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)));
			}
		}
		return worst;
	}

	// The Priming tab is a capability before it is a fact: it shows as soon as a card can prime, and
	// only takes on the green fill and orange halo once a card has actually been stacked on top.
	@Test
	void primingGlyphStaysColourlessUntilTheCardIsActuallyPrimed() {
		List<CardData.Trait> priming = List.of(CardData.Trait.PRIMING);

		BufferedImage capable = new BufferedImage(
				CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
		TraitTab.renderTraitTabs(capable, CardState.ACTIVE, priming, false);
		assertTrue(maxChroma(capable) < 30,
				"an unprimed card's tab is grey chrome and white line art — no colour");

		BufferedImage primed = new BufferedImage(
				CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
		TraitTab.renderTraitTabs(primed, CardState.ACTIVE, priming, true);
		assertTrue(maxChroma(primed) > 60, "priming lights the glyph up");
	}

	// The other glyphs carry their own colour and must not answer to the priming flag at all.
	@Test
	void theOtherGlyphsRenderTheSameWhicheverWayPrimedReads() {
		for (CardData.Trait t : List.of(CardData.Trait.HASTE, CardData.Trait.BRAVE,
				CardData.Trait.FIRST_STRIKE, CardData.Trait.CANNOT_BE_BROKEN)) {
			BufferedImage a = new BufferedImage(
					CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
			BufferedImage b = new BufferedImage(
					CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
			TraitTab.renderTraitTabs(a, CardState.ACTIVE, List.of(t), false);
			TraitTab.renderTraitTabs(b, CardState.ACTIVE, List.of(t), true);
			for (int y = 0; y < a.getHeight(); y++)
				for (int x = 0; x < a.getWidth(); x++)
					assertEquals(a.getRGB(x, y), b.getRGB(x, y), t + " should ignore the primed flag");
		}
	}

	@Test
	void renderTraitTabsDrawsNothingWithoutDrawableTraits() {
		BufferedImage canvas = new BufferedImage(
				CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
		TraitTab.renderTraitTabs(canvas, CardState.ACTIVE, List.of(CardData.Trait.WARP), false);
		for (int y = 0; y < canvas.getHeight(); y++)
			for (int x = 0; x < canvas.getWidth(); x++)
				assertEquals(0, canvas.getRGB(x, y) >>> 24, "WARP has no glyph — nothing to draw");
	}

	// ---- the slot tooltip that sits on top of the geometry above --------------------------

	/** A field slot the size the board gives it, carrying a card-canvas icon like the real ones. */
	private static JLabel slotWithIcon(int labelW, int labelH) {
		JLabel slot = new JLabel("", SwingConstants.CENTER);
		slot.setIcon(new ImageIcon(new BufferedImage(
				CardAnimation.CARD_H, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB)));
		slot.setSize(labelW, labelH);
		return slot;
	}

	@Test
	void slotTooltipNamesAndExplainsTheTraitUnderThePointer() {
		MainWindow mw = new MainWindow();
		JLabel slot = slotWithIcon(CardAnimation.CARD_H, CardAnimation.CARD_H);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, ALL_GLYPHS, false, Map.of());

		for (TraitTab.Tab tab : TraitTab.layout(CardState.ACTIVE, ALL_GLYPHS)) {
			int[] p = visibleCentre(tab, CardState.ACTIVE);
			String tip = mw.fieldSlotTooltipAt(slot, p[0], p[1]);
			assertNotNull(tip, "hovering a tab should produce a tooltip");
			assertTrue(tip.contains(tab.trait().displayName()), "tooltip should name " + tab.trait());
			assertTrue(tip.contains(TraitTab.description(tab.trait(), false)),
					"tooltip should explain " + tab.trait());
		}
	}

	// The icon is centred in the slot, so a label roomier than the card shifts the tabs. Hit
	// testing corrects for that offset; without it the tooltip would answer at the wrong spot.
	@Test
	void slotTooltipCorrectsForTheIconBeingCentredInARoomierLabel() {
		MainWindow mw = new MainWindow();
		int pad = 40;
		JLabel slot = slotWithIcon(CardAnimation.CARD_H + pad, CardAnimation.CARD_H + pad);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, ALL_GLYPHS, false, Map.of());

		TraitTab.Tab tab = TraitTab.layout(CardState.ACTIVE, ALL_GLYPHS).get(0);
		int[] canvasPt = visibleCentre(tab, CardState.ACTIVE);
		String shifted = mw.fieldSlotTooltipAt(slot, canvasPt[0] + pad / 2, canvasPt[1] + pad / 2);
		assertNotNull(shifted, "the shifted point is where the tab is actually drawn");
		assertTrue(shifted.contains(tab.trait().displayName()));

		// The unshifted point is off the tab once the icon is inset, so it must not report a trait.
		assertNull(mw.fieldSlotTooltipAt(slot, canvasPt[0], canvasPt[1]),
				"raw label coordinates should not hit the tab in a roomier label");
	}

	@Test
	void slotTooltipFallsBackToCountersAwayFromTabs() {
		MainWindow mw = new MainWindow();
		JLabel slot = slotWithIcon(CardAnimation.CARD_H, CardAnimation.CARD_H);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, ALL_GLYPHS, false, Map.of("Warp", 2));

		int offTabX = CardAnimation.CARD_H - 5;   // deep in the card art, past every tab
		int offTabY = CardAnimation.CARD_H / 2;
		String tip = mw.fieldSlotTooltipAt(slot, offTabX, offTabY);
		assertNotNull(tip);
		assertTrue(tip.contains("Warp"), "should fall back to the counter tooltip");
		assertFalse(tip.contains("Brave"), "no trait should be reported off a tab");
	}

	@Test
	void slotTooltipIsAbsentWithNoTraitsAndNoCounters() {
		MainWindow mw = new MainWindow();
		JLabel slot = slotWithIcon(CardAnimation.CARD_H, CardAnimation.CARD_H);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, List.of(), false, Map.of());
		assertNull(mw.fieldSlotTooltipAt(slot, CardAnimation.CARD_H / 2, CardAnimation.CARD_H / 2));
		assertNull(slot.getToolTipText(), "a plain card should show no tooltip at all");
	}

	// The Priming tab is the one glyph whose tooltip depends on more than which trait was hit, so
	// the primed flag has to reach the hover text the same way it reaches the drawing.
	@Test
	void primingTooltipSwitchesFromCapabilityToState() {
		MainWindow mw = new MainWindow();
		List<CardData.Trait> priming = List.of(CardData.Trait.PRIMING);
		JLabel slot = slotWithIcon(CardAnimation.CARD_H, CardAnimation.CARD_H);
		int[] p = visibleCentre(TraitTab.layout(CardState.ACTIVE, priming).get(0), CardState.ACTIVE);

		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, priming, false, Map.of());
		String capable = mw.fieldSlotTooltipAt(slot, p[0], p[1]);
		assertTrue(capable.contains("Priming"), "unprimed, the tab is named for the trait");
		assertTrue(capable.contains(TraitTab.description(CardData.Trait.PRIMING, false)));

		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, priming, true, Map.of());
		String primed = mw.fieldSlotTooltipAt(slot, p[0], p[1]);
		assertTrue(primed.contains("Primed"), "once primed, the tab is named for the state");
		assertTrue(primed.contains(TraitTab.description(CardData.Trait.PRIMING, true)));
		assertNotEquals(capable, primed, "the two states must not read identically");
	}

	// Slots are re-rendered constantly; the tooltip has to follow the card's current traits and
	// state rather than whatever it had when the listener was first installed.
	@Test
	void slotTooltipFollowsRerendersOfTheSameLabel() {
		MainWindow mw = new MainWindow();
		JLabel slot = slotWithIcon(CardAnimation.CARD_H, CardAnimation.CARD_H);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, ALL_GLYPHS, false, Map.of());

		List<CardData.Trait> justHaste = List.of(CardData.Trait.HASTE);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, justHaste, false, Map.of());

		TraitTab.Tab tab = TraitTab.layout(CardState.ACTIVE, justHaste).get(0);
		int[] p = visibleCentre(tab, CardState.ACTIVE);
		String tip = mw.fieldSlotTooltipAt(slot, p[0], p[1]);
		assertNotNull(tip);
		assertTrue(tip.contains("Haste"), "the re-rendered trait list should be the one consulted");
	}
}
