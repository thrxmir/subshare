package org.subshare.core.repair;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.config.Config;
import co.codewizards.cloudstore.core.config.ConfigImpl;

public class RepairDeleteCollisionConfig {

	private static final Logger logger = LoggerFactory.getLogger(RepairDeleteCollisionConfig.class);

	/**
	 * Config-key referencing the time (including) from which on collisions are deleted.
	 * <p>
	 * The config property value must be ISO8601-encoded -- it is read via {@link Config#getPropertyAsDate(String, Date)}.
	 */
	public static final String CONFIG_KEY_DELETE_COLLISIONS_FROM = "repair.deleteCollisions.from";
	/**
	 * Config-key referencing the time (excluding) until which collisions are deleted.
	 * <p>
	 * The config property value must be ISO8601-encoded -- it is read via {@link Config#getPropertyAsDate(String, Date)}.
	 */
	public static final String CONFIG_KEY_DELETE_COLLISIONS_TO = "repair.deleteCollisions.to";

//	private static final class DeleteCollisionConfigHolder {
//		public static final RepairDeleteCollisionConfig instance = new RepairDeleteCollisionConfig();
//	}
//
//	public static RepairDeleteCollisionConfig getInstance() {
//
//	}

	/**
	 * Gets the time (including) from which on collisions are deleted.
	 * <p>
	 * Only, if both {@link #getDeleteCollisionsFrom()} and {@link #getDeleteCollisionsTo()}
	 * are non-<code>null</code>, deletions actually occur. If one of them is <code>null</code> and the
	 * other non-<code>null</code>, a warning is logged -- and nothing done.
	 * @return the time (including) from which on this collisions are deleted. May be <code>null</code>.
	 */
	public static Date getDeleteCollisionsFrom() {
		Config config = ConfigImpl.getInstance();
		return config.getPropertyAsDate(CONFIG_KEY_DELETE_COLLISIONS_FROM, null);
	}

	/**
	 * Gets the time (excluding) until which collisions are deleted.
	 * <p>
	 * Only, if both {@link #getDeleteCollisionsFrom()} and {@link #getDeleteCollisionsTo()}
	 * are non-<code>null</code>, deletions actually occur. If one of them is <code>null</code> and the
	 * other non-<code>null</code>, a warning is logged -- and nothing done.
	 * @return the time (excluding) until which collisions are deleted. May be <code>null</code>.
	 */
	public static Date getDeleteCollisionsTo() {
		Config config = ConfigImpl.getInstance();
		return config.getPropertyAsDate(CONFIG_KEY_DELETE_COLLISIONS_TO, null);
	}

	public static final boolean isCreateCollisionSuppressed() {
		final Date deleteCollisionsFrom = getDeleteCollisionsFrom();
		final Date deleteCollisionsTo = getDeleteCollisionsTo();
		if (deleteCollisionsFrom == null && deleteCollisionsTo == null)
			return false;

		if (deleteCollisionsFrom == null || deleteCollisionsTo == null) {
			logger.warn("isCreateCollisionSuppressed: Only one of the two properties '{}' and '{}' is set! To enable the collision suppression, both must be set and the current timestamp must match. Ignoring this completely and returning false!",
					CONFIG_KEY_DELETE_COLLISIONS_FROM, CONFIG_KEY_DELETE_COLLISIONS_TO);
			return false;
		}

		final Date now = new Date();
		if (now.before(deleteCollisionsFrom))
			return false; // now < from

		// not returned, thus: now >= from

		if (now.before(deleteCollisionsTo))
			return true; // from <= now < to

		// not returned, thus: now >= to
		return false;
	}

	/**
	 * Get the [fromIncl, toExcl] date range to delete collisions -- or <code>null</code>.
	 * @return the [fromIncl, toExcl] date range to delete collisions -- or <code>null</code>.
	 * The returned array always contains 2 non-<code>null</code> elements. If one of the two dates
	 * is <code>null</code>, this method returns <code>null</code>, i.e. no array at all.
	 */
	public static Date[] getDeleteCollisionsFromInclToExclRange() {
		final Date deleteCollisionsFrom = getDeleteCollisionsFrom();
		final Date deleteCollisionsTo = getDeleteCollisionsTo();
		if (deleteCollisionsFrom == null && deleteCollisionsTo == null)
			return null;

		if (deleteCollisionsFrom == null || deleteCollisionsTo == null) {
			logger.warn("getDeleteCollisionsFromInclToExclRange: Only one of the two properties '{}' and '{}' is set! To enable the collision deletion, both must be set. Ignoring this completely and returning null!",
					CONFIG_KEY_DELETE_COLLISIONS_FROM, CONFIG_KEY_DELETE_COLLISIONS_TO);
			return null;
		}
		return new Date[] { deleteCollisionsFrom, deleteCollisionsTo };
	}
}
