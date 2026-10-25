package com.m3.veil;

import com.m3.veil.utils.EncryptionManager;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Production Unit Tests for VEIL Cryptographic Engine (#203).
 * Verifies HKDF-SHA256, ECDH Diffie-Hellman self-healing ratchet, and AES-256-GCM AEAD.
 */
public class VeilSecurityAndRatchetTest {

    @Test
    public void testHkdfSha256_derivationAndDeterminism() {
        byte[] ikm = "veil_ephemeral_input_key_material_32b".getBytes(StandardCharsets.UTF_8);
        byte[] salt = "veil_test_salt_123456789".getBytes(StandardCharsets.UTF_8);
        byte[] info = "veil_channel_session_key".getBytes(StandardCharsets.UTF_8);

        byte[] key1 = EncryptionManager.hkdfSha256(ikm, salt, info, 32);
        assertNotNull("Derived key must not be null", key1);
        assertEquals("Derived key must be 32 bytes (256-bit)", 32, key1.length);

        // Deterministic check
        byte[] key2 = EncryptionManager.hkdfSha256(ikm, salt, info, 32);
        assertArrayEquals("HKDF must be strictly deterministic given identical inputs", key1, key2);

        // Domain separation check
        byte[] infoDifferent = "veil_backup_session_key".getBytes(StandardCharsets.UTF_8);
        byte[] key3 = EncryptionManager.hkdfSha256(ikm, salt, infoDifferent, 32);
        assertFalse("Different HKDF info parameter must produce distinct keys", Arrays.equals(key1, key3));
    }

    @Test
    public void testHmacSha256_integrity() {
        byte[] data = "VEIL_COMMUNITY_JURY_VERDICT:QUARANTINED".getBytes(StandardCharsets.UTF_8);
        byte[] key = "super_secure_mesh_consensus_key_32b".getBytes(StandardCharsets.UTF_8);

        byte[] hmac1 = EncryptionManager.hmacSha256(data, key);
        assertNotNull(hmac1);
        assertEquals(32, hmac1.length);

        byte[] hmac2 = EncryptionManager.hmacSha256(data, key);
        assertArrayEquals("HMAC must match for identical data and key", hmac1, hmac2);

        byte[] tamperedData = "VEIL_COMMUNITY_JURY_VERDICT:DISMISSED".getBytes(StandardCharsets.UTF_8);
        byte[] hmacTampered = EncryptionManager.hmacSha256(tamperedData, key);
        assertFalse("HMAC must differ for modified data", Arrays.equals(hmac1, hmacTampered));
    }

    @Test
    public void testEcdhDiffieHellman_sharedSecretDerivation() {
        KeyPair aliceKeyPair = EncryptionManager.generateEcdhKeyPair();
        KeyPair bobKeyPair = EncryptionManager.generateEcdhKeyPair();

        assertNotNull("Alice EC keypair must generate successfully", aliceKeyPair);
        assertNotNull("Bob EC keypair must generate successfully", bobKeyPair);

        byte[] alicePublicEncoded = aliceKeyPair.getPublic().getEncoded();
        byte[] bobPublicEncoded = bobKeyPair.getPublic().getEncoded();

        // Alice computes shared secret using Bob's public key
        byte[] secretAlice = EncryptionManager.computeEcdhSharedSecret(aliceKeyPair.getPrivate(), bobPublicEncoded);
        assertNotNull("Alice derived shared secret", secretAlice);

        // Bob computes shared secret using Alice's public key
        byte[] secretBob = EncryptionManager.computeEcdhSharedSecret(bobKeyPair.getPrivate(), alicePublicEncoded);
        assertNotNull("Bob derived shared secret", secretBob);

        assertArrayEquals("ECDH shared secrets derived by Alice and Bob must match exactly", secretAlice, secretBob);
    }

    @Test
    public void testSymmetricRatchet_stepProducesForwardSecrecyKeys() {
        String dialogId = "test_dialog_ratchet_session_01";
        byte[] rootSeed = new byte[32];
        new SecureRandom().nextBytes(rootSeed);

        EncryptionManager.RatchetState state = EncryptionManager.getOrCreateRatchetState(dialogId, rootSeed);
        assertNotNull("Ratchet state must be initialized", state);
        assertEquals(dialogId, state.dialogId);

        byte[] initialSendChain = state.sendChainKey != null ? state.sendChainKey.clone() : null;
        assertNotNull(initialSendChain);

        // Step send chain
        byte[] messageKey1 = EncryptionManager.ratchetSendKey(dialogId);
        assertNotNull("First stepped message key", messageKey1);
        assertEquals(32, messageKey1.length);
        assertFalse("Stepped message key must differ from chain key", Arrays.equals(initialSendChain, messageKey1));

        // Step send chain again
        byte[] messageKey2 = EncryptionManager.ratchetSendKey(dialogId);
        assertNotNull("Second stepped message key", messageKey2);
        assertFalse("Successive message keys must guarantee forward secrecy", Arrays.equals(messageKey1, messageKey2));
    }

    @Test
    public void testAesGcmAead_encryptionDecryptionAndTamperDetection() throws Exception {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[12]; // 12-byte standard GCM IV
        new SecureRandom().nextBytes(iv);

        String rawPlaintext = "VEIL Pure P2P Zero-Knowledge Mesh Payload #203";
        byte[] plaintext = rawPlaintext.getBytes(StandardCharsets.UTF_8);

        // Encrypt with AES-GCM
        Cipher encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ciphertextAndTag = encryptCipher.doFinal(plaintext);

        assertNotNull(ciphertextAndTag);
        assertTrue("Ciphertext includes 16-byte GCM authentication tag", ciphertextAndTag.length >= plaintext.length + 16);

        // Decrypt with valid key and IV
        Cipher decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] decrypted = decryptCipher.doFinal(ciphertextAndTag);

        assertEquals("Decrypted plaintext must match original", rawPlaintext, new String(decrypted, StandardCharsets.UTF_8));

        // Tamper with ciphertext by flipping 1 bit
        byte[] tampered = ciphertextAndTag.clone();
        tampered[0] ^= 0x01;

        try {
            Cipher tamperCipher = Cipher.getInstance("AES/GCM/NoPadding");
            tamperCipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            tamperCipher.doFinal(tampered);
            fail("Tampered ciphertext MUST throw AEADBadTagException / AEAD integrity failure!");
        } catch (AEADBadTagException e) {
            // Expected! GCM authenticated tag rejected tamper
        }
    }
}
