package org.subshare.core.dto;

import java.util.Date;
import java.util.UUID;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.subshare.core.sign.Signature;

import co.codewizards.cloudstore.core.dto.Uid;

@XmlRootElement
public class UserRepoKeyDto {

	private Uid userRepoKeyId;
	private UUID serverRepositoryId;
	private Date validTo;
	private byte[] encryptedSignedPrivateKeyData;
	private byte[] signedPublicKeyData;
	private boolean invitation;

	@XmlElement
	private SignatureDto publicKeySignature;

	public Uid getUserRepoKeyId() {
		return userRepoKeyId;
	}
	public void setUserRepoKeyId(Uid userRepoKeyId) {
		this.userRepoKeyId = userRepoKeyId;
	}

	public UUID getServerRepositoryId() {
		return serverRepositoryId;
	}
	public void setServerRepositoryId(UUID serverRepositoryId) {
		this.serverRepositoryId = serverRepositoryId;
	}

	public Date getValidTo() {
		return validTo;
	}
	public void setValidTo(Date validTo) {
		this.validTo = validTo;
	}

	public boolean isInvitation() {
		return invitation;
	}
	public void setInvitation(boolean invitation) {
		this.invitation = invitation;
	}

	public byte[] getEncryptedSignedPrivateKeyData() {
		return encryptedSignedPrivateKeyData;
	}
	public void setEncryptedSignedPrivateKeyData(byte[] encryptedSignedPrivateKeyData) {
		this.encryptedSignedPrivateKeyData = encryptedSignedPrivateKeyData;
	}

	public byte[] getSignedPublicKeyData() {
		return signedPublicKeyData;
	}
	public void setSignedPublicKeyData(byte[] signedPublicKeyData) {
		this.signedPublicKeyData = signedPublicKeyData;
	}

	@XmlTransient
	public Signature getPublicKeySignature() {
		return publicKeySignature;
	}
	public void setPublicKeySignature(Signature publicKeySignature) {
		this.publicKeySignature = SignatureDto.copyIfNeeded(publicKeySignature);
	}
}
