package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JToolTip;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.metal.MetalLookAndFeel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatDarkLaf;

class ToolTipStyleTest {

	private LookAndFeel before;

	@BeforeEach
	void installOnMetal() throws Exception {
		before = UIManager.getLookAndFeel();
		UIManager.setLookAndFeel(new MetalLookAndFeel());
		ToolTipStyle.install();
	}

	/** The defaults are JVM-wide, so the look-and-feel's own come back for the tests after these. */
	@AfterEach
	void restore() throws Exception {
		for (String key : ToolTipStyle.KEYS) UIManager.put(key, null);
		UIManager.setLookAndFeel(before);
	}

	@Test
	void anyComponentsTooltipTakesTheStyle() {
		assertStyled(new JButton("Next").createToolTip());
	}

	// Metal draws a disabled control's tooltip from its own "inactive" keys, in grey; the Mulligan
	// and LB buttons explain why they are disabled, so theirs have to read the same.
	@Test
	void aDisabledComponentsTooltipLooksTheSame() {
		JButton disabled = new JButton("Mulligan");
		disabled.setEnabled(false);
		assertStyled(disabled.createToolTip());
	}

	@Test
	void theStyleOutlivesAChangeOfTheme() throws Exception {
		UIManager.setLookAndFeel(new FlatDarkLaf());
		assertStyled(new JButton("Next").createToolTip());
	}

	// Square corners would put tooltips on FlatLaf's drop-shadow popup, which could show the
	// shadow with no tooltip in it; the rounded ones it uses on Windows 11 stay.
	@Test
	void flatLafKeepsItsRoundedTooltipWindows() throws Exception {
		UIManager.setLookAndFeel(new FlatDarkLaf());
		assertTrue(UIManager.getInt("ToolTip.borderCornerRadius") > 0);
	}

	private static void assertStyled(JToolTip tip) {
		assertEquals(ToolTipStyle.BACKGROUND, new Color(tip.getBackground().getRGB()));
		assertEquals(ToolTipStyle.TEXT, new Color(tip.getForeground().getRGB()));
		List<Border> layers = borderLayers(tip.getBorder());
		assertTrue(layers.get(0) instanceof LineBorder outline && outline.getLineColor().getRed() < 0x30,
				"a dark outline, which holds against pale card art and light dialogs");
		assertTrue(layers.get(1) instanceof LineBorder rule && rule.getLineColor().getRed() > 0x90,
				"a light line inside it, which holds against the dark board");
		assertTrue(layers.get(2) instanceof BevelBorder bevel && bevel.getBevelType() == BevelBorder.RAISED,
				"and the raised bevel inside the frame");
	}

	/** {@code border}'s layers, outermost first, with every compound border opened up. */
	private static List<Border> borderLayers(Border border) {
		List<Border> layers = new ArrayList<>();
		if (border instanceof CompoundBorder cb) {
			layers.addAll(borderLayers(cb.getOutsideBorder()));
			layers.addAll(borderLayers(cb.getInsideBorder()));
		} else {
			layers.add(border);
		}
		return layers;
	}
}
