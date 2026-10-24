import java.io.File;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import com.android.apksig.ApkVerifier;

/** Reads public certificates only; no keystore/private-key access. */
public class CertificateAudit {
    public static void main(String[] paths) throws Exception {
        for (String path : paths) {
            ApkVerifier.Result result = new ApkVerifier.Builder(new File(path)).build().verify();
            if (!result.isVerified()) throw new IllegalArgumentException("Unverified APK: " + path);
            for (X509Certificate certificate : result.getSignerCertificates()) {
                byte[] encoded = certificate.getEncoded();
                StringBuilder sha = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(encoded)) sha.append(String.format("%02x", b & 255));
                System.out.println(new File(path).getName() + " sha256=" + sha + " androidSignatureHash=" + Integer.toHexString(Arrays.hashCode(encoded)));
            }
        }
    }
}
