package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class GenericCpTest {

	@Test
	void eachAmountHasItsOwnCircleUpToTwenty() {
		assertEquals("❶", GenericCp.circle(1));
		assertEquals("❷", GenericCp.circle(2));
		assertEquals("❿", GenericCp.circle(10));
		assertEquals("⓫", GenericCp.circle(11));
		assertEquals("⓴", GenericCp.circle(20));
		assertNull(GenericCp.circle(21));
		assertNull(GenericCp.circle(0));
	}

	@Test
	void withAFontThatDrawsThemTheLabelIsTheCircle() {
		assumeTrue(GenericCp.FAMILY != null, "no font here draws the circled digits");
		assertEquals("❷", GenericCp.label(2));
		assertEquals("any, any, any, any, any, any, any, any, any, any, any, any, any, any, any, any, any, "
				+ "any, any, any, any", GenericCp.label(21), "past twenty, written out");
	}

	@Test
	void htmlSetsEachCircleInThatFont() {
		assumeTrue(GenericCp.FAMILY != null, "no font here draws the circled digits");
		String face = "<font face='" + GenericCp.FAMILY + "'>";
		assertEquals("[Fire, " + face + "❷</font>]: Draw 1 card.",
				GenericCp.html("[Fire, ❷]: Draw 1 card."));
		assertEquals("no circles here", GenericCp.html("no circles here"));
	}
}
