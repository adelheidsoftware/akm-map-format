/**
 * AKM Binary Format — Kotlin Reference Implementation
 *
 * Encoder and decoder for the .akm (Adelkrieg Map) binary format.
 * Zero external dependencies — uses only java.io, java.nio, java.security, java.util.
 */
package akm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

// ============================================================================
// Data Model
// ============================================================================

data class AkmMap(
    val mapId: UUID,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val displayName: String,
    val description: String = "",
    val authors: List<AkmAuthor>,
    val createdAt: Instant?,
    val editorVersion: String = "",
    val territories: List<AkmTerritory>,
    val edges: List<AkmEdge> = emptyList(),
    val layers: List<AkmLayer> = emptyList(),
) {
    init {
        require(canvasWidth > 0 && canvasHeight > 0) { "Canvas dimensions must be positive" }
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(authors.isNotEmpty()) { "At least one author is required" }
        require(territories.isNotEmpty()) { "At least one territory is required" }
    }
}

data class AkmAuthor(val authorId: UUID, val name: String) {
    init {
        require(name.isNotBlank()) { "Author name must not be blank" }
    }
}

data class AkmTerritory(
    val territoryId: UUID,
    val label: String = "",
    val centroidX: Float,
    val centroidY: Float,
    val regions: List<AkmRegion>,
) {
    init {
        require(regions.isNotEmpty()) { "Territory must have at least one region" }
    }

    val hasExclaves: Boolean get() = regions.size > 1
    val totalRingCount: Int get() = regions.sumOf { it.rings.size }
}

data class AkmRegion(val rings: List<AkmRing>) {
    init {
        require(rings.isNotEmpty()) { "Region must have at least one ring" }
    }

    val exterior: AkmRing get() = rings.first()
    val holes: List<AkmRing> get() = rings.drop(1)
    val hasHoles: Boolean get() = rings.size > 1
}

/**
 * A closed polygon ring. Points are alternating [x0, y0, x1, y1, ...] floats.
 * CCW (positive signed area) = exterior, CW (negative) = hole.
 */
data class AkmRing(val points: FloatArray) {
    init {
        require(points.size >= 6 && points.size % 2 == 0) {
            "Ring must have at least 3 points (6 floats) and even length"
        }
    }

    val pointCount: Int get() = points.size / 2
    fun x(index: Int): Float = points[index * 2]
    fun y(index: Int): Float = points[index * 2 + 1]

    fun signedArea(): Double {
        var area = 0.0
        val n = pointCount
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += x(i).toDouble() * y(j).toDouble()
            area -= x(j).toDouble() * y(i).toDouble()
        }
        return area / 2.0
    }

    val isExterior: Boolean get() = signedArea() > 0
    val isHole: Boolean get() = signedArea() < 0

    override fun equals(other: Any?): Boolean =
        other is AkmRing && points.contentEquals(other.points)

    override fun hashCode(): Int = points.contentHashCode()
}

data class AkmEdge(val sourceId: UUID, val targetId: UUID, val bidirectional: Boolean) {
    init {
        require(sourceId != targetId) { "Self-referencing edges are not allowed" }
    }
}

data class AkmLayer(
    val name: String,
    val zOrder: Int,
    val blendMode: BlendMode,
    val opacity: Int,
    val imageFormat: ImageFormat,
    val imageData: ByteArray,
) {
    init {
        require(name.isNotBlank()) { "Layer name must not be blank" }
        require(opacity in 0..255) { "Opacity must be 0-255, got: $opacity" }
        require(imageData.isNotEmpty()) { "Image data must not be empty" }
    }

    val opacityFloat: Float get() = opacity / 255f

    override fun equals(other: Any?): Boolean =
        other is AkmLayer && name == other.name && zOrder == other.zOrder &&
            blendMode == other.blendMode && opacity == other.opacity &&
            imageFormat == other.imageFormat && imageData.contentEquals(other.imageData)

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + zOrder
        result = 31 * result + blendMode.hashCode()
        result = 31 * result + opacity
        result = 31 * result + imageFormat.hashCode()
        result = 31 * result + imageData.contentHashCode()
        return result
    }
}

