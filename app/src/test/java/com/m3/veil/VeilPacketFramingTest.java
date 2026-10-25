package com.m3.veil;

import com.m3.veil.webrtc.VeilPacket;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production Unit Tests for VeilPacket Binary Framing & CRC32 Verification (#204).
 * Verifies binary serialization, multi-lane routing, CRC32 integrity checks,
 * corruption rejection, and ECDH header parsing.
 */
public class VeilPacketFramingTest {

    @Test
    public void testPacketSerializationAndRoundTrip() {
        byte lane = VeilPacket.LANE_MSG;
        short packetType = VeilPacket.TYPE_CHAT_MESSAGE;
        int seq = 1042;
        byte[] payload = "Hello Veil Mesh Packet Framing".getBytes(StandardCharsets.UTF_8);

        VeilPacket packet = new VeilPacket(lane, packetType, seq, payload);
        assertEquals(lane, packet.getLane());
        assertEquals(packetType, packet.getPacketType());
        assertEquals(seq, packet.getSequenceNumber());
        assertArrayEquals(payload, packet.getPayload());

        byte[] serialized = packet.serialize();
        assertNotNull(serialized);
        assertTrue(serialized.length >= VeilPacket.HEADER_SIZE + payload.length);

        // Verify magic number in big endian
        ByteBuffer bb = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);
        int magic = bb.getInt();
        assertEquals(VeilPacket.MAGIC, magic);

        // Deserialize
        VeilPacket parsed = VeilPacket.deserialize(serialized);
        assertNotNull("Deserialization of valid frame must succeed", parsed);
        assertEquals(packet.getVersion(), parsed.getVersion());
        assertEquals(lane, parsed.getLane());
        assertEquals(packetType, parsed.getPacketType());
        assertEquals(seq, parsed.getSequenceNumber());
        assertEquals(packet.getCrc32(), parsed.getCrc32());
        assertArrayEquals(payload, parsed.getPayload());
        assertEquals("Hello Veil Mesh Packet Framing", parsed.getPayloadAsString());
    }

    @Test
    public void testMultiLaneRoutingFlags() {
        byte[] payload = new byte[]{1, 2, 3};

        VeilPacket ctrlPacket = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_SECURITY_ALERT, payload);
        assertEquals(VeilPacket.LANE_CTRL, ctrlPacket.getLane());

        VeilPacket mediaPacket = new VeilPacket(VeilPacket.LANE_MEDIA, VeilPacket.TYPE_FILE_CHUNK, payload);
        assertEquals(VeilPacket.LANE_MEDIA, mediaPacket.getLane());

        VeilPacket gossipPacket = new VeilPacket(VeilPacket.LANE_MESH_GOSSIP, VeilPacket.TYPE_GOSSIP_POST, payload);
        assertEquals(VeilPacket.LANE_MESH_GOSSIP, gossipPacket.getLane());

        VeilPacket gamePacket = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_GAME_STATE, payload);
        assertEquals(VeilPacket.TYPE_GAME_STATE, gamePacket.getPacketType());

        VeilPacket modPacket = new VeilPacket(VeilPacket.LANE_CTRL, VeilPacket.TYPE_MODERATION_VOTE, payload);
        assertEquals(VeilPacket.TYPE_MODERATION_VOTE, modPacket.getPacketType());
    }

    @Test
    public void testCorruptionRejection_viaCrc32Mismatch() {
        byte[] payload = "Critical uncorrupted cryptographic payload".getBytes(StandardCharsets.UTF_8);
        VeilPacket packet = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, 77, payload);
        byte[] serialized = packet.serialize();

        // Mutate a byte in the payload region (at the end of the packet)
        byte[] corrupted = serialized.clone();
        corrupted[corrupted.length - 1] ^= 0xFF;

        // Deserialization must reject corrupted packet due to CRC32 mismatch
        VeilPacket rejected = VeilPacket.deserialize(corrupted);
        assertNull("Corrupted packet with mismatched CRC32 must be rejected (return null)", rejected);
    }

    @Test
    public void testInvalidMagicAndTruncationRejection() {
        // Truncated data shorter than header size
        byte[] tooShort = new byte[VeilPacket.HEADER_SIZE - 2];
        assertNull("Truncated data shorter than header must return null", VeilPacket.deserialize(tooShort));

        // Corrupted Magic Number
        byte[] payload = "Valid payload".getBytes(StandardCharsets.UTF_8);
        VeilPacket packet = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, 1, payload);
        byte[] serialized = packet.serialize();
        serialized[0] = 0x00; // Invalidate first magic byte
        serialized[1] = 0x00;

        assertNull("Packet with invalid magic header must return null", VeilPacket.deserialize(serialized));
    }

    @Test
    public void testEcdhPublicKeyHeaderFraming() {
        byte[] payload = "Ratcheted confidential message".getBytes(StandardCharsets.UTF_8);
        byte[] dhPubKey = new byte[65]; // Uncompressed P-256 EC public key
        Arrays.fill(dhPubKey, (byte) 0x04);

        VeilPacket packet = new VeilPacket(VeilPacket.LANE_MSG, VeilPacket.TYPE_CHAT_MESSAGE, 999, payload, dhPubKey);
        assertEquals((byte) 0x02, packet.getVersion());
        assertArrayEquals(dhPubKey, packet.getDhPublicKey());

        byte[] serialized = packet.serialize();
        VeilPacket parsed = VeilPacket.deserialize(serialized);

        assertNotNull("Deserialization of packet with DH key header must succeed", parsed);
        assertEquals((byte) 0x02, parsed.getVersion());
        assertArrayEquals(dhPubKey, parsed.getDhPublicKey());
        assertArrayEquals(payload, parsed.getPayload());
    }
}
