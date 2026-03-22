package akm;

public enum BlendMode {

	NORMAL((byte) 0),
	MULTIPLY((byte) 1),
	SCREEN((byte) 2),
	OVERLAY((byte) 3);

	private final byte wireValue;

	BlendMode(byte wireValue) {
		this.wireValue = wireValue;
	}

	public byte wireValue() {
		return wireValue;
	}

	public static BlendMode fromWireValue(byte value) {
		for (BlendMode mode : values()) {
			if (mode.wireValue == value) return mode;
		}
		throw new IllegalArgumentException("Unknown blend mode: " + value);
	}
}
