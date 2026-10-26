package com.m3.veil.webrtc;

import android.util.Log;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.security.SecureRandom;

/**
 * Deterministic binary ByteBuffer packet framing for VEIL WebRTC multi-lane transport.
 * Header format (24 bytes):
 * - Magic (4 bytes): 0x5645494C ("VEIL")
 * - Version (1 byte): 0x01
 * - Lane (1 byte): 0x00 CTRL, 0x01 MSG, 0x02 MEDIA, 0x03 MESH_GOSSIP
 * - Packet Type (2 bytes short)
 * - Sequence (4 bytes int)
 * - Payload Length (4 bytes int)
 * - CRC32 (8 bytes long)
 * - Payload: byte[payloadLength]
 */
public class VeilPacket {

    private static final String TAG = "VeilPacket";

    public static final int MAGIC = 0x5645494C; // "VEIL"
    public static final byte VERSION = 0x01;
    public static final int HEADER_SIZE = 24;

    // Lanes
    public static final byte LANE_CTRL = 0x00;
    public static final byte LANE_MSG = 0x01;
    public static final byte LANE_MEDIA = 0x02;
    public static final byte LANE_MESH_GOSSIP = 0x03;

    // Control Types (0x0001 - 0x00FF)
    public static final short TYPE_PING = 0x0001;
    public static final short TYPE_PONG = 0x0002;
    public static final short TYPE_TYPING = 0x0003;
    public static final short TYPE_DELIVERED_ACK = 0x0004;
    public static final short TYPE_READ_RECEIPT = 0x0005;
    public static final short TYPE_BLINK_OPENED = 0x0006;
    public static final short TYPE_VANISH_NOTIFY = 0x0007;
    public static final short TYPE_SECURITY_ALERT = 0x0008;
    public static final short TYPE_RESEND_REQUEST = 0x0009;

    // Message Types (0x0101 - 0x01FF)
    public static final short TYPE_CHAT_MESSAGE = 0x0101;
    public static final short TYPE_REACTION = 0x0102;
    public static final short TYPE_POLL_VOTE = 0x0103;
    public static final short TYPE_MSG_EDIT = 0x0104;
    public static final short TYPE_MSG_DELETE = 0x0105;
    public static final short TYPE_PIN_SETTING = 0x0106;
    public static final short TYPE_CHAT_CLEAR = 0x0107;
    public static final short TYPE_GROUP_SETTINGS = 0x0108;
    public static final short TYPE_GAME_STATE = 0x0109;

    // Media Types (0x0201 - 0x02FF)
    public static final short TYPE_FILE_META = 0x0201;
    public static final short TYPE_FILE_CHUNK = 0x0202;
    public static final short TYPE_FILE_CHUNK_ACK = 0x0203;
    public static final short TYPE_FILE_END = 0x0204;
    public static final short TYPE_FILE_REQUEST = 0x0205;
    public static final short TYPE_FILE_CANCEL = 0x0206;
    public static final short TYPE_SWARM_CHUNK_REQUEST = 0x0207;

    // Mesh Gossip Types (0x0301 - 0x03FF)
    public static final short TYPE_MESH_ENVELOPE = 0x0301;
    public static final short TYPE_MESH_DELIVERY_ACK = 0x0302;
    public static final short TYPE_MESH_DISCOVERY = 0x0303;
    public static final short TYPE_GOSSIP_POST = 0x0304;
    public static final short TYPE_GOSSIP_COMMENT = 0x0305;
    public static final short TYPE_GOSSIP_LIKE = 0x0306;
    public static final short TYPE_STATUS_POST = 0x0307;
    public static final short TYPE_MODERATION_VOTE = 0x0308;

    private static final AtomicInteger SEQ_GENERATOR = new AtomicInteger(1);

    private final byte version;
    private final byte lane;
    private final short packetType;
    private final int sequenceNumber;
    private final long crc32;
    private final byte[] payload;
    private byte[] dhPublicKey;

    public VeilPacket(byte lane, short packetType, int sequenceNumber, byte[] payload) {
        this(lane, packetType, sequenceNumber, payload, null);
    }

    public VeilPacket(byte lane, short packetType, int sequenceNumber, byte[] payload, byte[] dhPublicKey) {
        this.version = (dhPublicKey != null && dhPublicKey.length > 0) ? (byte) 0x02 : VERSION;
        this.lane = lane;
        this.packetType = packetType;
        this.sequenceNumber = sequenceNumber;
        this.payload = payload != null ? payload : new byte[0];
        this.crc32 = computeCrc32(this.payload);
        this.dhPublicKey = dhPublicKey;
    }

    public VeilPacket(byte lane, short packetType, byte[] payload) {
        this(lane, packetType, SEQ_GENERATOR.getAndIncrement(), payload, null);
    }

    public VeilPacket(byte lane, short packetType, byte[] payload, byte[] dhPublicKey) {
        this(lane, packetType, SEQ_GENERATOR.getAndIncrement(), payload, dhPublicKey);
    }

    public VeilPacket(byte lane, short packetType, String jsonString) {
        this(lane, packetType, jsonString != null ? jsonString.getBytes(StandardCharsets.UTF_8) : new byte[0], null);
    }

    public VeilPacket(byte lane, short packetType, String jsonString, byte[] dhPublicKey) {
        this(lane, packetType, jsonString != null ? jsonString.getBytes(StandardCharsets.UTF_8) : new byte[0], dhPublicKey);
    }

