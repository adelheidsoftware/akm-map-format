package akm;

import java.util.List;

/**
 * A contiguous landmass within a territory. Ring 0 is the exterior boundary (CCW),
 * rings 1..N are holes for enclaves (CW).
 */
public record AkmRegion(List<AkmRing> rings) {

	public AkmRegion {
		if (rings == null || rings.isEmpty()) throw new IllegalArgumentException("Region must have at least one ring");
		rings = List.copyOf(rings);
	}

	public AkmRing exterior() {
		return rings.getFirst();
	}

	public List<AkmRing> holes() {
		return rings.subList(1, rings.size());
	}

	public boolean hasHoles() {
		return rings.size() > 1;
	}
}
