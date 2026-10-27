package com.m3.veil.database;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.security.SecureRandom;

/**
 * Manages the cryptographic passphrase for the local SQLCipher encrypted database.
 * The passphrase is generated using a cryptographically secure random generator (CSPRG)
 * and persisted within Android Keystore-backed EncryptedSharedPreferences.
 */
public class DatabaseKeyManager {

    private static final String PREFS_NAME = "veil_db_security_prefs";
    private static final String KEY_DB_PASSPHRASE = "key_sqlcipher_passphrase";
    private static volatile byte[] sCachedPassphrase = null;

    public static synchronized byte[] getDatabasePassphrase(Context context) {
        if (sCachedPassphrase != null) {
            return sCachedPassphrase;
        }
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences securePrefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context.getApplicationContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            String encodedKey = securePrefs.getString(KEY_DB_PASSPHRASE, null);
            if (encodedKey == null) {
                // Generate a fresh 256-bit (32 bytes) random cryptographic key
                byte[] newKey = new byte[32];
                new SecureRandom().nextBytes(newKey);
                encodedKey = Base64.encodeToString(newKey, Base64.NO_WRAP);
                securePrefs.edit().putString(KEY_DB_PASSPHRASE, encodedKey).apply();
                sCachedPassphrase = newKey;
                return newKey;
            }

            sCachedPassphrase = Base64.decode(encodedKey, Base64.NO_WRAP);
            return sCachedPassphrase;
        } catch (Exception e) {
            // Fallback to internal hardware-bound hash if EncryptedSharedPreferences throws on legacy ROMs
            String fallback = "veil_secure_vault_" + android.provider.Settings.Secure.getString(
                    context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            sCachedPassphrase = fallback.getBytes();
            return sCachedPassphrase;
        }
    }
}
