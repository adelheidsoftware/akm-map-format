/**
 * AKM Binary Format — TypeScript Reference Implementation
 *
 * Encoder and decoder for the .akm (Adelkrieg Map) binary format.
 * Uses DataView for big-endian I/O and Web Crypto API for SHA-256.
 * Works in both Node.js and browsers with no external dependencies.
 */

// ============================================================================
// Types
// ============================================================================

export enum BlendMode {
	Normal = 0,
	Multiply = 1,
	Screen = 2,
	Overlay = 3,
}

export enum ImageFormat {
	PNG = 0,
	WebP = 1,
	AVIF = 2,
}

export interface AkmAuthor {
	readonly authorId: string; // UUID string
	readonly name: string;
}

export interface AkmRing {
	/** Alternating [x0, y0, x1, y1, ...] coordinates. Length must be even and >= 6. */
	readonly points: Float32Array;
}

export interface AkmRegion {
	/** Ring 0 = exterior (CCW), rings 1..N = holes (CW). */
	readonly rings: readonly AkmRing[];
}

export interface AkmTerritory {
	readonly territoryId: string; // UUID string
	readonly label: string;
	readonly centroidX: number;
	readonly centroidY: number;
	/** Multiple regions = exclaves. */
	readonly regions: readonly AkmRegion[];
}

export interface AkmEdge {
	readonly sourceId: string; // UUID string
	readonly targetId: string; // UUID string
	readonly bidirectional: boolean;
}

export interface AkmLayer {
	readonly name: string;
	readonly zOrder: number;
	readonly blendMode: BlendMode;
	/** 0-255, maps to 0.0-1.0 alpha. */
	readonly opacity: number;
	readonly imageFormat: ImageFormat;
	readonly imageData: Uint8Array;
}

export interface AkmMap {
	readonly mapId: string; // UUID string
	readonly canvasWidth: number;
	readonly canvasHeight: number;
	readonly displayName: string;
	readonly description: string;
	readonly authors: readonly AkmAuthor[];
	/** Unix timestamp in seconds. */
	readonly createdAt: number;
	readonly editorVersion: string;
	readonly territories: readonly AkmTerritory[];
	readonly edges: readonly AkmEdge[];
	readonly layers: readonly AkmLayer[];
}

// ============================================================================
// Constants
// ============================================================================

const SIGNATURE = new Uint8Array([0x83, 0x41, 0x4b, 0x4d, 0x0d, 0x0a, 0x1a, 0x0a]);
const HEADER_SIZE = 16;

const CHUNK_META = encodeAscii("META");
const CHUNK_GEOM = encodeAscii("GEOM");
const CHUNK_ADJC = encodeAscii("ADJC");
const CHUNK_LAYR = encodeAscii("LAYR");
const CHUNK_CKSM = encodeAscii("CKSM");

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder("utf-8");

// ============================================================================
// Encoder
// ============================================================================

export async function encode(map: AkmMap): Promise<Uint8Array> {
	const chunks: Uint8Array[] = [];

	// File header (16 bytes)
	const header = new Uint8Array(HEADER_SIZE);
	header.set(SIGNATURE);
	const hv = new DataView(header.buffer);
	hv.setUint16(8, 1); // version major
	hv.setUint16(10, 0); // version minor
	// bytes 12-15 reserved (already 0)
	chunks.push(header);

	// Chunks
	chunks.push(writeChunk(CHUNK_META, encodeMeta(map)));
	chunks.push(writeChunk(CHUNK_GEOM, encodeGeom(map)));
	chunks.push(writeChunk(CHUNK_ADJC, encodeAdjc(map)));
	for (const layer of map.layers) {
		chunks.push(writeChunk(CHUNK_LAYR, encodeLayer(layer)));
	}

	// Checksum
	const contentSoFar = concat(chunks);
	const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", contentSoFar.buffer as ArrayBuffer));
	const cksmPayload = new Uint8Array(1 + hash.length);
	cksmPayload[0] = 0; // algorithm = SHA-256
	cksmPayload.set(hash, 1);
	chunks.push(writeChunk(CHUNK_CKSM, cksmPayload));

	return concat(chunks);
}

