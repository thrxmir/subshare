package org.subshare.local.persistence;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.jdo.Query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.Uid;
import co.codewizards.cloudstore.local.persistence.Dao;

public class UserIdentityDao extends Dao<UserIdentity, UserIdentityDao> {

	private static final Logger logger = LoggerFactory.getLogger(UserIdentityDao.class);

	public UserIdentity getUserIdentityOrFail(final Uid userIdentityId) {
		final UserIdentity userIdentity = getUserIdentity(userIdentityId);
		if (userIdentity == null)
			throw new IllegalArgumentException("There is no UserIdentity with this userIdentityId: " + userIdentityId);

		return userIdentity;
	}

	public UserIdentity getUserIdentity(final Uid userIdentityId) {
		assertNotNull(userIdentityId, "userIdentityId");
		final Query query = pm().newNamedQuery(getEntityClass(), "getUserIdentity_userIdentityId");
		try {
			final UserIdentity userIdentity = (UserIdentity) query.execute(userIdentityId.toString());
			return userIdentity;
		} finally {
			query.closeAll();
		}
	}

	public Collection<UserIdentity> getUserIdentitiesOf(final UserRepoKeyPublicKey ofUserRepoKeyPublicKey) {
		assertNotNull(ofUserRepoKeyPublicKey, "ofUserRepoKeyPublicKey");

		final Query query = pm().newNamedQuery(getEntityClass(), "getUserIdentities_ofUserRepoKeyPublicKey");
		try {
			final Map<String, Object> params = new HashMap<String, Object>(1);
			params.put("ofUserRepoKeyPublicKey", ofUserRepoKeyPublicKey);

			long startTimestamp = System.currentTimeMillis();
			@SuppressWarnings("unchecked")
			Collection<UserIdentity> result = (Collection<UserIdentity>) query.executeWithMap(params);
			logger.debug("getUserIdentitiesOf: query.execute(...) took {} ms.", System.currentTimeMillis() - startTimestamp);

			startTimestamp = System.currentTimeMillis();
			result = load(result);
			logger.debug("getUserIdentitiesOf: Loading result-set with {} elements took {} ms.", result.size(), System.currentTimeMillis() - startTimestamp);

			return result;
		} finally {
			query.closeAll();
		}
	}

	public Collection<UserIdentity> getUserIdentitiesChangedAfter(final long localRevision) {
		final Query query = pm().newNamedQuery(getEntityClass(), "getUserIdentitiesChangedAfter_localRevision");
		try {
			long startTimestamp = System.currentTimeMillis();
			@SuppressWarnings("unchecked")
			Collection<UserIdentity> result = (Collection<UserIdentity>) query.execute(localRevision);
			logger.debug("getUserIdentitiesChangedAfter: query.execute(...) took {} ms.", System.currentTimeMillis() - startTimestamp);

			startTimestamp = System.currentTimeMillis();
			result = load(result);
			logger.debug("getUserIdentitiesChangedAfter: Loading result-set with {} elements took {} ms.", result.size(), System.currentTimeMillis() - startTimestamp);

			return result;
		} finally {
			query.closeAll();
		}
	}

	@Override
	public void deletePersistent(UserIdentity entity) {
		deleteDependentObjects(entity);
		pm().flush();
		super.deletePersistent(entity);
	}

	@Override
	public void deletePersistentAll(Collection<? extends UserIdentity> entities) {
		for (UserIdentity userIdentity : entities)
			deleteDependentObjects(userIdentity);

		pm().flush();
		super.deletePersistentAll(entities);
	}

	protected void deleteDependentObjects(final UserIdentity userIdentity) {
		assertNotNull(userIdentity, "userIdentity");

		final UserIdentityLinkDao userIdentityLinkDao = getDao(UserIdentityLinkDao.class);
		userIdentityLinkDao.deletePersistentAll(userIdentityLinkDao.getUserIdentityLinksOf(userIdentity));
	}
}
