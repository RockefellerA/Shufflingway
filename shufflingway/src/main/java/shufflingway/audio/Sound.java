package shufflingway.audio;

/**
 * Every sound effect the app plays, each keyed to a file under {@code /sounds/} on the classpath
 * ({@code src/main/resources/sounds/}). Play one with {@link SoundEffects#play(Sound)}.
 *
 * <p>Files must be PCM WAV (8- or 16-bit, any common rate), which Java Sound decodes without
 * extra libraries. Keep a short tail of silence after the audible part: some mixers drop a clip
 * that ends within a few milliseconds of starting.
 */
public enum Sound {

	/** A short UI click: the Auto / Wait toggle beside the phase buttons. */
	TICK("tick.wav"),

	/** {@link #CHAT_RECEIVE} inverted, a fifth down: a multiplayer chat message you sent. */
	CHAT_SEND("chat_send.wav"),

	/** A two-note blip, a fifth up: a multiplayer chat message from the opponent. */
	CHAT_RECEIVE("chat_receive.wav");

	final String file;

	Sound(String file) {
		this.file = file;
	}

	String resourcePath() {
		return "/sounds/" + file;
	}
}