function encodeMeta(map: AkmMap): Uint8Array {
	const w = new BinaryWriter();
	w.uuid(map.mapId);
	w.u32(map.canvasWidth);
	w.u32(map.canvasHeight);
	w.string(map.displayName);
	w.string(map.description);
	w.u16(map.authors.length);
	for (const author of map.authors) {
		w.uuid(author.authorId);
		w.string(author.name);
	}
	w.i64(map.createdAt);
	w.string(map.editorVersion);
	return w.toBytes();
}

function encodeGeom(map: AkmMap): Uint8Array {
	const w = new BinaryWriter();
	w.u32(map.territories.length);
	for (const territory of map.territories) {
		w.uuid(territory.territoryId);
		w.string(territory.label);
		w.f32(territory.centroidX);
		w.f32(territory.centroidY);
		w.u16(territory.regions.length);
		for (const region of territory.regions) {
			w.u16(region.rings.length);
			for (const ring of region.rings) {
				const pointCount = ring.points.length / 2;
				w.u32(pointCount);
				for (let i = 0; i < ring.points.length; i++) {
					w.f32(ring.points[i]);
				}
			}
		}
	}
	return w.toBytes();
}

function encodeAdjc(map: AkmMap): Uint8Array {
	const w = new BinaryWriter();
	w.u32(map.edges.length);
	for (const edge of map.edges) {
		w.uuid(edge.sourceId);
		w.uuid(edge.targetId);
		w.u8(edge.bidirectional ? 0x01 : 0x00);
	}
	return w.toBytes();
}

function encodeLayer(layer: AkmLayer): Uint8Array {
	const w = new BinaryWriter();
	w.string(layer.name);
	w.u16(layer.zOrder);
	w.u8(layer.blendMode);
	w.u8(layer.opacity);
	w.u8(layer.imageFormat);
	w.u32(layer.imageData.length);
	w.bytes(layer.imageData);
	return w.toBytes();
}

function writeChunk(tag: Uint8Array, payload: Uint8Array): Uint8Array {
	const chunk = new Uint8Array(8 + payload.length);
	chunk.set(tag, 0);
	new DataView(chunk.buffer).setUint32(4, payload.length);
	chunk.set(payload, 8);
	return chunk;
}

// ============================================================================
// Decoder
// ============================================================================

