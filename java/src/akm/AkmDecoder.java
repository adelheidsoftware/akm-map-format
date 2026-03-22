package akm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Decodes .akm binary files into {@link AkmMap} instances.
 * Unknown chunks are silently skipped for forward compatibility.
 */
public final class AkmDecoder {

	private static final byte[] SIGNATURE = {
			(byte) 0x83, 0x41, 0x4B, 0x4D,
			0x0D, 0x0A, 0x1A, 0x0A
	};

	private static final int HEADER_SIZE = 16;

	public AkmMap decode(byte[] data) throws IOException, NoSuchAlgorithmException {
		if (data == null || data.length < HEADER_SIZE) {
			throw new IOException("Data too short to be a valid .akm file");
		}

		ByteArrayInputStream in = new ByteArrayInputStream(data);

		// File header
		byte[] sig = readExact(in, 8);
		if (!Arrays.equals(sig, SIGNATURE)) {
			throw new IOException("Invalid AKM file signature");
		}

		int versionMajor = readU16(in);
		int versionMinor = readU16(in);
		if (versionMajor > 1) {
			throw new IOException("Unsupported AKM version: " + versionMajor + "." + versionMinor);
		}

		readExact(in, 4); // reserved

		// Chunks
		UUID mapId = null;
		int canvasWidth = 0, canvasHeight = 0;
		String displayName = null, description = "", editorVersion = "";
		List<AkmAuthor> authors = null;
		Instant createdAt = null;
		List<AkmTerritory> territories = null;
		List<AkmEdge> edges = null;
		List<AkmLayer> layers = new ArrayList<>();

		ByteArrayOutputStream checksumAccumulator = new ByteArrayOutputStream();
		checksumAccumulator.write(data, 0, HEADER_SIZE);

		boolean foundChecksum = false;

		while (in.available() > 0) {
			byte[] tag = readExact(in, 4);
			byte[] sizeBytes = readExact(in, 4);
			int payloadSize = ByteBuffer.wrap(sizeBytes).getInt();

			if (payloadSize < 0) throw new IOException("Negative chunk size");

			byte[] payload = readExact(in, payloadSize);
			AkmField field = AkmField.of(tag);

			if (field == AkmField.CKSM) {
				if (payload.length < 1) throw new IOException("CKSM chunk too short");
				if (payload[0] != 0) throw new IOException("Unsupported checksum algorithm: " + payload[0]);
				byte[] expectedHash = Arrays.copyOfRange(payload, 1, payload.length);
				byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(checksumAccumulator.toByteArray());
				if (!Arrays.equals(expectedHash, actualHash)) throw new IOException("Checksum verification failed");
				foundChecksum = true;
				break;
			}

			checksumAccumulator.write(tag);
			checksumAccumulator.write(sizeBytes);
			checksumAccumulator.write(payload);

			switch (field) {
				case META -> {
					ByteArrayInputStream p = new ByteArrayInputStream(payload);
					mapId = readUuid(p);
					canvasWidth = readU32(p);
					canvasHeight = readU32(p);
					displayName = readString(p);
					description = readString(p);
					int authorCount = readU16(p);
					authors = new ArrayList<>(authorCount);
					for (int i = 0; i < authorCount; i++) {
						authors.add(new AkmAuthor(readUuid(p), readString(p)));
					}
					createdAt = Instant.ofEpochSecond(readI64(p));
					editorVersion = readString(p);
				}
				case GEOM -> {
					ByteArrayInputStream p = new ByteArrayInputStream(payload);
					int territoryCount = readU32(p);
					territories = new ArrayList<>(territoryCount);
					for (int t = 0; t < territoryCount; t++) {
						territories.add(readTerritory(p));
					}
				}
				case ADJC -> {
					ByteArrayInputStream p = new ByteArrayInputStream(payload);
					int edgeCount = readU32(p);
					edges = new ArrayList<>(edgeCount);
					for (int e = 0; e < edgeCount; e++) {
						UUID sourceId = readUuid(p);
						UUID targetId = readUuid(p);
						boolean bidirectional = (readExact(p, 1)[0] & 0x01) != 0;
						edges.add(new AkmEdge(sourceId, targetId, bidirectional));
					}
				}
				case LAYR -> {
					ByteArrayInputStream p = new ByteArrayInputStream(payload);
					String layerName = readString(p);
					int zOrder = readU16(p);
					BlendMode blendMode = BlendMode.fromWireValue(readExact(p, 1)[0]);
					int opacity = Byte.toUnsignedInt(readExact(p, 1)[0]);
					ImageFormat imageFormat = ImageFormat.fromWireValue(readExact(p, 1)[0]);
					int imageDataLength = readU32(p);
					byte[] imageData = readExact(p, imageDataLength);
					layers.add(new AkmLayer(layerName, zOrder, blendMode, opacity, imageFormat, imageData));
				}
				case UNKNOWN -> { /* skip for forward compatibility */ }
				default -> { }
			}
		}

		if (!foundChecksum) throw new IOException("Missing CKSM chunk");
		if (mapId == null || displayName == null || territories == null || edges == null || authors == null) {
			throw new IOException("Missing required chunks (META, GEOM, ADJC)");
		}

		return new AkmMap(mapId, canvasWidth, canvasHeight, displayName, description,
				authors, createdAt, editorVersion, territories, edges, layers);
	}

	private AkmTerritory readTerritory(ByteArrayInputStream in) throws IOException {
		UUID territoryId = readUuid(in);
		String label = readString(in);
		float centroidX = readF32(in);
		float centroidY = readF32(in);
		int regionCount = readU16(in);
		List<AkmRegion> regions = new ArrayList<>(regionCount);
		for (int r = 0; r < regionCount; r++) {
			int ringCount = readU16(in);
			List<AkmRing> rings = new ArrayList<>(ringCount);
			for (int k = 0; k < ringCount; k++) {
				int pointCount = readU32(in);
				float[] points = new float[pointCount * 2];
				for (int i = 0; i < points.length; i++) points[i] = readF32(in);
				rings.add(new AkmRing(points));
			}
			regions.add(new AkmRegion(rings));
		}
		return new AkmTerritory(territoryId, label, centroidX, centroidY, regions);
	}

	// --- Primitive readers ---

	private static byte[] readExact(ByteArrayInputStream in, int n) throws IOException {
		byte[] buf = in.readNBytes(n);
		if (buf.length != n) throw new IOException("Unexpected end of file (expected " + n + " bytes, got " + buf.length + ")");
		return buf;
	}

	private static int readU16(ByteArrayInputStream in) throws IOException {
		byte[] b = readExact(in, 2);
		return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
	}

	private static int readU32(ByteArrayInputStream in) throws IOException {
		return ByteBuffer.wrap(readExact(in, 4)).getInt();
	}

	private static long readI64(ByteArrayInputStream in) throws IOException {
		return ByteBuffer.wrap(readExact(in, 8)).getLong();
	}

	private static float readF32(ByteArrayInputStream in) throws IOException {
		return ByteBuffer.wrap(readExact(in, 4)).getFloat();
	}

	private static UUID readUuid(ByteArrayInputStream in) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(readExact(in, 16));
		return new UUID(buf.getLong(), buf.getLong());
	}

	private static String readString(ByteArrayInputStream in) throws IOException {
		int len = readU16(in);
		if (len == 0) return "";
		return new String(readExact(in, len), StandardCharsets.UTF_8);
	}
}
