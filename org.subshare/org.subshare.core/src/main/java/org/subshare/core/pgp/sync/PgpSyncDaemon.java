package org.subshare.core.pgp.sync;

import org.subshare.core.sync.SyncDaemon;

public interface PgpSyncDaemon extends SyncDaemon {
	public static interface Property extends SyncDaemon.Property {
	}

	public static enum PropertyEnum implements Property {
	}
}
