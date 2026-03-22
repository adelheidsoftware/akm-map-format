package akm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Top-level in-memory representation of a parsed .akm file.
 */
public record AkmMap(
		UUID mapId,
		int canvasWidth,
		int canvasHeight,
		String displayName,
		String description,
		List<AkmAuthor> authors,
		Instant createdAt,
		String editorVersion,
		List<AkmTerritory> territories,
		List<AkmEdge> edges,
		List<AkmLayer> layers) {

	public AkmMap {
		if (mapId == null) throw new IllegalArgumentException("Map ID must not be null");
		if (canvasWidth <= 0 || canvasHeight <= 0) throw new IllegalArgumentException("Canvas dimensions must be positive");
		if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Display name must not be null or blank");
		if (authors == null || authors.isEmpty()) throw new IllegalArgumentException("At least one author is required");
		if (territories == null || territories.isEmpty()) throw new IllegalArgumentException("At least one territory is required");
		if (description == null) description = "";
		if (editorVersion == null) editorVersion = "";
		authors = List.copyOf(authors);
		territories = List.copyOf(territories);
		edges = edges == null ? List.of() : List.copyOf(edges);
		layers = layers == null ? List.of() : List.copyOf(layers);
	}

	public int territoryCount() {
		return territories.size();
	}

	public int totalRingCount() {
		return territories.stream().mapToInt(AkmTerritory::totalRingCount).sum();
	}
}
