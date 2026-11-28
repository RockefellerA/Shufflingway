package shufflingway.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import shufflingway.AppLogger;
import shufflingway.AppSettings;

/**
 * Plays {@link Sound}s, honouring the Audio preferences ({@link AppSettings#isAudioEnabled()} and
 * {@link AppSettings#getSfxVolume()}).
 *
 * <p>All clip work runs on one daemon thread. Opening the first line can take a noticeable moment
 * on Windows, and {@link Clip#stop()} may block briefly, so none of it belongs on the EDT; a single
 * thread also serialises access to each clip without locking.
 *
 * <p>Each sound is decoded into one {@link Clip} the first time it is needed (or by
 * {@link #preload()}). A sound asked for again while its clip is still playing is not played a
 * second time: the clip plays on to its end, so several cards broken at once are heard as one
 * break. It cannot be restarted instead. On Windows the clip is a {@code DirectClip}, whose own
 * feeder thread spends the whole of a short clip draining it, and a {@link Clip#stop()} there lets
 * the feeder fall out of its drain and stop the clip again just after the restart's
 * {@link Clip#start()}: the restart and the rest of the first play are both lost. An effect that
 * must overlap itself would want a small pool of clips.
 *
 * <p>A machine with no usable output device, or a missing or undecodable file, leaves that sound
 * silent: the failure is logged once and the sound is not retried for the rest of the session.
 */
public final class SoundEffects {

	private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "sound-effects");
		t.setDaemon(true);
		return t;
	});

	/** Loaded clips, touched only on the audio thread. A sound whose load failed maps to null. */
	private static final Map<Sound, Clip> clips = new EnumMap<>(Sound.class);

	/** When each sound's clip was last started, as {@link System#nanoTime()}; audio thread only. */
	private static final Map<Sound, Long> startedAt = new EnumMap<>(Sound.class);

	private static volatile boolean silenced;

	private SoundEffects() {}

	/**
	 * Turns every sound off for the rest of this JVM, whatever the Audio preferences say. The test
	 * suite calls it before any test runs: tests read the player's real settings, so a test that
	 * logs a chat message or clicks a toggle would otherwise play through their speakers.
	 */
	public static void silence() {
		silenced = true;
	}

	/** Whether {@link #silence()} has been called. */
	static boolean isSilenced() {
		return silenced;
	}

	/**
	 * Loads every sound in the background so the first play does not wait on decoding or on
	 * opening the output line. Does nothing while audio is disabled; a sound is then loaded on its
	 * first play after audio is turned back on.
	 */
	public static void preload() {
		if (silenced || !AppSettings.isAudioEnabled()) return;
		AUDIO.execute(() -> {
			for (Sound s : Sound.values()) clip(s);
		});
	}

	/**
	 * Plays {@code sound} at the current sound-effect volume. Returns immediately; does nothing
	 * when audio is disabled, the volume is 0, or {@link #silence()} has been called. Safe to call
	 * from any thread.
	 */
	public static void play(Sound sound) {
		if (silenced || !AppSettings.isAudioEnabled()) return;
		int volume = AppSettings.getSfxVolume();
		if (volume <= 0) return;
		AUDIO.execute(() -> playNow(sound, volume));
	}

	private static void playNow(Sound sound, int volume) {
		Clip clip = clip(sound);
		if (clip == null) return;
		long now = System.nanoTime();
		if (stillPlaying(clip, startedAt.get(sound), now)) return;
		if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
			gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), gainDb(volume))));
		}
		clip.stop();
		clip.flush();
		clip.setFramePosition(0);
		clip.start();
		startedAt.put(sound, now);
	}

	/**
	 * Whether {@code clip}, last started at {@code startedAt} (null if never), is still playing at
	 * {@code now}. Neither test is enough alone. {@link Clip#isRunning()} turns true only once the
	 * feeder thread has written the first block, a moment after {@link Clip#start()}, so a second
	 * request in that moment would see it false. The clip's length covers that moment, and the
	 * running flag covers the drain after the last frame, which ends with the feeder's own stop.
	 */
	static boolean stillPlaying(Clip clip, Long startedAt, long now) {
		if (clip.isRunning()) return true;
		return startedAt != null && now - startedAt < clip.getMicrosecondLength() * 1000L;
	}

	private static Clip clip(Sound sound) {
		if (clips.containsKey(sound)) return clips.get(sound);
		Clip clip = load(sound);
		clips.put(sound, clip);
		return clip;
	}

	private static Clip load(Sound sound) {
		try (InputStream raw = SoundEffects.class.getResourceAsStream(sound.resourcePath())) {
			if (raw == null) throw new IOException("missing resource " + sound.resourcePath());
			// AudioSystem probes the header with mark/reset, which a jar entry's stream lacks.
			try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
				Clip clip = AudioSystem.getClip();
				clip.open(in);
				return clip;
			}
		} catch (IOException | UnsupportedAudioFileException | LineUnavailableException
				| IllegalArgumentException | SecurityException e) {
			// IllegalArgumentException is what getClip() throws when there is no output device.
			AppLogger.log("Sound effect " + sound + " unavailable", e);
			return null;
		}
	}

	/**
	 * The master-gain setting, in decibels, for a volume step of 1 to
	 * {@link AppSettings#SFX_VOLUME_MAX}. Amplitude follows the square of the step (step 10 is
	 * 0 dB, step 5 is about -12 dB, step 1 is -40 dB), which tracks perceived loudness far better
	 * than a straight line: with linear amplitude the bottom half of the slider barely changes
	 * anything.
	 */
	static float gainDb(int volume) {
		double fraction = (double) volume / AppSettings.SFX_VOLUME_MAX;
		return (float) (40.0 * Math.log10(fraction));
	}
}
