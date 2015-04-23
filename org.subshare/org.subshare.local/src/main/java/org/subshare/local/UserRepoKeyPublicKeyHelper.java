package org.subshare.local;

import static co.codewizards.cloudstore.core.util.AssertUtil.assertNotNull;
import static org.subshare.local.CryptreeNodeUtil.encrypt;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.subshare.core.GrantAccessDeniedException;
import org.subshare.core.dto.PermissionType;
import org.subshare.core.dto.UserIdentityPayloadDto;
import org.subshare.core.dto.jaxb.UserIdentityPayloadDtoIo;
import org.subshare.core.user.User;
import org.subshare.core.user.UserRegistry;
import org.subshare.core.user.UserRepoKey;
import org.subshare.core.user.UserRepoKeyRing;
import org.subshare.local.persistence.InvitationUserRepoKeyPublicKey;
import org.subshare.local.persistence.Permission;
import org.subshare.local.persistence.PermissionDao;
import org.subshare.local.persistence.RepositoryOwner;
import org.subshare.local.persistence.UserIdentity;
import org.subshare.local.persistence.UserIdentityDao;
import org.subshare.local.persistence.UserRepoKeyPublicKey;
import org.subshare.local.persistence.UserRepoKeyPublicKeyDao;

import co.codewizards.cloudstore.core.dto.Uid;

public class UserRepoKeyPublicKeyHelper {

	private final CryptreeContext context;

	public UserRepoKeyPublicKeyHelper(final CryptreeContext context) {
		this.context = assertNotNull("context", context);
	}

	public CryptreeContext getContext() {
		return context;
	}

	public UserRepoKeyPublicKey getUserRepoKeyPublicKeyOrCreate(final UserRepoKey.PublicKey publicKey) {
		assertNotNull("publicKey", publicKey);
		final UserRepoKeyPublicKeyDao urkpkDao = context.transaction.getDao(UserRepoKeyPublicKeyDao.class);

		UserRepoKeyPublicKey userRepoKeyPublicKey = urkpkDao.getUserRepoKeyPublicKey(publicKey.getUserRepoKeyId());
		if (userRepoKeyPublicKey == null)
			userRepoKeyPublicKey = createUserRepoKeyPublicKey(publicKey);

		return userRepoKeyPublicKey;
	}

	private UserRepoKeyPublicKey createUserRepoKeyPublicKey(final UserRepoKey.PublicKey publicKey) {
		assertNotNull("publicKey", publicKey);
		final UserRepoKeyPublicKeyDao urkpkDao = context.transaction.getDao(UserRepoKeyPublicKeyDao.class);

		final UserRepoKeyPublicKey userRepoKeyPublicKey;
		if (publicKey.isInvitation()) {
			final UserRepoKey.PublicKeyWithSignature publicKeyWithSignature = (UserRepoKey.PublicKeyWithSignature) publicKey;
			userRepoKeyPublicKey = urkpkDao.makePersistent(new InvitationUserRepoKeyPublicKey(publicKeyWithSignature));
		}
		else
			userRepoKeyPublicKey = urkpkDao.makePersistent(new UserRepoKeyPublicKey(publicKey));

		createUserIdentities(userRepoKeyPublicKey);
		return userRepoKeyPublicKey;
	}

	public void createMissingUserIdentities() {
		boolean hasGrantPermission;
		try {
			getUserRepoKeyWithGrantPermissionOrFail();
			hasGrantPermission = true;
		} catch (GrantAccessDeniedException x) {
			hasGrantPermission = false;
		}

		final UserRepoKeyPublicKeyDao urkpkDao = context.transaction.getDao(UserRepoKeyPublicKeyDao.class);
		for (UserRepoKeyPublicKey userRepoKeyPublicKey : urkpkDao.getObjects()) {
			if (! hasGrantPermission) {
				final UserRepoKey userRepoKey = getContext().userRepoKeyRing.getUserRepoKey(userRepoKeyPublicKey.getUserRepoKeyId());
				if (userRepoKey == null)
					continue;
			}
			createUserIdentities(userRepoKeyPublicKey);
		}
	}

