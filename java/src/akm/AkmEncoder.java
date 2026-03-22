package akm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Encodes an {@link AkmMap} into the .akm binary format.
 */
public final class AkmEncoder {

	private static final byte[] SIGNATURE = {
			(byte) 0x83, 0x41, 0x4B, 0x4D,
			0x0D, 0x0A, 0x1A, 0x0A
	};

	public byte[] encode(AkmMap map) throws IOException, NoSuchAlgorithmException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// File header (16 bytes)
		out.write(SIGNATURE);
		writeU16(out, 1); // version major
		writeU16(out, 0); // version minor
		writeU32(out, 0); // reserved

		// Chunks
		writeChunk(out, AkmField.META, encodeMeta(map));
		writeChunk(out, AkmField.GEOM, encodeGeom(map));
		writeChunk(out, AkmField.ADJC, encodeAdjc(map));
		for (AkmLayer layer : map.layers()) {
			writeChunk(out, AkmField.LAYR, encodeLayer(layer));
		}

		// Checksum
		byte[] contentSoFar = out.toByteArray();
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(contentSoFar);
		byte[] cksmPayload = new byte[1 + hash.length];
		cksmPayload[0] = 0; // algorithm = SHA-256
		System.arraycopy(hash, 0, cksmPayload, 1, hash.length);
		writeChunk(out, AkmField.CKSM, cksmPayload);

		return out.toByteArray();
	}

	private byte[] encodeMeta(AkmMap map) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		writeUuid(buf, map.mapId());
		writeU32(buf, map.canvasWidth());
		writeU32(buf, map.canvasHeight());
		writeString(buf, map.displayName());
		writeString(buf, map.description());
		writeU16(buf, map.authors().size());
		for (AkmAuthor author : map.authors()) {
			writeUuid(buf, author.authorId());
			writeString(buf, author.name());
		}
		writeI64(buf, map.createdAt() != null ? map.createdAt().getEpochSecond() : 0);
		writeString(buf, map.editorVersion());
		return buf.toByteArray();
	}

	private byte[] encodeGeom(AkmMap map) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		writeU32(buf, map.territories().size());
		for (AkmTerritory territory : map.territories()) {
			writeUuid(buf, territory.territoryId());
			writeString(buf, territory.label());
			writeF32(buf, territory.centroidX());
			writeF32(buf, territory.centroidY());
			writeU16(buf, territory.regions().size());
			for (AkmRegion region : territory.regions()) {
				writeU16(buf, region.rings().size());
				for (AkmRing ring : region.rings()) {
					writeU32(buf, ring.pointCount());
					for (int i = 0; i < ring.points().length; i++) {
						writeF32(buf, ring.points()[i]);
					}
				}
			}
		}
		return buf.toByteArray();
	}

	private byte[] encodeAdjc(AkmMap map) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		writeU32(buf, map.edges().size());
		for (AkmEdge edge : map.edges()) {
			writeUuid(buf, edge.sourceId());
			writeUuid(buf, edge.targetId());
			buf.write(edge.bidirectional() ? 0x01 : 0x00);
		}
		return buf.toByteArray();
	}

	private byte[] encodeLayer(AkmLayer layer) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		writeString(buf, layer.name());
		writeU16(buf, layer.zOrder());
		buf.write(layer.blendMode().wireValue());
		buf.write(layer.opacity());
		buf.write(layer.imageFormat().wireValue());
		writeU32(buf, layer.imageData().length);
		buf.write(layer.imageData());
		return buf.toByteArray();
	}

	// --- Chunk envelope ---

	private static void writeChunk(ByteArrayOutputStream out, AkmField field, byte[] payload) throws IOException {
		out.write(field.tag());
		writeU32(out, payload.length);
		out.write(payload);
	}

	// --- Primitive writers ---

	private static void writeU16(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeU32(ByteArrayOutputStream out, int value) throws IOException {
		out.write(ByteBuffer.allocate(4).putInt(value).array());
	}

	private static void writeI64(ByteArrayOutputStream out, long value) throws IOException {
		out.write(ByteBuffer.allocate(8).putLong(value).array());
	}

	private static void writeF32(ByteArrayOutputStream out, float value) throws IOException {
		out.write(ByteBuffer.allocate(4).putFloat(value).array());
	}

	private static void writeUuid(ByteArrayOutputStream out, UUID uuid) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(16);
		buf.putLong(uuid.getMostSignificantBits());
		buf.putLong(uuid.getLeastSignificantBits());
		out.write(buf.array());
	}

	private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
		if (value == null || value.isEmpty()) {
			writeU16(out, 0);
			return;
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeU16(out, bytes.length);
		out.write(bytes);
	}
}
