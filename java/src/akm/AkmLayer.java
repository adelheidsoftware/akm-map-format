package akm;

/**
 * A visual presentation layer. Multiple layers are composited bottom-to-top by z-order.
 */
public record AkmLayer(
		String name,
		int zOrder,
		BlendMode blendMode,
		int opacity,
		ImageFormat imageFormat,
		byte[] imageData) {

	public AkmLayer {
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Layer name must not be null or blank");
		if (opacity < 0 || opacity > 255) throw new IllegalArgumentException("Opacity must be 0-255, got: " + opacity);
		if (blendMode == null) throw new IllegalArgumentException("Blend mode must not be null");
		if (imageFormat == null) throw new IllegalArgumentException("Image format must not be null");
		if (imageData == null || imageData.length == 0) throw new IllegalArgumentException("Image data must not be null or empty");
	}

	public float opacityFloat() {
		return opacity / 255.0f;
	}
}
