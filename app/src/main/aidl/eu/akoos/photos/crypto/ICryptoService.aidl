package eu.akoos.photos.crypto;

// Cross-process binder for Drive thumbnail decryption. Blobs (encrypted file,
// decrypted output) are passed as file PATHS so they never cross the 1 MB binder
// transaction limit; only the (small) keys move through as byte arrays.
//
// A null byte[] return signals decrypt failure; the client falls back to the
// in-process path. decryptFileWithSessionKey returns false on failure.
interface ICryptoService {
    byte[] decryptNodeKey(String nodeKeyArmored, String nodePassphraseArmored, in byte[] parentKeyBytes);
    byte[] decryptSessionKeyBytes(String contentKeyPacketBase64, in byte[] nodeKeyBytes);
    boolean decryptFileWithSessionKey(in byte[] sessionKeyBytes, String encPath, String destPath);
    byte[] decryptBinaryPgpWithNodeKey(in byte[] data, in byte[] nodeKeyBytes);
    // File-based sibling of decryptBinaryPgpWithNodeKey for large full-res download
    // blocks (a few MB) that would exceed the binder limit as a byte[]. Reads encPath,
    // writes the plaintext to destPath; returns false on failure.
    boolean decryptBinaryToFile(String encPath, String destPath, in byte[] nodeKeyBytes);
    // Decrypts a cloud photo's armored XAttr blob to plaintext JSON with the node key.
    // Returns null on failure; the client falls back to the in-process path.
    String decryptXAttr(String xAttrArmored, in byte[] nodeKeyBytes);
}
