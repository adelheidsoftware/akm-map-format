package akm;

public enum ImageFormat {

	PNG((byte) 0, "image/png"),
	WEBP((byte) 1, "image/webp"),
	AVIF((byte) 2, "image/avif");

	private final byte wireValue;
	private final String mimeType;

	ImageFormat(byte wireValue, String mimeType) {
		this.wireValue = wireValue;
		this.mimeType = mimeType;
	}

	public byte wireValue() {
		return wireValue;
	}

	public String mimeType() {
		return mimeType;
	}

	public static ImageFormat fromWireValue(byte value) {
		for (ImageFormat format : values()) {
			if (format.wireValue == value) return format;
		}
		throw new IllegalArgumentException("Unknown image format: " + value);
	}
}
