package shufflingway;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Writes an animated PNG (APNG) that loops forever, each frame shown for the same time; for
 * {@link ReadmeScreenshots}, which flips each screenshot between its themes.
 *
 * <p>Lossless and full colour, where a GIF has 256 colours a frame and bands or speckles card art.
 * Every current browser plays it, and a viewer that does not know APNG shows the first frame as an
 * ordinary PNG.
 *
 * <p>ImageIO writes no APNG, but a frame's pixel data is exactly what its PNG writer puts in a
 * PNG's IDAT chunks. So each frame is written as a PNG in memory and that data lifted out. The
 * first frame's stays IDAT, so it is also the still image. Each later frame's goes into an fdAT
 * chunk, and every frame is preceded by an fcTL chunk saying how long it is shown.
 */
final class AnimatedPng {

	private AnimatedPng() {}

	private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

	/**
	 * Writes {@code frames}, which must share a size and image type, to {@code file}, each shown
	 * for {@code delayMs}, looping forever.
	 */
	static void write(List<BufferedImage> frames, int delayMs, File file) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
			out.write(SIGNATURE);
			int sequence = 0;
			byte[] header = null;
			for (int f = 0; f < frames.size(); f++) {
				Png png = encode(frames.get(f));
				if (f == 0) {
					header = png.header();
					chunk(out, "IHDR", header);
					// acTL: the number of frames, and plays (0: forever).
					chunk(out, "acTL", ByteBuffer.allocate(8).putInt(frames.size()).putInt(0).array());
				} else if (!Arrays.equals(header, png.header())) {
					throw new IllegalArgumentException("frame " + f + " differs from the first in size or colour type");
				}
				chunk(out, "fcTL", frameControl(sequence++, frames.get(f), delayMs));
				if (f == 0) {
					chunk(out, "IDAT", png.data());
				} else {
					chunk(out, "fdAT", ByteBuffer.allocate(4 + png.data().length)
							.putInt(sequence++).put(png.data()).array());
				}
			}
			chunk(out, "IEND", new byte[0]);
		}
	}

	/**
	 * fcTL: the frame's sequence number, size, offset (the whole canvas), its delay as a fraction
	 * of a second, and how it is shown — nothing disposed of after it, and drawn over what is there
	 * rather than blended, since every frame is opaque and whole.
	 */
	private static byte[] frameControl(int sequence, BufferedImage frame, int delayMs) {
		return ByteBuffer.allocate(26)
				.putInt(sequence).putInt(frame.getWidth()).putInt(frame.getHeight()).putInt(0).putInt(0)
				.putShort((short) delayMs).putShort((short) 1000)
				.put((byte) 0)   // dispose_op: none
				.put((byte) 0)   // blend_op: source
				.array();
	}

	/** A frame as ImageIO encodes it: the IHDR chunk's contents, and all its IDAT data run together. */
	private record Png(byte[] header, byte[] data) {}

	private static Png encode(BufferedImage frame) throws IOException {
		ByteArrayOutputStream encoded = new ByteArrayOutputStream();
		if (!ImageIO.write(frame, "png", encoded)) throw new IOException("no PNG writer");
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded.toByteArray()));
		in.skipNBytes(SIGNATURE.length);
		byte[] header = null;
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		while (true) {
			int length = in.readInt();
			byte[] type = in.readNBytes(4);
			byte[] body = in.readNBytes(length);
			in.skipNBytes(4);   // CRC
			String name = new String(type, StandardCharsets.US_ASCII);
			if (name.equals("IHDR")) header = body;
			else if (name.equals("IDAT")) data.write(body);
			else if (name.equals("IEND")) break;
		}
		if (header == null) throw new IOException("ImageIO wrote a PNG with no IHDR");
		return new Png(header, data.toByteArray());
	}

	/** A chunk: its length, type, contents, and the CRC of type and contents. */
	private static void chunk(DataOutputStream out, String type, byte[] body) throws IOException {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(body);
		out.writeInt(body.length);
		out.write(name);
		out.write(body);
		out.writeInt((int) crc.getValue());
	}
}
