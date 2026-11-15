package shufflingway;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Turns on {@link ComputerPlayer#showsReasoning} before each test, so a test can assert why the CPU
 * made a play as well as that it did — whatever the developer's own {@code settings.ini} says. Before
 * each test rather than each class, so a test that turns it off leaves the next one as it found it.
 * Applied to every test by autodetection, as {@link shufflingway.audio.SilenceSoundEffects} is.
 */
public class ShowCpuReasoning implements BeforeEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		ComputerPlayer.showsReasoning = true;
	}
}
