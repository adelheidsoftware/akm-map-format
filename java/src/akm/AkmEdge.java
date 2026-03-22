package akm;

import java.util.UUID;

/**
 * An adjacency edge between two territories.
 */
public record AkmEdge(UUID sourceId, UUID targetId, boolean bidirectional) {

	public AkmEdge {
		if (sourceId == null || targetId == null) throw new IllegalArgumentException("Edge source and target IDs must not be null");
		if (sourceId.equals(targetId)) throw new IllegalArgumentException("Self-referencing edges are not allowed");
	}
}
