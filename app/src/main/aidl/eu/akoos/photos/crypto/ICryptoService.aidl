package eu.akoos.photos.crypto;

// Cross-process binder for Drive thumbnail decryption. A large encrypted input is passed as a
// file PATH and a large plaintext comes back through a pipe, so neither crosses the 1 MB binder
// transaction limit and no plaintext is written to a file; only the (small) keys move through
// as byte arrays.
//
// A null byte[] return signals decrypt failure; the client falls back to the
// in-process path. The ToFd methods return false on failure.
import android.os.ParcelFileDescriptor;

interface ICryptoService {
    byte[] decryptNodeKey(String nodeKeyArmored, String nodePassphraseArmored, in byte[] parentKeyBytes);
    byte[] decryptSessionKeyBytes(String contentKeyPacketBase64, in byte[] nodeKeyBytes);
    // Decrypts the file at encPath with the session key and writes the plaintext into sink (the write end of a
    // pipe the caller reads), so it is never written to a file. Closes sink; returns false on failure.
    boolean decryptWithSessionKeyToFd(in byte[] sessionKeyBytes, String encPath, in ParcelFileDescriptor sink);
    byte[] decryptBinaryPgpWithNodeKey(in byte[] data, in byte[] nodeKeyBytes);
    // Sibling of decryptBinaryPgpWithNodeKey for large full-res download blocks (a few MB) that would exceed
    // the binder limit as a byte[]. Reads encPath and writes the plaintext into sink as above.
    boolean decryptBinaryToFd(String encPath, in ParcelFileDescriptor sink, in byte[] nodeKeyBytes);
    // Decrypts a cloud photo's armored XAttr blob to plaintext JSON with the node key.
    // Returns null on failure; the client falls back to the in-process path.
    String decryptXAttr(String xAttrArmored, in byte[] nodeKeyBytes);
}
