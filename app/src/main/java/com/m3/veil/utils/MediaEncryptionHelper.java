package com.m3.veil.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MediaEncryptionHelper {

    public static class EncryptionResult {
        public File encryptedFile;
        public String aesKeyBase64;
        public String ivBase64;
    }

    public static EncryptionResult encryptFile(File inputFile, File outputFile) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey secretKey = keyGen.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec ivSpec = new GCMParameterSpec(128, iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(outputFile);
             CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
            
            byte[] buffer = VeilBufferPool.borrowByteArray16k();
            try {
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    cos.write(buffer, 0, read);
                }
            } finally {
                VeilBufferPool.recycleByteArray(buffer);
            }
        }

        EncryptionResult result = new EncryptionResult();
        result.encryptedFile = outputFile;
        result.aesKeyBase64 = android.util.Base64.encodeToString(secretKey.getEncoded(), android.util.Base64.NO_WRAP);
        result.ivBase64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP);
        return result;
    }

    public static File decryptFile(File encryptedFile, File outputFile, String aesKeyBase64, String ivBase64) throws Exception {
        byte[] keyBytes = android.util.Base64.decode(aesKeyBase64, android.util.Base64.NO_WRAP);
        byte[] iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP);

        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec ivSpec = new GCMParameterSpec(128, iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

        try (FileInputStream fis = new FileInputStream(encryptedFile);
             CipherInputStream cis = new CipherInputStream(fis, cipher);
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            byte[] buffer = VeilBufferPool.borrowByteArray16k();
            try {
                int read;
                while ((read = cis.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            } finally {
                VeilBufferPool.recycleByteArray(buffer);
            }
        }
        return outputFile;
    }
}
