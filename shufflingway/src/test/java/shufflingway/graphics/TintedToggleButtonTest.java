package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TintedToggleButtonTest {

	private final TintedToggleButton auto = new TintedToggleButton("Auto", Color.GREEN);
	private final TintedToggleButton wait = new TintedToggleButton("Wait", Color.YELLOW);
	private final AtomicInteger autoActions = new AtomicInteger();
	private final AtomicInteger waitActions = new AtomicInteger();

	@BeforeEach
	void groupWithAutoChosen() {
		ButtonGroup group = new ButtonGroup();
		group.add(auto);
		group.add(wait);
		auto.setSelected(true);
		auto.addActionListener(e -> autoActions.incrementAndGet());
		wait.addActionListener(e -> waitActions.incrementAndGet());
	}

	@Test
	void clickingTheChosenButtonDoesNothing() {
		auto.doClick(0);

		assertEquals(0, autoActions.get());
		assertTrue(auto.isSelected());
		assertFalse(wait.isSelected());
		assertFalse(auto.getModel().isPressed(), "a latched button never shows as held down");
	}

	@Test
	void clickingTheOtherButtonChoosesItAndFiresOnce() {
		wait.doClick(0);

		assertEquals(1, waitActions.get());
		assertTrue(wait.isSelected());
		assertFalse(auto.isSelected());
	}

	@Test
	void theReleasedButtonCanBeChosenAgain() {
		wait.doClick(0);
		auto.doClick(0);

		assertEquals(1, autoActions.get());
		assertTrue(auto.isSelected());

		wait.doClick(0);
		wait.doClick(0);
		assertEquals(2, waitActions.get(), "the repeat click on the re-chosen Wait is swallowed");
	}

	@Test
	void anIconLabelledButtonLatchesTheSameWay() {
		Icon icon = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
		TintedToggleButton list = new TintedToggleButton(icon, Color.BLUE);
		TintedToggleButton binder = new TintedToggleButton(icon, Color.BLUE);
		ButtonGroup group = new ButtonGroup();
		group.add(list);
		group.add(binder);
		list.setSelected(true);
		AtomicInteger listActions = new AtomicInteger();
		list.addActionListener(e -> listActions.incrementAndGet());

		list.doClick(0);
		assertEquals(0, listActions.get());
		assertSame(icon, list.getIcon());

		binder.doClick(0);
		list.doClick(0);
		assertEquals(1, listActions.get());
		assertTrue(list.isSelected());
	}

	@Test
	void outsideAGroupItTogglesNormally() {
		TintedToggleButton loose = new TintedToggleButton("Loose", Color.RED);
		loose.doClick(0);
		assertTrue(loose.isSelected());
		loose.doClick(0);
		assertFalse(loose.isSelected());
	}
}
