import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Signature;
import java.nio.charset.StandardCharsets;

/**
 * Proves the shaded CLI jar exposes JSch's modern SSH algorithms. Run it against the jar only:
 *
 * <pre>
 * java -cp modules/interfaces/datacraft-cli/target/datacraft-cli.jar tests/ci/JschAlgorithmsProbe.java
 * </pre>
 *
 * <p>JSch keeps its JCE-backed Ed25519 and X25519 classes under {@code META-INF/versions/11} and
 * {@code /15}. They load only when the jar's manifest declares {@code Multi-Release: true}; without
 * it JSch sees Java 8, maps {@code ssh-ed25519} to its absent BouncyCastle variant and its XDH stub
 * refuses to start, so ed25519 keys and curve25519 key exchange silently disappear from SftpClient.
 * Any failure exits non-zero.
 */
public class JschAlgorithmsProbe {

  private static final String JCE_ED25519 = "com.jcraft.jsch.jce.SignatureEd25519";

  public static void main(String[] args) throws Exception {
    System.out.println(
        "JSch loaded from " + JSch.class.getProtectionDomain().getCodeSource().getLocation());

    String ed25519 = JSch.getConfig("ssh-ed25519");
    if (!JCE_ED25519.equals(ed25519)) {
      throw new IllegalStateException(
          "ssh-ed25519 resolves to " + ed25519 + ", expected " + JCE_ED25519);
    }

    // ssh-keygen's default key type: generate, sign and verify with the configured implementation.
    KeyPair keyPair = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
    byte[] data = "datacraft".getBytes(StandardCharsets.US_ASCII);
    byte[] signature = keyPair.getSignature(data);
    if (signature == null) {
      throw new IllegalStateException("Ed25519 signing failed");
    }
    Signature verifier = keyPair.getVerifier();
    verifier.update(data);
    if (!verifier.verify(signature)) {
      throw new IllegalStateException("Ed25519 signature did not verify");
    }

    // The JCE key agreement behind the curve25519-sha256 and mlkem768x25519-sha256 key exchanges.
    new com.jcraft.jsch.jce.XDH().init("X25519", 32);

    System.out.println("JSch Ed25519 signatures and X25519 key agreement are available");
  }
}
