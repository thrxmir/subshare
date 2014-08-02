package org.subshare.core.crypto;

import static co.codewizards.cloudstore.core.util.Util.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.subshare.core.dto.CryptoKeyType;

/**
 * Supported cipher transformations.
 * <p>
 * Each cipher transformation can either be asymmetric or symmetric.
 * <p>
 * Symmetric transformations are expected to cope with arbitrary length of plain-text data. This means, they
 * are usually a combination of a symmetric block cipher, a mode of operation (e.g. CBC or CFB) and - if
 * needed - a padding.
 * <p>
 * Asymmetric transformations are expected to encrypt a symmetric key, only (i.e. a pretty short single block
 * of data).
 * @author Marco หงุ่ยตระกูล-Schulze - marco at codewizards dot co
 */
public enum CipherTransformation {
	// IMPORTANT: Do *not* modify the order of these enum values and do not insert new enum values inbetween!
	// You may add new values at the end. These enum values are encoded in encrypted data via their ordinal
	// (see java.lang.Enum.ordinal()). Changing the ordinal (= index) of existing values therefore causes
	// encrypted data to not be readable anymore!!!

	AES_CBC_PKCS5PADDING(CryptoKeyType.symmetric, "AES/CBC/PKCS5Padding"),
	AES_CBC_PKCS7PADDING(CryptoKeyType.symmetric, "AES/CBC/PKCS7Padding"),
	AES_CFB_NOPADDING(CryptoKeyType.symmetric, "AES/CFB/NoPadding"),

	TWOFISH_CBC_PKCS5PADDING(CryptoKeyType.symmetric, "Twofish/CBC/PKCS5Padding"),
	TWOFISH_CBC_PKCS7PADDING(CryptoKeyType.symmetric, "Twofish/CBC/PKCS7Padding"),
	TWOFISH_CFB_NOPADDING(CryptoKeyType.symmetric, "Twofish/CFB/NoPadding"),

	RSA_OAEPWITHSHA1ANDMGF1PADDING(CryptoKeyType.asymmetric, "RSA//OAEPWITHSHA1ANDMGF1PADDING")
	;

	private static final Map<String, CipherTransformation> transformation2SymmetricCipherTransformation;
	static {
		final Map<String, CipherTransformation> m = new HashMap<String, CipherTransformation>(values().length);
		for (final CipherTransformation seTransformation : values()) {
			m.put(seTransformation.getTransformation(), seTransformation);
		}
		transformation2SymmetricCipherTransformation = Collections.unmodifiableMap(m);
	}

	private final CryptoKeyType type;
	private final String transformation;

	private CipherTransformation(final CryptoKeyType type, final String transformation) {
		this.type = assertNotNull("type", type);
		this.transformation = assertNotNull("transformation", transformation);
	}

	public String getTransformation() {
		return transformation;
	}

	public CryptoKeyType getType() {
		return type;
	}

	public static CipherTransformation fromTransformation(final String transformation) {
		assertNotNull("transformation", transformation);
		final CipherTransformation seTransformation = transformation2SymmetricCipherTransformation.get(transformation);
		if (seTransformation == null)
			throw new IllegalArgumentException("There is no CipherTransformation for this transformation: " + transformation);

		return seTransformation;
	}

}