// ============================================================================
// Enums
// ============================================================================

enum class BlendMode(val wireValue: Byte) {
    NORMAL(0), MULTIPLY(1), SCREEN(2), OVERLAY(3);

    companion object {
        fun fromWireValue(value: Byte): BlendMode =
            entries.find { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown blend mode: $value")
    }
}

enum class ImageFormat(val wireValue: Byte, val mimeType: String) {
    PNG(0, "image/png"), WEBP(1, "image/webp"), AVIF(2, "image/avif");

    companion object {
        fun fromWireValue(value: Byte): ImageFormat =
            entries.find { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown image format: $value")
    }
}

enum class AkmField(tag: String) {
    UNKNOWN("UNKN"), META("META"), GEOM("GEOM"), ADJC("ADJC"), LAYR("LAYR"), CKSM("CKSM");

    val tag: ByteArray = tag.toByteArray(StandardCharsets.UTF_8).also {
        require(it.size == 4) { "Tag must be exactly 4 bytes" }
    }

    companion object {
        fun of(tagBytes: ByteArray): AkmField =
            entries.find { it.tag.contentEquals(tagBytes) } ?: UNKNOWN
    }
}

// ============================================================================
// Encoder
// ============================================================================

object AkmEncoder {

    private val SIGNATURE = byteArrayOf(
        0x83.toByte(), 0x41, 0x4B, 0x4D,
        0x0D, 0x0A, 0x1A, 0x0A
    )

    fun encode(map: AkmMap): ByteArray {
        val out = ByteArrayOutputStream()

        // File header (16 bytes)
        out.write(SIGNATURE)
        out.writeU16(1) // version major
        out.writeU16(0) // version minor
        out.writeU32(0) // reserved

        // Chunks
        out.writeChunk(AkmField.META, encodeMeta(map))
        out.writeChunk(AkmField.GEOM, encodeGeom(map))
        out.writeChunk(AkmField.ADJC, encodeAdjc(map))
        for (layer in map.layers) {
            out.writeChunk(AkmField.LAYR, encodeLayer(layer))
        }

        // Checksum
        val contentSoFar = out.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(contentSoFar)
        val cksmPayload = ByteArray(1 + hash.size)
        cksmPayload[0] = 0 // algorithm = SHA-256
        System.arraycopy(hash, 0, cksmPayload, 1, hash.size)
        out.writeChunk(AkmField.CKSM, cksmPayload)

        return out.toByteArray()
    }

    private fun encodeMeta(map: AkmMap): ByteArray = buildPayload {
        writeUuid(map.mapId)
        writeU32(map.canvasWidth)
        writeU32(map.canvasHeight)
        writeString(map.displayName)
        writeString(map.description)
        writeU16(map.authors.size)
        for (author in map.authors) {
            writeUuid(author.authorId)
            writeString(author.name)
        }
        writeI64(map.createdAt?.epochSecond ?: 0L)
        writeString(map.editorVersion)
    }

    private fun encodeGeom(map: AkmMap): ByteArray = buildPayload {
        writeU32(map.territories.size)
        for (territory in map.territories) {
            writeUuid(territory.territoryId)
            writeString(territory.label)
            writeF32(territory.centroidX)
            writeF32(territory.centroidY)
            writeU16(territory.regions.size)
            for (region in territory.regions) {
                writeU16(region.rings.size)
                for (ring in region.rings) {
                    writeU32(ring.pointCount)
                    for (v in ring.points) writeF32(v)
                }
            }
        }
    }

    private fun encodeAdjc(map: AkmMap): ByteArray = buildPayload {
        writeU32(map.edges.size)
        for (edge in map.edges) {
            writeUuid(edge.sourceId)
            writeUuid(edge.targetId)
            write(if (edge.bidirectional) 0x01 else 0x00)
        }
    }

