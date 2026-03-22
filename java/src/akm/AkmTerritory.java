package akm;

import java.util.List;
import java.util.UUID;

/**
 * A territory within an AKM map. Contains one or more regions (multiple regions = exclaves).
 */
public record AkmTerritory(
		UUID territoryId,
		String label,
		float centroidX,
		float centroidY,
		List<AkmRegion> regions) {

	public AkmTerritory {
		if (territoryId == null) throw new IllegalArgumentException("Territory ID must not be null");
		if (label == null) label = "";
		if (regions == null || regions.isEmpty()) throw new IllegalArgumentException("Territory must have at least one region");
		regions = List.copyOf(regions);
	}

	public boolean hasExclaves() {
		return regions.size() > 1;
	}

	public int totalRingCount() {
		return regions.stream().mapToInt(r -> r.rings().size()).sum();
	}
}