export async function decode(data: Uint8Array): Promise<AkmMap> {
	if (data.length < HEADER_SIZE) {
		throw new Error("Data too short to be a valid .akm file");
	}

	const r = new BinaryReader(data);

	// File header
	const sig = r.bytes(8);
	if (!arraysEqual(sig, SIGNATURE)) {
		throw new Error("Invalid AKM file signature");
	}

	const versionMajor = r.u16();
	const versionMinor = r.u16();
	if (versionMajor > 1) {
		throw new Error(`Unsupported AKM version: ${versionMajor}.${versionMinor}`);
	}

	r.skip(4); // reserved

	// Chunks
	let mapId: string | null = null;
	let canvasWidth = 0;
	let canvasHeight = 0;
	let displayName: string | null = null;
	let description = "";
	let authors: AkmAuthor[] | null = null;
	let createdAt = 0;
	let editorVersion = "";
	let territories: AkmTerritory[] | null = null;
	let edges: AkmEdge[] | null = null;
	const layers: AkmLayer[] = [];

	let foundChecksum = false;

	while (r.remaining() > 0) {
		const tagBytes = r.bytes(4);
		const payloadSize = r.u32();
		if (payloadSize < 0) throw new Error("Negative chunk size");
		const payload = r.bytes(payloadSize);

		const tag = decodeAscii(tagBytes);

		if (tag === "CKSM") {
			if (payload.length < 1) throw new Error("CKSM chunk too short");
			if (payload[0] !== 0) throw new Error(`Unsupported checksum algorithm: ${payload[0]}`);
			const expectedHash = payload.slice(1);
			const contentBeforeCksm = data.slice(0, r.offset - 8 - payloadSize);
			const actualHash = new Uint8Array(await crypto.subtle.digest("SHA-256", contentBeforeCksm.buffer as ArrayBuffer));
			if (!arraysEqual(expectedHash, actualHash)) throw new Error("Checksum verification failed");
			foundChecksum = true;
			break;
		}

		const p = new BinaryReader(payload);

		switch (tag) {
			case "META": {
				mapId = p.uuid();
				canvasWidth = p.u32();
				canvasHeight = p.u32();
				displayName = p.string();
				description = p.string();
				const authorCount = p.u16();
				authors = [];
				for (let i = 0; i < authorCount; i++) {
					authors.push({ authorId: p.uuid(), name: p.string() });
				}
				createdAt = p.i64();
				editorVersion = p.string();
				break;
			}
			case "GEOM": {
				const territoryCount = p.u32();
				territories = [];
				for (let t = 0; t < territoryCount; t++) {
					territories.push(readTerritory(p));
				}
				break;
			}
			case "ADJC": {
				const edgeCount = p.u32();
				edges = [];
				for (let e = 0; e < edgeCount; e++) {
					const sourceId = p.uuid();
					const targetId = p.uuid();
					const bidirectional = (p.u8() & 0x01) !== 0;
					edges.push({ sourceId, targetId, bidirectional });
				}
				break;
			}
			case "LAYR": {
				const name = p.string();
				const zOrder = p.u16();
				const blendMode = p.u8() as BlendMode;
				const opacity = p.u8();
				const imageFormat = p.u8() as ImageFormat;
				const imageDataLength = p.u32();
				const imageData = p.bytes(imageDataLength);
				layers.push({ name, zOrder, blendMode, opacity, imageFormat, imageData });
				break;
			}
			// Unknown chunks are silently skipped (forward compatibility)
		}
	}

	if (!foundChecksum) throw new Error("Missing CKSM chunk");
	if (mapId == null || displayName == null || territories == null || edges == null || authors == null) {
		throw new Error("Missing required chunks (META, GEOM, ADJC)");
	}

	return {
		mapId,
		canvasWidth,
		canvasHeight,
		displayName,
		description,
		authors,
		createdAt,
		editorVersion,
		territories,
		edges,
		layers,
	};
}

function readTerritory(p: BinaryReader): AkmTerritory {
	const territoryId = p.uuid();
	const label = p.string();
	const centroidX = p.f32();
	const centroidY = p.f32();
	const regionCount = p.u16();
	const regions: AkmRegion[] = [];
	for (let r = 0; r < regionCount; r++) {
		const ringCount = p.u16();
		const rings: AkmRing[] = [];
		for (let k = 0; k < ringCount; k++) {
			const pointCount = p.u32();
			const points = new Float32Array(pointCount * 2);
			for (let i = 0; i < points.length; i++) {
				points[i] = p.f32();
			}
			rings.push({ points });
		}
		regions.push({ rings });
	}
	return { territoryId, label, centroidX, centroidY, regions };
}

// ============================================================================
// Binary Writer
// ============================================================================

class BinaryWriter {
	private chunks: Uint8Array[] = [];

	u8(value: number): void {
		this.chunks.push(new Uint8Array([value & 0xff]));
	}

	u16(value: number): void {
		const buf = new Uint8Array(2);
		new DataView(buf.buffer).setUint16(0, value);
		this.chunks.push(buf);
	}

	u32(value: number): void {
		const buf = new Uint8Array(4);
		new DataView(buf.buffer).setUint32(0, value);
		this.chunks.push(buf);
	}