    private fun encodeLayer(layer: AkmLayer): ByteArray = buildPayload {
        writeString(layer.name)
        writeU16(layer.zOrder)
        write(layer.blendMode.wireValue.toInt())
        write(layer.opacity)
        write(layer.imageFormat.wireValue.toInt())
        writeU32(layer.imageData.size)
        write(layer.imageData)
    }

    private inline fun buildPayload(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()
}

// ============================================================================
// Decoder
// ============================================================================

object AkmDecoder {

    private val SIGNATURE = byteArrayOf(
        0x83.toByte(), 0x41, 0x4B, 0x4D,
        0x0D, 0x0A, 0x1A, 0x0A
    )

    private const val HEADER_SIZE = 16

    fun decode(data: ByteArray): AkmMap {
        require(data.size >= HEADER_SIZE) { "Data too short to be a valid .akm file" }

        val input = ByteArrayInputStream(data)

        // File header
        val sig = input.readExact(8)
        if (!sig.contentEquals(SIGNATURE)) throw IOException("Invalid AKM file signature")

        val versionMajor = input.readU16()
        val versionMinor = input.readU16()
        if (versionMajor > 1) throw IOException("Unsupported AKM version: $versionMajor.$versionMinor")

        input.readExact(4) // reserved

        // Chunks
        var mapId: UUID? = null
        var canvasWidth = 0
        var canvasHeight = 0
        var displayName: String? = null
        var description = ""
        var authors: List<AkmAuthor>? = null
        var createdAt: Instant? = null
        var editorVersion = ""
        var territories: List<AkmTerritory>? = null
        var edges: List<AkmEdge>? = null
        val layers = mutableListOf<AkmLayer>()

        val checksumAccumulator = ByteArrayOutputStream()
        checksumAccumulator.write(data, 0, HEADER_SIZE)

        var foundChecksum = false

        while (input.available() > 0) {
            val tag = input.readExact(4)
            val sizeBytes = input.readExact(4)
            val payloadSize = ByteBuffer.wrap(sizeBytes).int
            if (payloadSize < 0) throw IOException("Negative chunk size")

            val payload = input.readExact(payloadSize)
            val field = AkmField.of(tag)

            if (field == AkmField.CKSM) {
                if (payload.isEmpty()) throw IOException("CKSM chunk too short")
                if (payload[0] != 0.toByte()) throw IOException("Unsupported checksum algorithm: ${payload[0]}")
                val expectedHash = payload.copyOfRange(1, payload.size)
                val actualHash = MessageDigest.getInstance("SHA-256").digest(checksumAccumulator.toByteArray())
                if (!expectedHash.contentEquals(actualHash)) throw IOException("Checksum verification failed")
                foundChecksum = true
                break
            }

            checksumAccumulator.write(tag)
            checksumAccumulator.write(sizeBytes)
            checksumAccumulator.write(payload)

            val p = ByteArrayInputStream(payload)

            when (field) {
                AkmField.META -> {
                    mapId = p.readUuid()
                    canvasWidth = p.readU32()
                    canvasHeight = p.readU32()
                    displayName = p.readString()
                    description = p.readString()
                    val authorCount = p.readU16()
                    authors = (0 until authorCount).map { AkmAuthor(p.readUuid(), p.readString()) }
                    createdAt = Instant.ofEpochSecond(p.readI64())
                    editorVersion = p.readString()
                }
                AkmField.GEOM -> {
                    val territoryCount = p.readU32()
                    territories = (0 until territoryCount).map { readTerritory(p) }
                }
                AkmField.ADJC -> {
                    val edgeCount = p.readU32()
                    edges = (0 until edgeCount).map {
                        val sourceId = p.readUuid()
                        val targetId = p.readUuid()
                        val bidirectional = (p.readExact(1)[0].toInt() and 0x01) != 0
                        AkmEdge(sourceId, targetId, bidirectional)
                    }
                }
                AkmField.LAYR -> {
                    val name = p.readString()
                    val zOrder = p.readU16()
                    val blendMode = BlendMode.fromWireValue(p.readExact(1)[0])
                    val opacity = p.readExact(1)[0].toInt() and 0xFF
                    val imageFormat = ImageFormat.fromWireValue(p.readExact(1)[0])
                    val imageDataLength = p.readU32()
                    val imageData = p.readExact(imageDataLength)
                    layers.add(AkmLayer(name, zOrder, blendMode, opacity, imageFormat, imageData))
                }
                AkmField.UNKNOWN -> { /* skip for forward compatibility */ }
                else -> { }
            }
        }

        if (!foundChecksum) throw IOException("Missing CKSM chunk")
        requireNotNull(mapId) { "Missing META chunk" }
        requireNotNull(displayName) { "Missing META chunk" }
        requireNotNull(territories) { "Missing GEOM chunk" }
        requireNotNull(edges) { "Missing ADJC chunk" }
        requireNotNull(authors) { "Missing META chunk" }

        return AkmMap(mapId, canvasWidth, canvasHeight, displayName, description,
            authors, createdAt, editorVersion, territories, edges, layers)
    }

