package shufflingway.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

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
