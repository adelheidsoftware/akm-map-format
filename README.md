# AKM Binary Format Specification

**Version:** 1.0
**Extension:** `.akm`
**MIME type:** `application/x-adelkrieg-map`
**Byte order:** Big-endian

## File Structure

```
+-----------------------------------------------------+
| FILE HEADER (fixed 16 bytes)                         |
+-----------------------------------------------------+
| CHUNK: META (required, exactly 1)                    |
| CHUNK: GEOM (required, exactly 1)                    |
| CHUNK: ADJC (required, exactly 1)                    |
| CHUNK: LAYR (optional, 0..N)                         |
| CHUNK: CKSM (required, exactly 1, must be last)      |
+-----------------------------------------------------+
```

Each chunk follows a uniform envelope:

| Field   | Size | Description                                            |
|---------|------|--------------------------------------------------------|
| Tag     | 4B   | ASCII identifier (`META`, `GEOM`, `ADJC`, `LAYR`, `CKSM`) |
| Size    | u32  | Byte length of Payload (excludes Tag and Size)         |
| Payload | ...  | Chunk-specific data                                    |

Decoders MUST skip chunks with unrecognized tags (forward compatibility).

## File Header

| Offset | Size | Field         | Value                                                        |
|--------|------|---------------|--------------------------------------------------------------|
| 0      | 8    | Signature     | `0x83 0x41 0x4B 0x4D 0x0D 0x0A 0x1A 0x0A` (`\x83AKM\r\n\x1a\n`) |
| 8      | 2    | Version Major | `u16` (currently `1`)                                        |
| 10     | 2    | Version Minor | `u16` (currently `0`)                                        |
| 12     | 4    | Reserved      | `0x00000000`                                                 |

The signature uses mixed binary and text bytes (borrowed from the PNG format) to detect corruption from text-mode transfers.

## Primitive Types

| Notation   | Description                            |
|------------|----------------------------------------|
| `u8`       | Unsigned 8-bit integer                 |
| `u16`      | Unsigned 16-bit integer, big-endian    |
| `u32`      | Unsigned 32-bit integer, big-endian    |
| `i64`      | Signed 64-bit integer, big-endian      |
| `f32`      | IEEE 754 single-precision float, big-endian |
| `uuid`     | 16 bytes: MSB long (8B) then LSB long (8B) |
| `utf8[n]`  | UTF-8 encoded string, `n` bytes       |
| `string`   | `u16` byte-length prefix + `utf8[n]`  |

## Chunk: META

Map metadata.

| Field                | Type     | Description                              |
|----------------------|----------|------------------------------------------|
| Map ID               | `uuid`   | Unique map identifier                    |
| Canvas Width         | `u32`    | Coordinate space width (virtual pixels)  |
| Canvas Height        | `u32`    | Coordinate space height (virtual pixels) |
| Display Name         | `string` | Human-readable name                      |
| Description          | `string` | Optional (0-length = empty)              |
| Author Count         | `u16`    | Number of author records (>= 1)          |
| Authors              | ...      | Author records (see below)               |
| Created At           | `i64`    | Unix timestamp in seconds                |
| Editor Version       | `string` | Tool that created the file               |

### Author Record

| Field     | Type     | Description              |
|-----------|----------|--------------------------|
| Author ID | `uuid`   | User identifier          |
| Name      | `string` | Display name at export   |

## Chunk: GEOM

Territory geometry. Each territory is a multi-polygon supporting enclaves and exclaves.

| Field           | Type | Description           |
|-----------------|------|-----------------------|
| Territory Count | `u32`| Number of territories |
| Territories     | ...  | Territory records     |

### Territory Record

| Field        | Type   | Description                                    |
|--------------|--------|------------------------------------------------|
| Territory ID | `uuid` | Unique territory identifier                    |
| Label        | `string` | Optional human-readable label               |
| Centroid X   | `f32`  | Precomputed centroid X (label placement)        |
| Centroid Y   | `f32`  | Precomputed centroid Y                          |
| Region Count | `u16`  | Number of regions (>= 1; > 1 = exclaves)       |
| Regions      | ...    | Region records                                  |

### Region Record

