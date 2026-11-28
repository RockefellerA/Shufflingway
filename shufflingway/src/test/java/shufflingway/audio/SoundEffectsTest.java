package shufflingway.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

import org.junit.jupiter.api.Test;

import shufflingway.AppSettings;

class SoundEffectsTest {

	/** {@link SilenceSoundEffects} reached this class without being named on it. */
	@Test
	void theTestSuiteIsSilenced() {
		assertTrue(SoundEffects.isSilenced(),
				"SilenceSoundEffects did not run: check junit-platform.properties and META-INF/services");
	}

	@Test
	void fullVolumeIsUnityGain() {
		assertEquals(0f, SoundEffects.gainDb(AppSettings.SFX_VOLUME_MAX), 1e-4f);
	}

	@Test
	void gainFallsWithEveryStepDown() {
		for (int v = AppSettings.SFX_VOLUME_MAX; v > 1; v--)
			assertTrue(SoundEffects.gainDb(v - 1) < SoundEffects.gainDb(v), "step " + (v - 1));
	}

	@Test
	void lowestAudibleStepIsQuietButNotMuted() {
		assertEquals(-40f, SoundEffects.gainDb(1), 1e-3f);
	}

	// =============================================================================================
	// A request while the clip is still playing
	// =============================================================================================

	private static final long MS = 1_000_000L;

	/** A clip 310 ms long, as {@link Sound#CARD_BREAK} is, that is or is not running. */
	private static Clip clip(boolean running) {
		Clip clip = mock(Clip.class);
		when(clip.isRunning()).thenReturn(running);
		when(clip.getMicrosecondLength()).thenReturn(310_000L);
		return clip;
	}

	@Test
	void aClipNeverStartedIsFree() {
		assertFalse(SoundEffects.stillPlaying(clip(false), null, 5_000 * MS));
	}

	/** A second break in the same instant as the first, before the feeder has said the clip is running. */
	@Test
	void aClipJustStartedIsBusyBeforeItReportsRunning() {
		assertTrue(SoundEffects.stillPlaying(clip(false), 1_000 * MS, 1_002 * MS));
	}

	/** Past the clip's length the feeder is still draining the last frames, and says so. */
	@Test
	void aClipStillDrainingIsBusy() {
		assertTrue(SoundEffects.stillPlaying(clip(true), 1_000 * MS, 1_330 * MS));
	}

	@Test
	void aClipThatHasPlayedOutIsFree() {
		assertFalse(SoundEffects.stillPlaying(clip(false), 1_000 * MS, 1_400 * MS));
	}

	/** Decoding needs no output device, so this holds on a headless build machine too. */
	@Test
	void everySoundIsOnTheClasspathAndDecodesAsPcm() throws Exception {
		for (Sound s : Sound.values()) {
			try (InputStream raw = SoundEffects.class.getResourceAsStream(s.resourcePath())) {
				assertNotNull(raw, s + " missing at " + s.resourcePath());
				try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw))) {
					AudioFormat.Encoding enc = in.getFormat().getEncoding();
					assertTrue(enc == AudioFormat.Encoding.PCM_SIGNED || enc == AudioFormat.Encoding.PCM_UNSIGNED,
							s + " is " + enc);
					assertTrue(in.getFrameLength() > 0, s + " is empty");
				}
			}
		}
	}
}
