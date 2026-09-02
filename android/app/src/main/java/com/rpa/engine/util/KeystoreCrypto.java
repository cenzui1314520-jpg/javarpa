package com.rpa.engine.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore AES-GCM 轻量封装，用于设备密钥落盘加密。
 * 密钥不出 Keystore，root 前提下也难以直接导出。
 */
final class KeystoreCrypto {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "rpa_master";
    private static final String PREFIX = "enc:v1:"; // 带 IV 的密文标记，与历史明文区分
    private static final int GCM_TAG_BITS = 128;

    private KeystoreCrypto() {}

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null; // Keystore 异常时调用方降级明文，保证功能可用
        }
    }

    static String decrypt(String stored) {
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_BITS, all, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), spec);
            byte[] plain = cipher.doFinal(all, 12, all.length - 12);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
