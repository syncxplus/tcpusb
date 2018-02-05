package io.github.syncxplus.tcpusb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RSA PUBLIC KEY structure
 * @see <a href="https://android.googlesource.com/platform/system/core/+/master/libcrypto_utils/android_pubkey.c">android_pubkey.c</a>
 * <dl>
 * <dt>uint32_t modulus_size_words                     <dd>Modulus length: ANDROID_PUBKEY_MODULUS_SIZE_WORDS (ANDROID_PUBKEY_MODULUS_SIZE / 4)
 * <dt>uint32_t n0inv                                  <dd>Precomputed montgomery parameter: -1 / n[0] mod 2^32
 * <dt>uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE]    <dd>RSA modulus as a little-endian array
 * <dt>uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]         <dd>Montgomery parameter R^2 as a little-endian array of little-endian words
 * <dt>uint32_t exponent                               <dd>RSA modulus: 3 or 65537
 * </dl>
 * KEY size
 * @see <a href="https://android.googlesource.com/platform/system/core/+/master/libcrypto_utils/include/crypto_utils/android_pubkey.h">android_pubkey.h</a>
 * <code>ANDROID_PUBKEY_MODULUS_SIZE</code> is <code>(2048 / 8) = 256</code>
 * <code>ANDROID_PUBKEY_ENCODED_SIZE</code> is <code>(3 * sizeof(uint32_t) + 2 * ANDROID_PUBKEY_MODULUS_SIZE) = 524</code>
 */
public class AndroidPubKey {
    private static final Logger LOGGER = LoggerFactory.getLogger(AndroidPubKey.class);
    private static final Pattern RSA_KEY_PATTERN = Pattern.compile("^((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?)\0? (.*)\\s*$");
    private static final int ANDROID_PUBKEY_MODULUS_SIZE = 256;
    private static final int ANDROID_PUBKEY_ENCODED_SIZE = 524;
    private static final List<Integer> EXPONENT = new ArrayList<Integer>(){{
        add(3);
        add(65537);
    }};

    /**
     * @see <a href="http://www.rfc-editor.org/rfc/rfc2437.txt">RSASSA-PKCS1-V1_5-SIGN (K, M)</a>
     * @param key
     * @param token
     * @param signature
     * @return
     */
    public static boolean verify(String key, byte[] token, byte[] signature) {
        Matcher matcher = RSA_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            byte[] pubKeyDecoded = Base64.getDecoder().decode(matcher.group(1));
            if (pubKeyDecoded.length != ANDROID_PUBKEY_ENCODED_SIZE) {
                LOGGER.error("RSA public key length invalid: {}", pubKeyDecoded.length);
                return false;
            }
            int exponent = ByteBuffer
                    .wrap(pubKeyDecoded, ANDROID_PUBKEY_ENCODED_SIZE - Integer.BYTES, Integer.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            if (!EXPONENT.contains(exponent)) {
                LOGGER.error("RSA public key exponent invalid: {}", exponent);
                return false;
            }
            byte[] n = new byte[ANDROID_PUBKEY_MODULUS_SIZE];
            for (int i = 0; i < ANDROID_PUBKEY_MODULUS_SIZE; i ++) {
                n[i] = pubKeyDecoded[Integer.BYTES + Integer.BYTES + ANDROID_PUBKEY_MODULUS_SIZE - 1 - i];
            }
            BigInteger bigIntN = new BigInteger(1, n);
            RSAPublicKeySpec rsaKeySpec = new RSAPublicKeySpec(bigIntN, BigInteger.valueOf(exponent));
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PublicKey publicKey = keyFactory.generatePublic(rsaKeySpec);
                Cipher cipher = Cipher.getInstance("RSA");
                cipher.init(Cipher.DECRYPT_MODE, publicKey);
                byte[] sigDecrypted = cipher.doFinal(signature);
                return Arrays.equals(token, Arrays.copyOfRange(sigDecrypted,sigDecrypted.length - 20, sigDecrypted.length));
            } catch (Exception e) {
                LOGGER.error("RSA public key verification exception", e);
                return false;
            }
        } else {
            LOGGER.error("RSA public key invalid: {}", key);
            return false;
        }
    }
}
