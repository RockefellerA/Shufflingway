package shufflingway;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Stops finished games from asking "Play Again?" ({@link MainWindow#neverAskToPlayAgain}) before
 * each test class. The question is a real window on a timer, so every test that ends a game left
 * one open on the desktop after it had finished. Applied to every test by autodetection, as
 * {@link shufflingway.audio.SilenceSoundEffects} is.
 */
public class SuppressPlayAgain implements BeforeAllCallback {

	@Override
	public void beforeAll(ExtensionContext context) {
		MainWindow.neverAskToPlayAgain();
	}
}
