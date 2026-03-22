package akm;

import java.util.UUID;

/**
 * A map author record.
 */
public record AkmAuthor(UUID authorId, String name) {

	public AkmAuthor {
		if (authorId == null) throw new IllegalArgumentException("Author ID must not be null");
		if (name == null || name.isBlank()) throw new IllegalArgumentException("Author name must not be null or blank");
	}
}
