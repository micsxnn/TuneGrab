import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

/** Check structured signing results, not localized CLI output (v3.1 CLI prints both SDK-targeted signers). */
public final class VerifySigning {
    private static String fingerprint(X509Certificate certificate) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())) {
            text.append(String.format("%02x", value & 0xff));
        }
        return text.toString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 4, "Usage: VerifySigning APK OLD_SHA256 NEW_SHA256 LINEAGE");
        String oldFingerprint = args[1], newFingerprint = args[2];
        SigningCertificateLineage lineage = SigningCertificateLineage.readFromFile(new File(args[3]));
        List<X509Certificate> certificates = lineage.getCertificatesInLineage();
        require(certificates.size() == 2, "Expected one explicit key rotation");
        require(fingerprint(certificates.get(0)).equals(oldFingerprint), "Incorrect lineage root");
        require(fingerprint(certificates.get(1)).equals(newFingerprint), "Incorrect lineage successor");
        require(!lineage.getSignerCapabilities(certificates.get(0)).hasRollback(), "Legacy rollback must be disabled");
        for (int[] range : new int[][]{{24, 27}, {28, 32}, {33, 37}}) {
            ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0]))
                .setMinCheckedPlatformVersion(range[0]).setMaxCheckedPlatformVersion(range[1]).build().verify();
            require(result.isVerified(), "APK verification failed for API " + range[0] + ": " + result.getErrors());
            X509Certificate selected;
            if (range[0] >= 33) {
                require(result.isVerifiedUsingV31Scheme(), "Android 13+ must verify the v3.1 signer");
                require(result.getV31SchemeSigners().size() == 1, "Ambiguous v3.1 signers");
                selected = result.getV31SchemeSigners().get(0).getCertificate();
                SigningCertificateLineage embedded = result.getSigningCertificateLineage();
                require(embedded != null && embedded.getCertificatesInLineage().equals(certificates), "Embedded lineage mismatch");
                require(!embedded.getSignerCapabilities(certificates.get(0)).hasRollback(), "Embedded legacy rollback enabled");
            } else if (range[0] >= 28) {
                require(result.isVerifiedUsingV3Scheme() && result.getV3SchemeSigners().size() == 1, "Expected one legacy v3 signer");
                selected = result.getV3SchemeSigners().get(0).getCertificate();
            } else {
                require(result.isVerifiedUsingV2Scheme() && result.getV2SchemeSigners().size() == 1, "Expected one legacy v2 signer");
                selected = result.getV2SchemeSigners().get(0).getCertificate();
            }
            String actual = fingerprint(selected);
            String expected = range[0] >= 33 ? newFingerprint : oldFingerprint;
            require(actual.equals(expected), "Incorrect signer for API " + range[0] + "-" + range[1]);
            System.out.println("Verified API " + range[0] + "-" + range[1] + " certificate SHA-256: " + actual);
        }
        System.out.println("Verified embedded key lineage; legacy rollback capability disabled.");
    }
}
