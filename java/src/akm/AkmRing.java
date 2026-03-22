package akm;

/**
 * A closed polygon ring. Points are stored as alternating [x0, y0, x1, y1, ...] floats.
 * The ring is implicitly closed (last point connects to first).
 *
 * Winding: CCW (positive signed area) = exterior, CW (negative) = hole.
 */
public record AkmRing(float[] points) {

	public AkmRing {
		if (points == null || points.length < 6 || points.length % 2 != 0)
			throw new IllegalArgumentException("Ring must have at least 3 points (6 floats) and even length");
	}

	public int pointCount() {
		return points.length / 2;
	}

	public float x(int index) {
		return points[index * 2];
	}

	public float y(int index) {
		return points[index * 2 + 1];
	}

	public double signedArea() {
		double area = 0;
		int n = pointCount();
		for (int i = 0; i < n; i++) {
			int j = (i + 1) % n;
			area += (double) x(i) * y(j);
			area -= (double) x(j) * y(i);
		}
		return area / 2.0;
	}

	public boolean isExterior() {
		return signedArea() > 0;
	}

	public boolean isHole() {
		return signedArea() < 0;
	}
}