	private void createUserIdentities(final UserRepoKeyPublicKey userRepoKeyPublicKey) {
		assertNotNull("userRepoKeyPublicKey", userRepoKeyPublicKey);

		final UserIdentityDao uiDao = context.transaction.getDao(UserIdentityDao.class);
		final PermissionDao pDao = context.transaction.getDao(PermissionDao.class);

		final UserRepoKey userRepoKey = getContext().userRepoKeyRing.getUserRepoKey(userRepoKeyPublicKey.getUserRepoKeyId());
		final UserRepoKey signingUserRepoKey = userRepoKey != null ? userRepoKey : getUserRepoKeyWithGrantPermissionOrFail();

		final Set<UserRepoKeyPublicKey> forUserRepoKeyPublicKeys = new HashSet<>();
		for (final Permission permission : pDao.getNonRevokedPermissions(PermissionType.seeUserIdentity))
			forUserRepoKeyPublicKeys.add(permission.getUserRepoKeyPublicKey());

		// During the invitation hand-shake of a new user, the new user's repository does not have a repository-owner, yet.
		// Thus, the creation of the corresponding UserIdentity must be postponed.
		final RepositoryOwner repositoryOwner = context.getRepositoryOwner();
		if (repositoryOwner != null)
			forUserRepoKeyPublicKeys.add(repositoryOwner.getUserRepoKeyPublicKey());

		for (final UserRepoKeyPublicKey forUserRepoKeyPublicKey : forUserRepoKeyPublicKeys) {
			final Collection<UserIdentity> userIdentities = uiDao.getUserIdentities(userRepoKeyPublicKey, forUserRepoKeyPublicKey);
			if (!userIdentities.isEmpty())
				continue;

			final UserIdentity userIdentity = new UserIdentity();
			userIdentity.setOfUserRepoKeyPublicKey(userRepoKeyPublicKey);
			userIdentity.setForUserRepoKeyPublicKey(forUserRepoKeyPublicKey);
			userIdentity.setEncryptedUserIdentityPayloadDtoData(createEncryptedUserIdentityPayloadDtoData(userRepoKeyPublicKey, forUserRepoKeyPublicKey));
			context.getSignableSigner(signingUserRepoKey).sign(userIdentity);
			uiDao.makePersistent(userIdentity);
		}
	}

	private UserRepoKey getUserRepoKeyWithGrantPermissionOrFail() {
		final PermissionDao dao = context.transaction.getDao(PermissionDao.class);
		for (final UserRepoKey userRepoKey : context.userRepoKeyRing.getPermanentUserRepoKeys(context.serverRepositoryId)) {
			final boolean owner = isOwner(userRepoKey.getUserRepoKeyId());
			if (owner)
				return userRepoKey;

			final Collection<Permission> permissions = dao.getValidPermissions(PermissionType.grant, userRepoKey.getUserRepoKeyId(), new Date());
			if (!permissions.isEmpty())
				return userRepoKey;
		}
		throw new GrantAccessDeniedException("No UserRepoKey found having grant permissions!");
	}

	private boolean isOwner(final Uid userRepoKeyId) {
		assertNotNull("userRepoKeyId", userRepoKeyId);
		return userRepoKeyId.equals(context.getRepositoryOwnerOrFail().getUserRepoKeyPublicKey().getUserRepoKeyId());
	}

	private byte[] createEncryptedUserIdentityPayloadDtoData(final UserRepoKeyPublicKey ofUserRepoKeyPublicKey, final UserRepoKeyPublicKey forUserRepoKeyPublicKey) {
		final byte[] userIdentityPayloadDtoData = createUserIdentityPayloadDtoData(ofUserRepoKeyPublicKey);
		final byte[] encrypted = encrypt(userIdentityPayloadDtoData, forUserRepoKeyPublicKey.getPublicKey().getPublicKey());
		return encrypted;
	}

	private byte[] createUserIdentityPayloadDtoData(final UserRepoKeyPublicKey ofUserRepoKeyPublicKey) {
		final UserIdentityPayloadDto dto = createUserIdentityPayloadDto(ofUserRepoKeyPublicKey);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		new UserIdentityPayloadDtoIo().serialize(dto, out);
		return out.toByteArray();
	}

	private UserIdentityPayloadDto createUserIdentityPayloadDto(final UserRepoKeyPublicKey ofUserRepoKeyPublicKey) {
		final User user = getUserOrFail(ofUserRepoKeyPublicKey.getUserRepoKeyId());
		final UserIdentityPayloadDto result = new UserIdentityPayloadDto();
		result.getPgpKeyIds().addAll(user.getPgpKeyIds());
		return result;
	}

	private User getUserOrFail(final Uid userRepoKeyId) {
		final User user = getUser(userRepoKeyId);
		if (user == null)
			throw new IllegalArgumentException("No User found for userRepoKeyId=" + userRepoKeyId);

		return user;
	}

	private User getUser(final Uid userRepoKeyId) {
		assertNotNull("userRepoKeyId", userRepoKeyId);
		for (final User user : getUserRegistry().getUsers()) {
			final UserRepoKeyRing userRepoKeyRing = user.getUserRepoKeyRing();
			if (userRepoKeyRing != null) {
				if (userRepoKeyRing.getUserRepoKey(userRepoKeyId) != null)
					return user;
			}
			else {
				for (final UserRepoKey.PublicKey publicKey : user.getUserRepoKeyPublicKeys()) {
					if (userRepoKeyId.equals(publicKey.getUserRepoKeyId()))
						return user;
				}
			}
		}
		return null;
	}

	private UserRegistry getUserRegistry() {
		return context.getUserRegistry();
	}
}
