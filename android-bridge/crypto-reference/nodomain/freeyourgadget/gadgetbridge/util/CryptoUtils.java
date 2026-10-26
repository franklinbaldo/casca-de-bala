/*  Copyright (C) 2021-2024 Andreas Shimokawa, Damien Gaignon, José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
// Test-only subset of upstream methods44..56; no Android dependencies.
package nodomain.freeyourgadget.gadgetbridge.util;
import javax.crypto.*;import javax.crypto.spec.SecretKeySpec;import java.security.*;
public final class CryptoUtils {private CryptoUtils(){}
    public static byte[] encryptAES(byte[] value, byte[] secretKey) throws InvalidKeyException, NoSuchPaddingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException {
        Cipher ecipher = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec newKey = new SecretKeySpec(secretKey, "AES");
        ecipher.init(Cipher.ENCRYPT_MODE, newKey);
        return ecipher.doFinal(value);
    }

    public static byte[] decryptAES(byte[] value, byte[] secretKey) throws InvalidKeyException, NoSuchPaddingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException {
        Cipher ecipher = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec newKey = new SecretKeySpec(secretKey, "AES");
        ecipher.init(Cipher.DECRYPT_MODE, newKey);
        return ecipher.doFinal(value);
    }

}
