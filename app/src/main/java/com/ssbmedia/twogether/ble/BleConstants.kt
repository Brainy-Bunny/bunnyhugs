package com.ssbmedia.twogether.ble

import android.os.ParcelUuid
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KNOWN ACCEPTED LIMITATION (not fixed in this pass - this is exactly what a later PAKE-based rework,
 * "Stage 2", exists to close): the advertisement broadcast by AdvertiserManager is a static, replayable,
 * unauthenticated payload with no rolling identifier or nonce - a sniffed advertisement could
 * theoretically be replayed later to fake a "together" detection, and the small keyspace of the
 * underlying pairing code (10^6) means the broadcast prefix bytes could be brute-forced back toward
 * candidate codes given enough captures (independently reproduced during design review: well under two
 * minutes on a single consumer GPU against this file's own strengthened, 600,000-iteration hash).
 * Reversal of an extracted raw pairing-code hash is still meaningfully harder thanks to that
 * strengthening (see util/Hashing.kt), but it does not change the underlying keyspace.
 *
 * SECURITY (mutual handshake): GattSyncManager's connection-level handshake is nonce/HMAC-based (see
 * computeMutualHandshakeResponse below, and GattSyncManager's class doc), NOT a static bearer token - a
 * captured handshake message is worthless replayed against a later connection, since fresh nonces are
 * generated every connection and only an HMAC over that exact nonce pair is accepted. As of the
 * mutual-handshake rework, BOTH sides prove knowledge of the shared secret to each other - and BOTH
 * sides check the connecting/connected identity against this pairing's pinned partner (see
 * checkPinOrRecordPending) - before EITHER side transmits a byte of real payload. This closes the
 * asymmetry an earlier version of this file used to document here: previously only the CLIENT proved
 * itself to the SERVER, so a fake "server" that knew nothing about the shared secret could still receive
 * a real client's full data payload. That specific gap is closed.
 *
 * What this does NOT do: derive an actual session key or encrypt the subsequent payload channel. Both
 * sides proving they know the SAME pre-existing shared secret is not the same guarantee as a real key
 * exchange - an active on-path relay that simply forwards bytes between a genuine client and a genuine
 * server (rather than impersonating either one outright) is not something a mutual proof of shared-secret
 * knowledge alone can detect, and the payload itself is still sent in the clear. Closing that requires a
 * real key-exchange protocol (a PAKE run once over the pairing code, deriving a session key everything
 * else - including the advertised identifier and the payload channel - is then rekeyed off), which is
 * exactly Stage 2's scope, not this pass's.
 *
 * SECURITY (device-ID pinning): the handshake above only proves a connecting device knows OUR pairing
 * code - it says nothing on its own about WHICH device that is. Before device-ID pinning existed, a
 * second couple who coincidentally landed on the same 6-digit code (1-in-a-million per pairing,
 * non-negligible if many couples pair nearby around the same time - a crowded venue, say) would pass the
 * handshake and sync data exactly like the real partner would, for as long as both phones kept running
 * the background proximity service - not just once. PairingStore.pinPartnerDeviceIdIfAbsent +
 * checkPinOrRecordPending's use of it now close most of that: the first sync after pairing pins whichever
 * device we synced with as the trusted partner, and every later connection from a different device id -
 * checked mutually, during the handshake itself, on BOTH sides, before either side transmits anything
 * real - is rejected outright. This is trust-on-first-use, not a full fix - if a colliding stranger's
 * phone happens to win the race to be first to sync (needs matching code + BLE range + beating the real
 * partner to it), that stranger gets pinned instead; and, per the paragraph above, a party who has
 * genuinely obtained the pairing code (not merely a random collision) is not excluded by this mechanism
 * at all, since they can complete the mutual proof honestly. Considered an acceptable residual risk given
 * this app's real threat model (see the Stage 1/Stage 2 design review), not a claim that it's closed.
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
     * How many hex chars of the pair-code hash we use to derive the shared GATT handshake KEY (see
     * GattSyncManager / computeMutualHandshakeResponse below) - taken from a hex range that starts right AFTER
     * the bytes used for SECRET_PREFIX_BYTES above, so this key material is never overlapping with the
     * (unauthenticated, public) BLE advertisement's own prefix bytes. This key is never itself put on the
     * air - only a per-connection random nonce and its HMAC response are - so, unlike the old static-token
     * scheme, that non-overlap is now just defense in depth rather than the only thing standing between a
     * sniffer and a usable credential.
     */
    const val HANDSHAKE_TOKEN_BYTES = 8
    const val HANDSHAKE_TOKEN_HEX_OFFSET = SECRET_PREFIX_BYTES * 2

    /** Size, in bytes, of the random nonce the GATT server issues to a freshly-subscribed client at the
     * start of every connection - see GattSyncManager's onDescriptorWriteRequest. Regenerated fresh per
     * connection, so a captured handshake response can never be replayed against a later one. */
    const val HANDSHAKE_NONCE_BYTES = 16

    /** Truncation length of the HMAC-SHA256 handshake response - see computeMutualHandshakeResponse. 16
     * bytes (128 bits) of MAC output is comfortably beyond brute-force reach while staying well under any
     * BLE MTU, so it never needs its own chunking. */
    const val HANDSHAKE_RESPONSE_BYTES = 16

    /** SECURITY (mutual-handshake follow-up, per multi-round advisory review): the ONE-WAY handshake
     * above only ever proves the CLIENT to the SERVER - a fake "server" that knows nothing can still
     * receive a real client's full data payload, since the client used to send it the moment its own
     * write locally succeeded, without ever checking the server proved anything back. This computes
     * BOTH directions of a mutual proof from the SAME shared handshakeKey, with [role] as a domain
     * separator (HANDSHAKE_ROLE_CLIENT when the CLIENT is the one proving itself to the server,
     * HANDSHAKE_ROLE_SERVER for the reverse) so a captured client-proof can never be replayed back as a
     * valid server-proof even though both are derived from the same key. [nonceFirst]/[nonceSecond] are
     * always ordered "nonce the verifier issued, nonce the prover issued" for THAT direction - see the
     * two call sites in GattSyncManager for the exact ordering each side uses; getting this backwards
     * on either side would make every legitimate handshake fail closed (safe) rather than open
     * (unsafe), since a mismatched order just fails to match, but it's still tracked precisely to avoid
     * spurious failures. [deviceId]/[partnerName] are bound INTO the MAC (not just carried alongside
     * it) with explicit length prefixes before each field, specifically so an attacker who doesn't know
     * handshakeKey can't tamper with either field in transit without invalidating the proof - without
     * the length prefixes, two different (deviceId, partnerName) pairs whose concatenation happens to
     * produce the same byte sequence would be indistinguishable to the MAC. */
    fun computeMutualHandshakeResponse(
        handshakeKey: ByteArray,
        role: Byte,
        nonceFirst: ByteArray,
        nonceSecond: ByteArray,
        deviceId: ByteArray,
        partnerName: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(handshakeKey, "HmacSHA256"))
        mac.update(byteArrayOf(role))
        mac.update(nonceFirst)
        mac.update(nonceSecond)
        mac.update(byteArrayOf(deviceId.size.toByte()))
        mac.update(deviceId)
        mac.update(byteArrayOf(partnerName.size.toByte()))
        mac.update(partnerName)
        return mac.doFinal().copyOf(HANDSHAKE_RESPONSE_BYTES)
    }

    /** Domain separator for [computeMutualHandshakeResponse] - see its own doc for why this exists. */
    const val HANDSHAKE_ROLE_CLIENT: Byte = 0x01
    const val HANDSHAKE_ROLE_SERVER: Byte = 0x02

    /** Hard cap on the UTF-8 byte length of a single variable-length field
     * ([computeMutualHandshakeResponse]'s deviceId/partnerName) - both are length-prefixed with one
     * byte on the wire, so this is the largest value that prefix can represent, not an arbitrary
     * product limit. deviceId is always a 36-character UUID string (well under this); partnerName is a
     * short display name that's realistically always far shorter, but is defensively truncated to this
     * cap (silently, at the point of encoding - see GattSyncManager) rather than trusted to already be
     * short, since it's ultimately free-form user input. */
    const val MAX_HANDSHAKE_FIELD_BYTES = 255

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