One contiguous landmass. A territory with exclaves (e.g. mainland France + French Guiana) has multiple regions.

| Field      | Type | Description                                           |
|------------|------|-------------------------------------------------------|
| Ring Count | `u16`| Number of rings (>= 1; ring 0 = exterior, 1..N = holes) |
| Rings      | ...  | Ring records                                           |

### Ring Record

A closed polygon. The last point implicitly connects back to the first.

| Field       | Type       | Description                    |
|-------------|------------|--------------------------------|
| Point Count | `u32`      | Number of vertices             |
| Points      | `f32[N*2]` | Alternating X, Y coordinates   |

### Winding Order

| Ring type         | Winding               | Purpose             |
|-------------------|-----------------------|----------------------|
| Exterior (ring 0) | Counter-clockwise    | Defines filled area  |
| Hole (ring 1..N)  | Clockwise            | Cuts out area        |

Follows the SVG/GeoJSON convention. Use even-odd fill rule for correct rendering.

### Enclave/Exclave Model

```
Territory "France":
  Region 0 (mainland):
    Ring 0 (exterior): [...CCW...]
    Ring 1 (hole for Monaco): [...CW...]
    Ring 2 (hole for Andorra): [...CW...]
  Region 1 (French Guiana):
    Ring 0 (exterior): [...CCW...]

Territory "Monaco":
  Region 0:
    Ring 0 (exterior): [...fills France's ring 1 hole...]
```

Arbitrary nesting depth is supported.

## Chunk: ADJC

Adjacency graph defining which territories are connected to each other.

| Field      | Type | Description       |
|------------|------|-------------------|
| Edge Count | `u32`| Number of edges   |
| Edges      | ...  | Edge records      |

### Edge Record

| Field     | Type   | Description                                         |
|-----------|--------|-----------------------------------------------------|
| Source ID | `uuid` | Source territory                                     |
| Target ID | `uuid` | Target territory                                    |
| Flags     | `u8`   | Bit 0: `BIDIRECTIONAL` (0 = one-way, 1 = two-way)  |

Bidirectional edges are stored once with bit 0 set. One-way edges use `0x00`.

## Chunk: LAYR

Visual presentation layer. Multiple `LAYR` chunks are rendered bottom-to-top by z-order.

| Field            | Type      | Description                                          |
|------------------|-----------|------------------------------------------------------|
| Name             | `string`  | Layer identifier                                     |
| Z-Order          | `u16`     | Render order (lower = further back)                  |
| Blend Mode       | `u8`      | `0` = Normal, `1` = Multiply, `2` = Screen, `3` = Overlay |
| Opacity          | `u8`      | 0-255 (maps to 0.0-1.0)                             |
| Image Format     | `u8`      | `0` = PNG, `1` = WebP, `2` = AVIF                   |
| Image Data Length | `u32`    | Byte length of image data                            |
| Image Data       | `bytes`   | Compressed image                                     |

### Predefined Layer Names

| Name          | Purpose            | Typical Z-Order |
|---------------|--------------------|-----------------|
| `water`       | Water              | 5               |
| `land`        | Non-territory land | 10              |
| `connections` | Connection paths   | 15              |
| `territories` | Territory fill     | 20              |
| `borders`     | Border lines       | 25              |
| `labels`      | Name labels        | 30              |

Renderers may ignore layers they don't understand. Layers are purely decorative; maps can be rendered from the geometry alone.

## Chunk: CKSM

Integrity checksum. Must be the last chunk in the file.

| Field     | Type       | Description                                           |
|-----------|------------|-------------------------------------------------------|
| Algorithm | `u8`       | `0` = SHA-256                                         |
| Hash      | `bytes[32]`| SHA-256 of all preceding bytes (signature through end of last chunk before CKSM) |

## SVG Path Conversion

Territory geometry maps directly to SVG `<path>` elements:

```javascript
function territoryToSvgPath(territory) {
  let d = "";
  for (const region of territory.regions) {
    for (const ring of region.rings) {
      d += `M ${ring.points[0].x} ${ring.points[0].y} `;
      for (let i = 1; i < ring.points.length; i++) {
        d += `L ${ring.points[i].x} ${ring.points[i].y} `;
      }
      d += "Z ";
    }
  }
  return d;
}
```