    private fun readTerritory(input: ByteArrayInputStream): AkmTerritory {
        val territoryId = input.readUuid()
        val label = input.readString()
        val centroidX = input.readF32()
        val centroidY = input.readF32()
        val regionCount = input.readU16()
        val regions = (0 until regionCount).map {
            val ringCount = input.readU16()
            val rings = (0 until ringCount).map {
                val pointCount = input.readU32()
                val points = FloatArray(pointCount * 2) { input.readF32() }
                AkmRing(points)
            }
            AkmRegion(rings)
        }
        return AkmTerritory(territoryId, label, centroidX, centroidY, regions)
    }
}

// ============================================================================
// Extension functions for binary I/O
// ============================================================================

private fun ByteArrayInputStream.readExact(n: Int): ByteArray {
    val buf = readNBytes(n)
    if (buf.size != n) throw IOException("Unexpected end of file (expected $n bytes, got ${buf.size})")
    return buf
}

private fun ByteArrayInputStream.readU16(): Int {
    val b = readExact(2)
    return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
}

private fun ByteArrayInputStream.readU32(): Int = ByteBuffer.wrap(readExact(4)).int
private fun ByteArrayInputStream.readI64(): Long = ByteBuffer.wrap(readExact(8)).long
private fun ByteArrayInputStream.readF32(): Float = ByteBuffer.wrap(readExact(4)).float

private fun ByteArrayInputStream.readUuid(): UUID {
    val buf = ByteBuffer.wrap(readExact(16))
    return UUID(buf.long, buf.long)
}

private fun ByteArrayInputStream.readString(): String {
    val len = readU16()
    if (len == 0) return ""
    return String(readExact(len), StandardCharsets.UTF_8)
}

private fun ByteArrayOutputStream.writeU16(value: Int) {
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
}

private fun ByteArrayOutputStream.writeU32(value: Int) {
    write(ByteBuffer.allocate(4).putInt(value).array())
}

private fun ByteArrayOutputStream.writeI64(value: Long) {
    write(ByteBuffer.allocate(8).putLong(value).array())
}

private fun ByteArrayOutputStream.writeF32(value: Float) {
    write(ByteBuffer.allocate(4).putFloat(value).array())
}

private fun ByteArrayOutputStream.writeUuid(uuid: UUID) {
    val buf = ByteBuffer.allocate(16)
    buf.putLong(uuid.mostSignificantBits)
    buf.putLong(uuid.leastSignificantBits)
    write(buf.array())
}

private fun ByteArrayOutputStream.writeString(value: String) {
    if (value.isEmpty()) {
        writeU16(0)
        return
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeU16(bytes.size)
    write(bytes)
}

private fun ByteArrayOutputStream.writeChunk(field: AkmField, payload: ByteArray) {
    write(field.tag)
    writeU32(payload.size)
    write(payload)
}
