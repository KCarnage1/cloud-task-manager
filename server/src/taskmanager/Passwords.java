package taskmanager;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256 with a random salt per password. The stored form is "iterations$salt$hash". */
public final class Passwords {
    static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();

    private Passwords() {}

    public static String hash(String password) { return hash(password, ITERATIONS); }

    static String hash(String password, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return iterations + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(derive(password, salt, iterations));
    }

    public static boolean verify(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 3) return false;
        try {
            byte[] expected = Base64.getDecoder().decode(p[2]);
            byte[] actual = derive(password, Base64.getDecoder().decode(p[1]), Integer.parseInt(p[0]));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Used to spend the same time on an unknown username as on a wrong password. */
    static final String DUMMY = hash("not-a-real-password");
}
