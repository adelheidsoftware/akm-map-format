package akm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Chunk tag identifiers for the AKM binary format. Unknown tags return {@link #UNKNOWN}.
 */
public enum AkmField {

	UNKNOWN("UNKN"),
	META("META"),
	GEOM("GEOM"),
	ADJC("ADJC"),
	LAYR("LAYR"),
	CKSM("CKSM");

	private final byte[] tag;

	AkmField(String tag) {
		this.tag = tag.getBytes(StandardCharsets.UTF_8);
		if (this.tag.length != 4) throw new IllegalArgumentException("Tag must be exactly 4 bytes: " + tag);
	}

	public byte[] tag() {
		return tag.clone();
	}

	public static AkmField of(byte[] tagBytes) {
		for (AkmField field : values()) {
			if (Arrays.equals(field.tag, tagBytes)) return field;
		}
		return UNKNOWN;
	}
}
