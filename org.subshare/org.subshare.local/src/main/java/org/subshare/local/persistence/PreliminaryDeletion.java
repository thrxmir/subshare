package org.subshare.local.persistence;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import javax.jdo.annotations.Index;
import javax.jdo.annotations.Inheritance;
import javax.jdo.annotations.InheritanceStrategy;
import javax.jdo.annotations.NullValue;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.Queries;
import javax.jdo.annotations.Query;
import javax.jdo.annotations.Unique;

import co.codewizards.cloudstore.local.persistence.Entity;

@PersistenceCapable
@Inheritance(strategy = InheritanceStrategy.NEW_TABLE)
@Unique(name = "UK_PreliminaryDeletion_cryptoRepoFile", members = "cryptoRepoFile")
@Index(name = "PreliminaryDeletion_cryptoRepoFile", members = "cryptoRepoFile")
@Queries({
	@Query(name = "getPreliminaryDeletion_cryptoRepoFile", value = "SELECT UNIQUE WHERE this.cryptoRepoFile == :cryptoRepoFile")
})
public class PreliminaryDeletion extends Entity {

	@Persistent(nullValue=NullValue.EXCEPTION)
	private CryptoRepoFile cryptoRepoFile;

	public CryptoRepoFile getCryptoRepoFile() {
		return cryptoRepoFile;
	}

	public void setCryptoRepoFile(CryptoRepoFile cryptoRepoFile) {
		this.cryptoRepoFile = assertNotNull("cryptoRepoFile", cryptoRepoFile);
	}
}