Use `fill-rule="evenodd"` for correct rendering with holes.

## Point-in-Polygon Hit Detection

```javascript
function hitTest(x, y, territory) {
  for (const region of territory.regions) {
    if (isPointInRegion(x, y, region)) return true;
  }
  return false;
}

function isPointInRegion(x, y, region) {
  if (!isPointInRing(x, y, region.rings[0])) return false;
  for (let i = 1; i < region.rings.length; i++) {
    if (isPointInRing(x, y, region.rings[i])) return false;
  }
  return true;
}

function isPointInRing(x, y, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    if ((ring[i].y > y) !== (ring[j].y > y) &&
        x < (ring[j].x - ring[i].x) * (y - ring[i].y) /
            (ring[j].y - ring[i].y) + ring[i].x) {
      inside = !inside;
    }
  }
  return inside;
}
```

## Reference Implementations

| Language   | Directory                  | Status                      |
|------------|----------------------------|-----------------------------|
| Java       | [java/](java/)             | Complete (encoder + decoder) |
| Kotlin     | [kotlin/](kotlin/)         | Complete (encoder + decoder) |
| TypeScript | [typescript/](typescript/) | Complete (encoder + decoder) |

### Java

```java
import akm.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Build a minimal map
var territory = new AkmTerritory(
    UUID.randomUUID(), "island", 50f, 50f,
    List.of(new AkmRegion(List.of(new AkmRing(new float[]{
        0, 0, 100, 0, 100, 100, 0, 100
    }))))
);

var map = new AkmMap(
    UUID.randomUUID(), 200, 200, "Test Map", "",
    List.of(new AkmAuthor(UUID.randomUUID(), "Author")),
    Instant.now(), "example/1.0",
    List.of(territory), List.of(), List.of()
);

// Encode
byte[] bytes = new AkmEncoder().encode(map);

// Decode
AkmMap decoded = new AkmDecoder().decode(bytes);
System.out.println(decoded.territoryCount()); // 1
```

### Kotlin

```kotlin
import akm.*
import java.time.Instant
import java.util.UUID

// Build a minimal map
val territory = AkmTerritory(
    territoryId = UUID.randomUUID(), label = "island",
    centroidX = 50f, centroidY = 50f,
    regions = listOf(AkmRegion(listOf(AkmRing(floatArrayOf(
        0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f
    )))))
)

val map = AkmMap(
    mapId = UUID.randomUUID(), canvasWidth = 200, canvasHeight = 200,
    displayName = "Test Map",
    authors = listOf(AkmAuthor(UUID.randomUUID(), "Author")),
    createdAt = Instant.now(), editorVersion = "example/1.0",
    territories = listOf(territory),
)

// Encode
val bytes: ByteArray = AkmEncoder.encode(map)

// Decode
val decoded: AkmMap = AkmDecoder.decode(bytes)
println(decoded.territories.size) // 1
```

### TypeScript

```typescript
import { encode, decode, type AkmMap, BlendMode, ImageFormat } from "./akm";

// Build a minimal map
const map: AkmMap = {
  mapId: "550e8400-e29b-41d4-a716-446655440000",
  canvasWidth: 200,
  canvasHeight: 200,
  displayName: "Test Map",
  description: "",
  authors: [{ authorId: "660e8400-e29b-41d4-a716-446655440000", name: "Author" }],
  createdAt: Math.floor(Date.now() / 1000),
  editorVersion: "example/1.0",
  territories: [{
    territoryId: "770e8400-e29b-41d4-a716-446655440000",
    label: "island",
    centroidX: 50,
    centroidY: 50,
    regions: [{
      rings: [{
        points: new Float32Array([0, 0, 100, 0, 100, 100, 0, 100]),
      }],
    }],
  }],
  edges: [],
  layers: [],
};

// Encode
const bytes: Uint8Array = await encode(map);

// Decode
const decoded: AkmMap = await decode(bytes);
console.log(decoded.territories.length); // 1
```

## Version History

| Version | Changes               |
|---------|-----------------------|
| 1.0     | Initial specification |

## License

MIT
