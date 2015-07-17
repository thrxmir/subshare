package org.subshare.core.repo.metaonly;

import static co.codewizards.cloudstore.core.oio.OioFileFactory.*;
import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.subshare.core.Cryptree;
import org.subshare.core.CryptreeFactoryRegistry;
import org.subshare.core.dto.CryptoRepoFileDto;
import org.subshare.core.repo.ServerRepo;
import org.subshare.core.repo.ServerRepoManagerImpl;
import org.subshare.core.repo.ServerRepoRegistry;
import org.subshare.core.repo.ServerRepoRegistryImpl;
import org.subshare.core.server.Server;
import org.subshare.core.server.ServerRegistry;
import org.subshare.core.server.ServerRegistryImpl;
import org.subshare.core.sync.RepoSyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.Severity;
import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.dto.Error;
import co.codewizards.cloudstore.core.dto.RepoFileDto;
import co.codewizards.cloudstore.core.dto.Uid;
import co.codewizards.cloudstore.core.oio.File;
import co.codewizards.cloudstore.core.progress.LoggerProgressMonitor;
import co.codewizards.cloudstore.core.repo.local.LocalRepoHelper;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManagerFactory;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.core.repo.sync.RepoToRepoSync;
import co.codewizards.cloudstore.core.util.UrlUtil;

public class MetaOnlyRepoManagerImpl implements MetaOnlyRepoManager {

	private static final Logger logger = LoggerFactory.getLogger(MetaOnlyRepoManagerImpl.class);
	private static final UUID NULL_UUID = new UUID(0, 0);
	private final Map<File, UUID> localRoot2LocalRepositoryId = Collections.synchronizedMap(new HashMap<File, UUID>());

	private MetaOnlyRepoManagerImpl() { }

	private static final class Holder {
		public static final MetaOnlyRepoManager instance = new MetaOnlyRepoManagerImpl();
	}

	public static MetaOnlyRepoManager getInstance() {
		return Holder.instance;
	}

	@Override
	public List<RepoSyncState> sync() {
		final ServerRegistry serverRegistry = ServerRegistryImpl.getInstance();
		final ServerRepoRegistry serverRepoRegistry = ServerRepoRegistryImpl.getInstance();
		final List<ServerRepo> serverRepos = serverRepoRegistry.getServerRepos();
		final List<RepoSyncState> repoSyncStates = new ArrayList<RepoSyncState>(serverRepos.size());
		for (final ServerRepo serverRepo : serverRepos) {
			final Server server = serverRegistry.getServer(serverRepo.getServerId());
			if (server == null) {
				logger.warn("sync: serverRegistry does not know server with serverId={}!", serverRepo.getServerId());
				continue;
			}
			final File localRoot = getLocalRoot(serverRepo);
			final URL remoteRoot = getRemoteRoot(server, serverRepo);
			try {
				final long startTimestamp = System.currentTimeMillis();
				sync(server, serverRepo);
				final long durationMs = System.currentTimeMillis() - startTimestamp;
				repoSyncStates.add(new RepoSyncState(getLocalRepositoryId(localRoot, NULL_UUID),
						serverRepo, server, localRoot, remoteRoot,
						Severity.INFO, String.format("Sync took %s ms.", durationMs), null));
			} catch (Exception x) {
				repoSyncStates.add(new RepoSyncState(getLocalRepositoryId(localRoot, NULL_UUID),
						serverRepo, server, localRoot, remoteRoot,
						Severity.ERROR, x.getMessage(), new Error(x)));
			}
		}
		return repoSyncStates;
	}

	private UUID getLocalRepositoryId(final File localRoot, final UUID fallbackRepositoryId) {
		assertNotNull("localRoot", localRoot);
		final UUID result = localRoot2LocalRepositoryId.get(localRoot);
		if (result == null)
			return fallbackRepositoryId;
		else
			return result;
	}

	private void sync(final Server server, final ServerRepo serverRepo) {
		assertNotNull("server", server);
		assertNotNull("serverRepo", serverRepo);

		final List<URL> remoteRoots = new ArrayList<URL>();
		try (final LocalRepoManager localRepoManager = createLocalRepoManager(serverRepo);) {
			connectLocalRepositoryWithServerRepositoryIfNeeded(localRepoManager, server, serverRepo);

			for (final URL url : localRepoManager.getRemoteRepositoryId2RemoteRootMap().values())
				remoteRoots.add(url);
		}

		final File localRoot = getLocalRoot(serverRepo);
		for (final URL remoteRoot : remoteRoots) {
			try (RepoToRepoSync repoToRepoSync = RepoToRepoSync.create(localRoot, remoteRoot);) {
				repoToRepoSync.sync(new LoggerProgressMonitor(logger));
			}
		}
	}

