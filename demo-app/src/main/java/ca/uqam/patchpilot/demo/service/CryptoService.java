package ca.uqam.patchpilot.demo.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;

/**
 * Encryption utility service.
 *
 * VULNERABILITY 5 — Weak Cryptographic Algorithm (SonarQube rule: java:S4426)
 *
 * DES (Data Encryption Standard) uses a 56-bit key that can be brute-forced
 * with commodity hardware in hours. It has been deprecated since the late 1990s
 * and is classified as broken by NIST.
 *
 * Secure fix: replace DES with AES-256-GCM:
 *   KeyGenerator.getInstance("AES") with a key size of 256 bits,
 *   and Cipher.getInstance("AES/GCM/NoPadding").
 */
@Service
public class CryptoService {

    // VULNERABILITY: DES is a broken algorithm — minimum acceptable is AES-128
    public byte[] encrypt(byte[] data) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("DES");
        SecretKey secretKey = keyGen.generateKey();

        Cipher cipher = Cipher.getInstance("DES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return cipher.doFinal(data);
    }
}
