package shufflingway.audio;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Silences {@link SoundEffects} before each test class, so the suite never plays through the
 * speakers. Applied to every test by autodetection: it is listed in
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension}, and
 * {@code junit-platform.properties} turns autodetection on. Both are read by any JUnit launcher,
 * so this holds for a run from the IDE as well as from Maven.
 */
public class SilenceSoundEffects implements BeforeAllCallback {

	@Override
	public void beforeAll(ExtensionContext context) {
		SoundEffects.silence();
	}
}