    private VeilPacket(byte version, byte lane, short packetType, int sequenceNumber, long crc32, byte[] payload, byte[] dhPublicKey) {
        this.version = version;
        this.lane = lane;
        this.packetType = packetType;
        this.sequenceNumber = sequenceNumber;
        this.crc32 = crc32;
        this.payload = payload != null ? payload : new byte[0];
        this.dhPublicKey = dhPublicKey;
    }

    public byte[] getDhPublicKey() {
        if (dhPublicKey != null) {
            return dhPublicKey;
        }
        // Fallback: inspect JSON payload for dhPublicKey if present
        try {
            if (payload != null && payload.length > 0) {
                JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                JSONObject p = json.optJSONObject("payload");
                String dhKeyBase64 = p != null ? p.optString("dhPublicKey", null) : json.optString("dhPublicKey", null);
                if (dhKeyBase64 != null && !dhKeyBase64.isEmpty()) {
                    dhPublicKey = android.util.Base64.decode(dhKeyBase64, android.util.Base64.DEFAULT);
                    return dhPublicKey;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public void setDhPublicKey(byte[] dhPublicKey) {
        this.dhPublicKey = dhPublicKey;
    }

    public byte getVersion() {
        return version;
    }

    public byte getLane() {
        return lane;
    }

    public short getPacketType() {
        return packetType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getCrc32() {
        return crc32;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getPayloadAsString() {
        if (payload == null || payload.length == 0) return "";
        return new String(payload, StandardCharsets.UTF_8);
    }

    public JSONObject getPayloadAsJson() {
        try {
            return new JSONObject(getPayloadAsString());
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse payload as JSON: " + e.getMessage());
            return new JSONObject();
        }
    }

    public static final int PADDING_BLOCK_SIZE = 256;
    private static final SecureRandom PADDING_RANDOM = new SecureRandom();

    public byte[] serialize() {
        return serialize(true);
    }

    public byte[] serialize(boolean padToBlockSize) {
        boolean hasDhKey = (dhPublicKey != null && dhPublicKey.length > 0);
        byte ver = hasDhKey ? (byte) 0x02 : version;
        int dhHeaderSize = hasDhKey ? (1 + dhPublicKey.length) : 0;
        int unpaddedSize = HEADER_SIZE + dhHeaderSize + payload.length;
        int totalSize = unpaddedSize;

        if (padToBlockSize) {
            int rem = unpaddedSize % PADDING_BLOCK_SIZE;
            if (rem != 0) {
                totalSize = unpaddedSize + (PADDING_BLOCK_SIZE - rem);
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.BIG_ENDIAN);

        buffer.putInt(MAGIC);
        buffer.put(ver);
        buffer.put(lane);
        buffer.putShort(packetType);
        buffer.putInt(sequenceNumber);
        buffer.putInt(payload.length);
        buffer.putLong(crc32);
        if (hasDhKey) {
            buffer.put((byte) dhPublicKey.length);
            buffer.put(dhPublicKey);
        }
        if (payload.length > 0) {
            buffer.put(payload);
        }
        if (totalSize > unpaddedSize) {
            byte[] padding = new byte[totalSize - unpaddedSize];
            PADDING_RANDOM.nextBytes(padding);
            buffer.put(padding);
        }

        return buffer.array();
    }

    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(serialize());
    }

    public static VeilPacket deserialize(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return null;
        }
        return deserialize(ByteBuffer.wrap(data));
    }

    public static VeilPacket deserialize(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < HEADER_SIZE) {
            return null;
        }

        buffer.order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            Log.w(TAG, String.format("Invalid packet magic: 0x%08X (expected 0x%08X)", magic, MAGIC));
            return null;
        }

        byte version = buffer.get();
        byte lane = buffer.get();
        short packetType = buffer.getShort();
        int sequenceNumber = buffer.getInt();
        int payloadLength = buffer.getInt();

        if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) { // 10MB safety cap
            Log.w(TAG, "Unreasonable payload length: " + payloadLength);
            return null;
        }

        if (buffer.remaining() < 8 + payloadLength) {
            Log.w(TAG, "Truncated buffer: expected " + (8 + payloadLength) + " bytes, available " + buffer.remaining());
            return null;
        }

        long expectedCrc32 = buffer.getLong();

        byte[] dhKey = null;
        if (version >= 0x02 && buffer.remaining() >= 1) {
            int dhLen = buffer.get() & 0xFF;
            if (dhLen > 0 && buffer.remaining() >= dhLen) {
                dhKey = new byte[dhLen];
                buffer.get(dhKey);
            }
        }

        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0 && buffer.remaining() >= payloadLength) {
            buffer.get(payload);
        }

        long actualCrc32 = computeCrc32(payload);
        if (actualCrc32 != expectedCrc32) {
            Log.e(TAG, "CRC32 checksum mismatch! Expected: " + expectedCrc32 + ", got: " + actualCrc32 + " (packet dropped)");
            return null;
        }

        return new VeilPacket(version, lane, packetType, sequenceNumber, expectedCrc32, payload, dhKey);
    }

    public static long computeCrc32(byte[] data) {
        if (data == null || data.length == 0) return 0L;
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    @Override
    public String toString() {
        return "VeilPacket{" +
                "lane=0x" + Integer.toHexString(lane) +
                ", type=0x" + Integer.toHexString(packetType) +
                ", seq=" + sequenceNumber +
                ", len=" + payload.length +
                ", crc=" + crc32 +
                '}';
    }
}