	i64(value: number): void {
		const buf = new Uint8Array(8);
		const view = new DataView(buf.buffer);
		// Split into high and low 32-bit parts (big-endian)
		view.setInt32(0, Math.floor(value / 0x100000000));
		view.setUint32(4, value >>> 0);
		this.chunks.push(buf);
	}

	f32(value: number): void {
		const buf = new Uint8Array(4);
		new DataView(buf.buffer).setFloat32(0, value);
		this.chunks.push(buf);
	}

	uuid(value: string): void {
		const hex = value.replace(/-/g, "");
		if (hex.length !== 32) throw new Error(`Invalid UUID: ${value}`);
		const buf = new Uint8Array(16);
		for (let i = 0; i < 16; i++) {
			buf[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		this.chunks.push(buf);
	}

	string(value: string): void {
		if (!value || value.length === 0) {
			this.u16(0);
			return;
		}
		const encoded = textEncoder.encode(value);
		this.u16(encoded.length);
		this.chunks.push(encoded);
	}

	bytes(data: Uint8Array): void {
		this.chunks.push(data);
	}

	toBytes(): Uint8Array {
		return concat(this.chunks);
	}
}

// ============================================================================
// Binary Reader
// ============================================================================

class BinaryReader {
	private readonly data: Uint8Array;
	private readonly view: DataView;
	private pos: number = 0;

	constructor(data: Uint8Array) {
		this.data = data;
		this.view = new DataView(data.buffer, data.byteOffset, data.byteLength);
	}

	get offset(): number {
		return this.pos;
	}

	remaining(): number {
		return this.data.length - this.pos;
	}

	skip(n: number): void {
		this.ensure(n);
		this.pos += n;
	}

	u8(): number {
		this.ensure(1);
		return this.data[this.pos++];
	}

	u16(): number {
		this.ensure(2);
		const value = this.view.getUint16(this.pos);
		this.pos += 2;
		return value;
	}

	u32(): number {
		this.ensure(4);
		const value = this.view.getUint32(this.pos);
		this.pos += 4;
		return value;
	}

	i64(): number {
		this.ensure(8);
		const high = this.view.getInt32(this.pos);
		const low = this.view.getUint32(this.pos + 4);
		this.pos += 8;
		return high * 0x100000000 + low;
	}

	f32(): number {
		this.ensure(4);
		const value = this.view.getFloat32(this.pos);
		this.pos += 4;
		return value;
	}

	uuid(): string {
		const raw = this.bytes(16);
		const hex = Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("");
		return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
	}

	string(): string {
		const len = this.u16();
		if (len === 0) return "";
		return textDecoder.decode(this.bytes(len));
	}

	bytes(n: number): Uint8Array {
		this.ensure(n);
		const slice = this.data.slice(this.pos, this.pos + n);
		this.pos += n;
		return slice;
	}

	private ensure(n: number): void {
		if (this.pos + n > this.data.length) {
			throw new Error(`Unexpected end of data (need ${n} bytes at offset ${this.pos}, have ${this.data.length - this.pos})`);
		}
	}
}

// ============================================================================
// Utilities
// ============================================================================

function concat(arrays: Uint8Array[]): Uint8Array {
	let totalLength = 0;
	for (const arr of arrays) totalLength += arr.length;
	const result = new Uint8Array(totalLength);
	let offset = 0;
	for (const arr of arrays) {
		result.set(arr, offset);
		offset += arr.length;
	}
	return result;
}

function arraysEqual(a: Uint8Array, b: Uint8Array): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) return false;
	}
	return true;
}

function encodeAscii(s: string): Uint8Array {
	const buf = new Uint8Array(s.length);
	for (let i = 0; i < s.length; i++) buf[i] = s.charCodeAt(i);
	return buf;
}

function decodeAscii(buf: Uint8Array): string {
	let s = "";
	for (let i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]);
	return s;
}
