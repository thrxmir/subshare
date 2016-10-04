package org.subshare.core.pgp;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import java.util.Set;

import co.codewizards.cloudstore.core.auth.SignatureException;

public class MissingSigningPgpKeyException extends SignatureException {

	private static final long serialVersionUID = 1L;

	private final Set<PgpKeyId> missingPgpKeyIds;

	public MissingSigningPgpKeyException(Set<PgpKeyId> missingPgpKeyIds, String message) {
		super(message);
		this.missingPgpKeyIds = assertNotNull("missingPgpKeyIds", missingPgpKeyIds);
	}

	public Set<PgpKeyId> getMissingPgpKeyIds() {
		return missingPgpKeyIds;
	}
}
