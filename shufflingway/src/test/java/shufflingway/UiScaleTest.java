package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Which resolutions a screen is offered, and which of them open maximized rather than as a window.
 * Screen sizes are in Java's units, so a screen above 100% Windows scaling measures smaller than
 * its pixels: 4K at 150% is 2560x1440.
 */
class UiScaleTest {

    @Test
    void aStandardScreenMaximizesItsOwnSizeAndWindowsTheSmallerOnes() {
        assertEquals(List.of("1280x720", "1920x1080", "2560x1440"), UiScale.resolutionsFor(2560, 1440));
        assertTrue(UiScale.opensMaximized("2560x1440", 2560, 1440));
        assertFalse(UiScale.opensMaximized("1920x1080", 2560, 1440));
        assertFalse(UiScale.opensMaximized("1280x720", 2560, 1440));

        assertEquals(List.of("1280x720", "1920x1080"), UiScale.resolutionsFor(1920, 1080));
        assertTrue(UiScale.opensMaximized("1920x1080", 1920, 1080));
        assertFalse(UiScale.opensMaximized("1280x720", 1920, 1080));

        assertEquals(List.of("1280x720"), UiScale.resolutionsFor(1280, 720));
        assertTrue(UiScale.opensMaximized("1280x720", 1280, 720));
    }

    @Test
    void aScreenBetweenSizesMaximizesTheLargestItFits() {
        // A 1080p laptop at 125% scaling, which Java measures as 1536x864, and a 1366x768 one.
        assertEquals(List.of("1280x720"), UiScale.resolutionsFor(1536, 864));
        assertTrue(UiScale.opensMaximized("1280x720", 1536, 864));
        assertTrue(UiScale.opensMaximized("1280x720", 1366, 768));
        // 16:10 and ultrawide.
        assertTrue(UiScale.opensMaximized("1920x1080", 1920, 1200));
        assertTrue(UiScale.opensMaximized("2560x1440", 2560, 1600));
        assertTrue(UiScale.opensMaximized("2560x1440", 3440, 1440));
    }

    @Test
    void aScreenWithRoomForMoreThanEveryChoiceGetsTheLargestAsAWindow() {
        // 4K at 100% scaling.
        assertEquals(List.of("1280x720", "1920x1080", "2560x1440"), UiScale.resolutionsFor(3840, 2160));
        assertFalse(UiScale.opensMaximized("2560x1440", 3840, 2160));
        // 4K at 125% is 3072x1728: no room for 3840x2160, so 2560x1440 fills it.
        assertTrue(UiScale.opensMaximized("2560x1440", 3072, 1728));
    }

    @Test
    void aScreenSmallerThanEveryChoiceIsStillOfferedTheSmallestMaximized() {
        assertEquals(List.of("1280x720"), UiScale.resolutionsFor(1024, 768));
        assertTrue(UiScale.opensMaximized("1280x720", 1024, 768));
    }

    @Test
    void aSavedSizeBiggerThanTheScreenItOpensOnMaximizes() {
        // Chosen on a 1440p monitor, then opened on a 1080p one.
        assertTrue(UiScale.opensMaximized("2560x1440", 1920, 1080));
    }
}