	private void connectLocalRepositoryWithServerRepositoryIfNeeded(final LocalRepoManager localRepoManager, final Server server, final ServerRepo serverRepo) {
		if (localRepoManager.getRemoteRepositoryId2RemoteRootMap().isEmpty()) {
			final URL remoteRoot = getRemoteRoot(server, serverRepo);
			ServerRepoManagerImpl.connectLocalRepositoryWithServerRepository(localRepoManager, serverRepo.getRepositoryId(), remoteRoot);
		}
	}

	private static URL getRemoteRoot(Server server, ServerRepo serverRepo) {
		final URL remoteRoot = UrlUtil.appendNonEncodedPath(server.getUrl(), serverRepo.getRepositoryId().toString());
		return remoteRoot;
	}

	private File getLocalRoot(final ServerRepo serverRepo) {
		return createFile(ConfigDir.getInstance().getFile(), "metaOnlyRepo",
				serverRepo.getServerId().toString(), serverRepo.getRepositoryId().toString());
	}

	public LocalRepoManager createLocalRepoManager(final ServerRepo serverRepo) {
		final File localRoot = getLocalRoot(serverRepo);
		if (!localRoot.isDirectory())
			localRoot.mkdirs();

		if (!localRoot.isDirectory())
			throw new IllegalStateException(new IOException("Could not create directory: " + localRoot.getAbsolutePath()));

		final LocalRepoManager localRepoManager;
		if (LocalRepoHelper.getLocalRootContainingFile(localRoot) == null)
			localRepoManager = createLocalRepoManagerForNewRepository(serverRepo, localRoot);
		else
			localRepoManager = LocalRepoManagerFactory.Helper.getInstance().createLocalRepoManagerForExistingRepository(localRoot);

		localRoot2LocalRepositoryId.put(localRoot, localRepoManager.getRepositoryId());
		return localRepoManager;
	}

	private LocalRepoManager createLocalRepoManagerForNewRepository(final ServerRepo serverRepo, final File localRoot) {
		boolean successful = false;
		final LocalRepoManager localRepoManager = LocalRepoManagerFactory.Helper.getInstance().createLocalRepoManagerForNewRepository(localRoot);
		try {
			try (final LocalRepoTransaction transaction = localRepoManager.beginWriteTransaction();) {
				final Cryptree cryptree = CryptreeFactoryRegistry.getInstance().getCryptreeFactoryOrFail().getCryptreeOrCreate(transaction, serverRepo.getRepositoryId());
				cryptree.makeMetaOnly();

				// We must remove the Cryptree from the transaction, because this Cryptree thinks, it was on the server-side.
				// It does this, because we do not provide a UserRepoKeyRing (which usually never happens on the client-side).
				// This wrong assumption causes the VerifySignableAndWriteProtectedEntityListener to fail.
				transaction.removeContextObject(cryptree);
				transaction.commit();
			}
			successful = true;
		} finally {
			if (! successful)
				localRepoManager.close();
		}
		return localRepoManager;
	}

	@Override
	public ServerRepoFile getRootServerRepoFile(final ServerRepo serverRepo) {
		assertNotNull("serverRepo", serverRepo);
		// TODO implement!
		final CryptoRepoFileDto cryptoRepoFileDto;
		final RepoFileDto repoFileDto;
		try (final LocalRepoManager localRepoManager = createLocalRepoManager(serverRepo);) {
//			repoFileDto = localRepoManager.getRepoFileDto("");
//			cryptoRepoFileDto = localRepoManager.adapt(CcLocalRepoManager.class).getCryptoRepoFileDto("");

			// I think the following is bullshit. We better introduce some new API like drafted above.

//			try (final LocalRepoTransaction transaction = localRepoManager.beginReadTransaction();) {
////				final UserRepoKeyRingLookup userRepoKeyRingLookup = assertNotNull("cryptreeRestRepoTransportFactoryImpl.userRepoKeyRingLookup", repoTransportFactory.getUserRepoKeyRingLookup());
////				userRepoKeyRingLookup.getUserRepoKeyRing(new UserRepoKeyRingLookupContext(clientRepositoryId, serverRepositoryId));
//				final Cryptree cryptree = CryptreeFactoryRegistry.getInstance().getCryptreeFactoryOrFail()
//						.getCryptreeOrCreate(transaction, serverRepo.getRepositoryId());
//
//
//				// We must remove the Cryptree from the transaction, because this Cryptree thinks, it was on the server-side.
//				// It does this, because we do not provide a UserRepoKeyRing (which usually never happens on the client-side).
//				// This wrong assumption causes the VerifySignableAndWriteProtectedEntityListener to fail.
//				transaction.removeContextObject(cryptree);
//			}
		}
//		return new ServerRepoFileImpl(serverRepo, cryptoRepoFileDto, repoFileDto);
		throw new UnsupportedOperationException("NYI");
	}

	public List<ServerRepoFile> getChildServerRepoFiles(final Uid cryptoRepoFileId) {
		assertNotNull("cryptoRepoFileId", cryptoRepoFileId);

		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("NYI");
	}
}