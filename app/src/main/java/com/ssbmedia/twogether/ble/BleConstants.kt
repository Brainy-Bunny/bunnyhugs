package com.ssbmedia.twogether.ble

import android.os.ParcelUuid
import java.util.UUID

/**
 * KNOWN ACCEPTED MVP LIMITATION (not fixed in this pass): the advertisement broadcast by
 * AdvertiserManager is a static, replayable, unauthenticated payload with no rolling identifier or
 * nonce - a sniffed advertisement could theoretically be replayed later to fake a "together"
 * detection, and the small keyspace of the underlying pairing code (10^6) means the broadcast prefix
 * bytes could be brute-forced back toward candidate codes given enough captures. A full fix (rolling
 * identifiers, real key exchange) is out of scope here; GattSyncManager's connection-level handshake
 * and the strengthened pairing-code hash (see util/Hashing.kt) close the two most exploitable paths -
 * with one important asymmetry: the handshake only authenticates the CLIENT to the SERVER (a
 * connecting device must prove it knows the pair secret before the server accepts anything from it),
 * not the reverse. It stops a passive/naive unauthenticated device from writing to or reading from our
 * GATT *server* role. It does NOT protect a client connecting to an impersonated server - an active
 * attacker posing as the partner's server could still receive a real client's data. Reversal of an
 * extracted raw pairing-code hash is still meaningfully harder thanks to the strengthened hash.
 */
object BleConstants {
    /** Shared by every Twogether install so the scanner can find any Twogether beacon at the OS filter level. */
    val PROXIMITY_SERVICE_UUID: UUID = UUID.fromString("6f5a0001-9c1e-4f2a-8a3d-3b1e6d4f0001")
    val PROXIMITY_PARCEL_UUID: ParcelUuid = ParcelUuid(PROXIMITY_SERVICE_UUID)

    val SYNC_SERVICE_UUID: UUID = UUID.fromString("6f5a0002-9c1e-4f2a-8a3d-3b1e6d4f0002")
    val SYNC_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f5a0003-9c1e-4f2a-8a3d-3b1e6d4f0003")
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Feature 2: a second characteristic on the SAME sync service/connection, dedicated to photo bytes
     * so the small metadata JSON exchange (SYNC_CHARACTERISTIC_UUID) never has to share reassembly state
     * with the much larger, much slower photo transfer - see GattSyncManager's photo-phase doc. */
    val PHOTO_CHARACTERISTIC_UUID: UUID = UUID.fromString("6f5a0004-9c1e-4f2a-8a3d-3b1e6d4f0004")

    /** How many leading hex chars of the strengthened pair-code hash we broadcast in service data to identify our partner. */
    const val SECRET_PREFIX_BYTES = 4

    /**
     * How many hex chars of the pair-code hash we use to derive the GATT handshake token (see
     * GattSyncManager) - taken from a hex range that starts right AFTER the bytes used for
     * SECRET_PREFIX_BYTES above, so the handshake token is never fully exposed by the (unauthenticated,
     * public) BLE advertisement itself. A naive stranger scanning nearby BLE devices only ever sees the
     * first SECRET_PREFIX_BYTES bytes over the air.
     */
    const val HANDSHAKE_TOKEN_BYTES = 8
    const val HANDSHAKE_TOKEN_HEX_OFFSET = SECRET_PREFIX_BYTES * 2

    /** Chunk protocol: 1 flag byte (0 = more chunks follow, 1 = last chunk) + payload. */
    const val CHUNK_FLAG_MORE: Byte = 0
    const val CHUNK_FLAG_LAST: Byte = 1
    /** Design ceiling for a single chunk's payload - the actual per-connection chunk size used is
     * further clamped down to whatever ATT MTU was really negotiated on that connection (see
     * GattSyncManager.effectiveChunkPayload); never sent larger than what fits. */
    const val MAX_CHUNK_PAYLOAD = 180

    /** Feature 2: a higher chunk-payload ceiling used ONLY for the photo characteristic. JSON metadata
     * chunks stay capped at MAX_CHUNK_PAYLOAD above (unchanged, proven-safe sizing); photo transfers are
     * the whole reason to request a bigger MTU at all (see GattSyncManager's REQUESTED_MTU) - without
     * raising this ceiling too, a larger negotiated MTU would go to waste and every photo would still
     * crawl through at 180-byte chunks. */
    const val MAX_CHUNK_PAYLOAD_PHOTO = 500

    /** Photo-characteristic wire protocol: each fully-reassembled message (chunked exactly like the JSON
     * envelope, via the same MORE/LAST flag byte) starts with one of these type bytes. */
    const val PHOTO_FRAME_TYPE_REQUEST: Byte = 1
    const val PHOTO_FRAME_TYPE_DATA: Byte = 2
    const val PHOTO_FRAME_TYPE_DONE: Byte = 3

    /**
     * Slices [numBytes] bytes out of a hex string starting at hex-character [hexOffset] (2 hex chars per
     * byte). Used to deterministically derive several independent byte sequences (advertised prefix,
     * GATT handshake token) from the single stored pair-code hash, without ever needing the two paired
     * phones to exchange anything beyond the original 6-digit code itself.
     */
    fun deriveBytesFromHexHash(hexHash: String, hexOffset: Int, numBytes: Int): ByteArray {
        val neededHexLen = hexOffset + numBytes * 2
        val padded = hexHash.padEnd(neededHexLen, '0')
        val slice = padded.substring(hexOffset, neededHexLen)
        return ByteArray(numBytes) { i -> slice.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
