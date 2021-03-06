package org.subshare.local.dbrepo.transport;

import static co.codewizards.cloudstore.core.objectfactory.ObjectFactoryUtil.*;
import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static co.codewizards.cloudstore.core.util.StringUtil.*;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subshare.core.Cryptree;
import org.subshare.core.CryptreeFactoryRegistry;
import org.subshare.core.dto.CurrentHistoCryptoRepoFileDto;
import org.subshare.core.dto.SsDeleteModificationDto;
import org.subshare.core.dto.SsDirectoryDto;
import org.subshare.core.dto.SsNormalFileDto;
import org.subshare.core.dto.SsRepoFileDto;
import org.subshare.core.dto.SsSymlinkDto;
import org.subshare.core.repo.transport.CryptreeServerFileRepoTransport;
import org.subshare.local.dto.CurrentHistoCryptoRepoFileDtoConverter;
import org.subshare.local.persistence.CurrentHistoCryptoRepoFile;
import org.subshare.local.persistence.FileChunkPayload;
import org.subshare.local.persistence.FileChunkPayloadDao;
import org.subshare.local.persistence.HistoCryptoRepoFile;
import org.subshare.local.persistence.HistoCryptoRepoFileDao;
import org.subshare.local.persistence.HistoFileChunk;
import org.subshare.local.persistence.HistoFileChunkDao;
import org.subshare.local.persistence.LastCryptoKeySyncFromRemoteRepo;
import org.subshare.local.persistence.LastCryptoKeySyncFromRemoteRepoDao;
import org.subshare.local.persistence.SsDirectory;
import org.subshare.local.persistence.SsNormalFile;
import org.subshare.local.persistence.SsSymlink;
import org.subshare.local.persistence.TempFileChunk;
import org.subshare.local.persistence.TempFileChunkDao;

import co.codewizards.cloudstore.core.Uid;
import co.codewizards.cloudstore.core.dto.ChangeSetDto;
import co.codewizards.cloudstore.core.oio.File;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.core.repo.transport.DeleteModificationCollisionException;
import co.codewizards.cloudstore.local.LocalRepoSync;
import co.codewizards.cloudstore.local.persistence.Directory;
import co.codewizards.cloudstore.local.persistence.FileChunk;
import co.codewizards.cloudstore.local.persistence.NormalFile;
import co.codewizards.cloudstore.local.persistence.RemoteRepository;
import co.codewizards.cloudstore.local.persistence.RemoteRepositoryDao;
import co.codewizards.cloudstore.local.persistence.RepoFile;
import co.codewizards.cloudstore.local.persistence.RepoFileDao;
import co.codewizards.cloudstore.local.persistence.Symlink;
import co.codewizards.cloudstore.local.transport.FileRepoTransport;

public class DbFileRepoTransportImpl extends FileRepoTransport implements CryptreeServerFileRepoTransport {

	private static final Logger logger = LoggerFactory.getLogger(DbFileRepoTransportImpl.class);

//	/**
//	 * This delegate is used on the client-side.
//	 */
//	private CryptreeFileRepoTransportImpl delegateOnClient;
//
//	protected CryptreeFileRepoTransportImpl getDelegateOnClient() {
//		if (delegateOnClient == null) {
//			final CryptreeFileRepoTransportFactoryImpl factory = RepoTransportFactoryRegistry.getInstance().getRepoTransportFactoryOrFail(CryptreeFileRepoTransportFactoryImpl.class);
//			delegateOnClient = (CryptreeFileRepoTransportImpl) factory.createRepoTransport(getRemoteRoot(), getClientRepositoryIdOrFail());
//		}
//		return delegateOnClient;
//	}

	@Override
	public void delete(final SsDeleteModificationDto deleteModificationDto) {
		assertNotNull(deleteModificationDto, "deleteModificationDto");
//		if (! isOnServer())
//			throw new IllegalStateException("This method should only be invoked on the server!");

		final UUID clientRepositoryId = assertNotNull(getClientRepositoryId(), "clientRepositoryId");

		final LocalRepoManager localRepoManager = getLocalRepoManager();
		try (final LocalRepoTransaction transaction = localRepoManager.beginWriteTransaction();) {
			final String path = deleteModificationDto.getServerPath();
			final File file = getFile(path); // we *must* *not* prefix this path! It is absolute inside the repository (= relative to the repository's root - not the connection point)!

			final RepoFile repoFile = transaction.getDao(RepoFileDao.class).getRepoFile(getLocalRepoManager().getLocalRoot(), file);
			if (repoFile == null)
				return; // already deleted (probably by other client) => no need to do anything (and a DB rollback is fine)

			// We check first, if the file exists, because we cannot check the validity of the signature (more precisely the
			// permissions), if the parent was deleted.

			final Cryptree cryptree = CryptreeFactoryRegistry.getInstance().getCryptreeFactoryOrFail().getCryptreeOrCreate(transaction, clientRepositoryId);
			cryptree.assertSignatureOk(deleteModificationDto);

			final boolean collision = detectFileCollisionRecursively(transaction, clientRepositoryId, file);
			if (collision) // TODO find out what exactly was modified and return these more precise infos to the client!
				throw new DeleteModificationCollisionException(String.format("Collision in repository %s: The file/directory '%s' cannot be deleted, because it was modified by someone else in the meantime.", localRepoManager.getRepositoryId(), path));

//			createAndPersistDeleteModifications(transaction, deleteModificationDto);

			final LocalRepoSync localRepoSync = LocalRepoSync.create(transaction);
			localRepoSync.deleteRepoFile(repoFile, false);

			transaction.commit();
		}
	}

	@Override
	public void delete(String path) {
//		if (! isOnClient())
			throw new IllegalStateException("This method should only be invoked on the client!");

//		getDelegateOnClient().delete(path);
	}

//	private void createAndPersistDeleteModifications(final LocalRepoTransaction transaction, final SsDeleteModificationDto deleteModificationDto) {
//		assertNotNull("transaction", transaction);
//		assertNotNull("deleteModificationDto", deleteModificationDto);
//		final RemoteRepositoryDao remoteRepositoryDao = transaction.getDao(RemoteRepositoryDao.class);
//		final DeleteModificationDao deleteModificationDao = transaction.getDao(DeleteModificationDao.class);
//
//		for (final RemoteRepository remoteRepository : remoteRepositoryDao.getObjects()) {
//			if (!getClientRepositoryIdOrFail().equals(remoteRepository.getRepositoryId())) {
//				SsDeleteModification deleteModification = toDeleteModification(transaction, deleteModificationDto, remoteRepository);
//				deleteModification = deleteModificationDao.makePersistent(deleteModification);
//			}
//		}
//	}

//	private SsDeleteModification toDeleteModification(final LocalRepoTransaction transaction, final SsDeleteModificationDto deleteModificationDto, final RemoteRepository remoteRepository) {
//		assertNotNull("transaction", transaction);
//		assertNotNull("deleteModificationDto", deleteModificationDto);
//		assertNotNull("remoteRepository", remoteRepository);
//
//		final SsDeleteModification deleteModification = new SsDeleteModification();
//		deleteModification.setRemoteRepository(remoteRepository);
//		deleteModification.setServerPath(deleteModificationDto.getServerPath());
//		deleteModification.setPath(deleteModificationDto.getServerPath()); // the field does not allow null => must set it to a non-null value.
//		deleteModification.setCryptoRepoFileIdControllingPermissions(deleteModificationDto.getCryptoRepoFileIdControllingPermissions());
//		deleteModification.setLength(-1);
//		deleteModification.setSignature(deleteModificationDto.getSignature());
//		return deleteModification;
//	}

	@Override
	public void makeDirectory(String path, Date lastModified) {
		throw new IllegalStateException("This method should not be invoked on the server!");
	}

	@Override
	public void makeDirectory(String path, final SsDirectoryDto directoryDto, final CurrentHistoCryptoRepoFileDto currentHistoCryptoRepoFileDto) {
		assertNotNull(path, "path");
		assertNotNull(directoryDto, "directoryDto");
		assertNotNull(currentHistoCryptoRepoFileDto, "currentHistoCryptoRepoFileDto");
		assertNotNull(currentHistoCryptoRepoFileDto.getHistoCryptoRepoFileDto(), "currentHistoCryptoRepoFileDto.histoCryptoRepoFileDto");

		path = prefixPath(path);
		final File file = getFile(path); // null-check already inside getFile(...) - no need for another check here
		final File parentFile = file.getParentFile();
		final File localRoot = getLocalRepoManager().getLocalRoot();
		final UUID clientRepositoryId = getClientRepositoryIdOrFail();

		assertThatUnsignedPathMatchesSignedSsRepoFileDto(path, file, parentFile, directoryDto);

		final boolean isRoot = file.equals(localRoot); // we continue even with the root (even though this *always* exists) to allow for updating timestamps and similar.

		try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginWriteTransaction(); ) {
			final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);

			final RepoFile parentRepoFile = isRoot ? null : repoFileDao.getRepoFile(localRoot, parentFile);
			if (! isRoot) {
				assertNotNull(parentRepoFile, "parentRepoFile"); // TODO or is this a collision which requires a special exception?! Might be an attack, too: We sign only the direct parent - parents of parents (in path) are not signed. Hence, this might be a broken path!

				if (! (parentRepoFile instanceof Directory))
					throw new IllegalStateException("parentRepoFile is no Directory! " + parentRepoFile); // TODO collision?! special handling?!
			}

			// TODO detect collisions! - sure?! We cannot handle collisions on the server!
			RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
			if (repoFile != null && ! (repoFile instanceof Directory)) {
				repoFileDao.deletePersistent(repoFile);
				repoFile = null;
			}

			SsDirectory directory = (SsDirectory) repoFile;
			if (directory == null) {
				repoFile = directory = createObject(SsDirectory.class);
				directory.setParent(parentRepoFile);
				directory.setName(directoryDto.getName());
			}
			directory.setLastModified(SsRepoFileDto.DUMMY_LAST_MODIFIED); // no need in Subshare ;-) (but there's a not-null-constraint in the DB)
			directory.setLastSyncFromRepositoryId(clientRepositoryId);
			directory.setSignature(directoryDto.getSignature());

			repoFileDao.makePersistent(directory); // just in case, it is not yet persistent ;-) if it already is, this is a no-op.

			CurrentHistoCryptoRepoFile currentHistoCryptoRepoFile = CurrentHistoCryptoRepoFileDtoConverter.create(transaction).putCurrentHistoCryptoRepoFile(currentHistoCryptoRepoFileDto);
			currentHistoCryptoRepoFile.setLastSyncFromRepositoryId(clientRepositoryId);

			transaction.commit();
		}
	}

	private void assertThatUnsignedPathMatchesSignedSsRepoFileDto(String path, File file, File parentFile, SsRepoFileDto repoFileDto) {
		final File localRoot = getLocalRepoManager().getLocalRoot();
		if (file.equals(localRoot) || parentFile.equals(localRoot)) {
			if (! isEmpty(repoFileDto.getParentName()))
				throw new IllegalStateException(String.format("path references localRoot, but repoFileDto.parentName is not empty! repoFileDto.parentName='%s' repoFileDto.name='%s' path='%s'",
						repoFileDto.getParentName(), repoFileDto.getName(), path));
		}
		else {
			if (! repoFileDto.getParentName().equals(parentFile.getName()))
				throw new IllegalStateException(String.format("path does not match repoFileDto.parentName! repoFileDto.parentName='%s' parentFile.name='%s' repoFileDto.name='%s' path='%s'",
						repoFileDto.getParentName(), parentFile.getName(), repoFileDto.getName(), path));

		}

		if (file.equals(localRoot)) {
			if (! isEmpty(repoFileDto.getName()))
				throw new IllegalStateException(String.format("path references localRoot, but repoFileDto.name is not empty! repoFileDto.name='%s' path='%s'",
						repoFileDto.getName(), path));
		}
		else {
			if (! repoFileDto.getName().equals(file.getName())) // or does this need to be the realName?!
				throw new IllegalStateException(String.format("path does not match repoFileDto.name! repoFileDto.name='%s' file.name='%s' path='%s'",
						repoFileDto.getName(), file.getName(), path));
		}
	}

	@Override
	public void makeSymlink(String path, String target, Date lastModified) {
		throw new IllegalStateException("This method should not be invoked on the server!");
	}

	@Override
	public void makeSymlink(String path, SsSymlinkDto symlinkDto, CurrentHistoCryptoRepoFileDto currentHistoCryptoRepoFileDto) {
		assertNotNull(path, "path");
		assertNotNull(symlinkDto, "symlinkDto");
		assertNotNull(currentHistoCryptoRepoFileDto, "currentHistoCryptoRepoFileDto");
		assertNotNull(currentHistoCryptoRepoFileDto.getHistoCryptoRepoFileDto(), "currentHistoCryptoRepoFileDto.histoCryptoRepoFileDto");

		path = prefixPath(path);
		final File file = getFile(path); // null-check already inside getFile(...) - no need for another check here
		final File parentFile = file.getParentFile();
		final File localRoot = getLocalRepoManager().getLocalRoot();
		final UUID clientRepositoryId = getClientRepositoryIdOrFail();

		assertThatUnsignedPathMatchesSignedSsRepoFileDto(path, file, parentFile, symlinkDto);

		final boolean isRoot = file.equals(localRoot); // we continue even with the root (even though this *always* exists) to allow for updating timestamps and similar.

		try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginWriteTransaction(); ) {
			final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);

			final RepoFile parentRepoFile = isRoot ? null : repoFileDao.getRepoFile(localRoot, parentFile);
			if (! isRoot) {
				assertNotNull(parentRepoFile, "parentRepoFile"); // TODO or is this a collision which requires a special exception?! Might be an attack, too: We sign only the direct parent - parents of parents (in path) are not signed. Hence, this might be a broken path!

				if (! (parentRepoFile instanceof Directory))
					throw new IllegalStateException("parentRepoFile is no Directory! " + parentRepoFile); // TODO collision?! special handling?!
			}

			// TODO detect collisions! - sure?! We cannot handle collisions on the server!
			RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
			if (repoFile != null && ! (repoFile instanceof Symlink)) {
				repoFileDao.deletePersistent(repoFile);
				repoFile = null;
			}

			SsSymlink symlink = (SsSymlink) repoFile;
			if (symlink == null) {
				repoFile = symlink = createObject(SsSymlink.class);
				symlink.setParent(parentRepoFile);
				symlink.setName(symlinkDto.getName());
			}
			symlink.setLastModified(new Date(0)); // no need in Subshare ;-) (but there's a not-null-constraint in the DB)
			symlink.setTarget(symlinkDto.getTarget()); // no need in Subshare ;-) (but there's a not-null-constraint in the DB)
			symlink.setLastSyncFromRepositoryId(clientRepositoryId);
			symlink.setSignature(symlinkDto.getSignature());

			repoFileDao.makePersistent(symlink); // just in case, it is not yet persistent ;-) if it already is, this is a no-op.

			CurrentHistoCryptoRepoFile currentHistoCryptoRepoFile = CurrentHistoCryptoRepoFileDtoConverter.create(transaction).putCurrentHistoCryptoRepoFile(currentHistoCryptoRepoFileDto);
			currentHistoCryptoRepoFile.setLastSyncFromRepositoryId(clientRepositoryId);

			transaction.commit();
		}

	}

	@Override
	public void beginPutFile(final String path) {
		throw new IllegalStateException("This method should not be invoked on the server!");
	}

	@Override
	public void beginPutFile(String path, final SsNormalFileDto normalFileDto) {
		assertNotNull(normalFileDto, "normalFileDto");

		path = prefixPath(path);
		final File file = getFile(path); // null-check already inside getFile(...) - no need for another check here
		final UUID clientRepositoryId = getClientRepositoryIdOrFail();
		final File parentFile = file.getParentFile();
		final File localRoot = getLocalRepoManager().getLocalRoot();

		assertThatUnsignedPathMatchesSignedSsRepoFileDto(path, file, parentFile, normalFileDto);

		try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginWriteTransaction(); ) {
			assertNoDeleteModificationCollision(transaction, clientRepositoryId, path);
			// TODO detect all types of collisions! And check, whether the above method actually works in Subshare!

			final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);
			final RepoFile parentRepoFile = repoFileDao.getRepoFile(localRoot, parentFile);
			assertNotNull(parentRepoFile, "parentRepoFile"); // TODO or is this a collision which requires a special exception?! Might be an attack, too: We sign only the direct parent - parents of parents (in path) are not signed. Hence, this might be a broken path!

			if (! (parentRepoFile instanceof Directory))
				throw new IllegalStateException("parentRepoFile is no Directory! " + parentRepoFile); // TODO collision?! special handling?!

			RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
			if (repoFile != null && ! (repoFile instanceof NormalFile)) {
				repoFileDao.deletePersistent(repoFile);
				repoFile = null;
			}

			SsNormalFile normalFile = (SsNormalFile) repoFile;
			if (normalFile == null) {
				repoFile = normalFile = createObject(SsNormalFile.class);
				normalFile.setParent(parentRepoFile);
				normalFile.setName(normalFileDto.getName());
			}
			normalFile.setLength(normalFileDto.getLength()); // this is the length-with-padding, actually! not the real length!
//			normalFile.setLengthWithPadding(normalFileDto.getLengthWithPadding()); // this is not used outside encrypted data.
			normalFile.setSha1("X"); // we don't store this on the server-side in Subshare for security reasons! but there's a not-null-constraint.
			normalFile.setLastModified(normalFileDto.getLastModified()); // this is always a dummy-value, but it's part of the signature => copy it.
			normalFile.setSignature(normalFileDto.getSignature());

//			if (!newFile && !normalFile.isInProgress()) // TODO collision detection?!
//				detectAndHandleFileCollision(transaction, clientRepositoryId, file, normalFile);

			normalFile.setLastSyncFromRepositoryId(clientRepositoryId);
			normalFile.setInProgress(true);

			repoFileDao.makePersistent(normalFile); // just in case, it is not yet persistent ;-) if it already is, this is a no-op.

			transaction.commit();
		}
	}

	@Override
	public void putFileData(String path, long offset, byte[] fileData) {
//		if (isOnClient())
//			getDelegateOnClient().putFileData(path, offset, fileData);
//		else {
			path = prefixPath(path);
			logger.info("putFileData: path='{}' offset={}", path, offset);

			final File file = getFile(path); // null-check already inside getFile(...) - no need for another check here
			final UUID clientRepositoryId = getClientRepositoryIdOrFail();
			final File localRoot = getLocalRepoManager().getLocalRoot();

			try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginWriteTransaction(); ) {
				final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);
				final TempFileChunkDao tempFileChunkDao = transaction.getDao(TempFileChunkDao.class);
				final FileChunkPayloadDao fileChunkPayloadDao = transaction.getDao(FileChunkPayloadDao.class);

				final RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
				assertNotNull(repoFile, "repoFile"); // TODO or is this a collision which requires a special exception?!

				final NormalFile normalFile = (NormalFile) repoFile; // TODO maybe another collision?!

				TempFileChunk tempFileChunk = tempFileChunkDao.getTempFileChunk(normalFile, clientRepositoryId, offset);
				if (tempFileChunk == null) {
					tempFileChunk = new TempFileChunk();
					tempFileChunk.setNormalFile(normalFile);
					tempFileChunk.setRemoteRepositoryId(clientRepositoryId);
					tempFileChunk.setRole(TempFileChunk.Role.RECEIVING);
					tempFileChunk.setOffset(offset);
					tempFileChunk.setLength(fileData.length);
					tempFileChunk = tempFileChunkDao.makePersistent(tempFileChunk);
				}
				else
					tempFileChunk.setLength(fileData.length);

				FileChunkPayload fileChunkPayload = fileChunkPayloadDao.getFileChunkPayload(tempFileChunk);
				if (fileChunkPayload == null) {
					fileChunkPayload = new FileChunkPayload();
					fileChunkPayload.setTempFileChunk(tempFileChunk);
				}
				fileChunkPayload.setFileData(fileData);
				fileChunkPayloadDao.makePersistent(fileChunkPayload); // just in case, it is not yet persistent ;-) if it already is, this is a no-op.

				transaction.commit();
			}
//		}
	}

	@Override
	public byte[] getFileData(String path, long offset, int length) {
//		if (isOnClient())
//			return getDelegateOnClient().getFileData(path, offset, length);
//		else {
			path = prefixPath(path);
			logger.info("getFileData: path='{}' offset={}", path, offset);

			// length is ignored!
			final File file = getFile(path); // null-check already inside getFile(...) - no need for another check here
			final File localRoot = getLocalRepoManager().getLocalRoot();

			try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginReadTransaction(); ) {
				final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);
				final FileChunkPayloadDao fileChunkPayloadDao = transaction.getDao(FileChunkPayloadDao.class);

				final RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
				assertNotNull(repoFile, "repoFile"); // TODO or is this a collision which requires a special exception?! or return null?!
				final SsNormalFile normalFile = (SsNormalFile) repoFile;

				final FileChunkPayload fileChunkPayload = fileChunkPayloadDao.getFileChunkPayloadOfFileChunk(normalFile, offset);
				final byte[] fileData = fileChunkPayload == null ? null : fileChunkPayload.getFileData();

				transaction.commit();
				return fileData;
			}
//		}
	}

	@Override
	public byte[] getHistoFileData(final Uid histoCryptoRepoFileId, final long offset) {
		try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginReadTransaction(); ) {
			final HistoCryptoRepoFileDao hcrfDao = transaction.getDao(HistoCryptoRepoFileDao.class);
			final HistoCryptoRepoFile histoCryptoRepoFile = hcrfDao.getHistoCryptoRepoFileOrFail(histoCryptoRepoFileId);
			final FileChunkPayloadDao fcpDao = transaction.getDao(FileChunkPayloadDao.class);
			final FileChunkPayload fileChunkPayload = fcpDao.getFileChunkPayloadOfHistoFileChunk(histoCryptoRepoFile, offset);
			final byte[] fileData = fileChunkPayload == null ? null : fileChunkPayload.getFileData();

			transaction.commit();
			return fileData;
		}
	}

	@Override
	public void endPutFile(String path, Date lastModified, long length, String sha1) {
//		final RepoFileContext context = RepoFileContext.getContext();
//		assertNotNull("RepoFileContext.getContext()", context);
//		final SsNormalFileDto normalFileDto = (SsNormalFileDto) context.getSsRepoFileDto();
//		final HistoCryptoRepoFileDto cryptoRepoFileOnServerDto = context.getCryptoRepoFileOnServerDto();
//		// length is ignored and is always 0!
//		// sha1 is ignored and always null!
//		endPutFile(path, normalFileDto, cryptoRepoFileOnServerDto);
		throw new IllegalStateException("This method should not be invoked on the server!");
	}

	@Override
	public void endPutFile(String path, final SsNormalFileDto normalFileDto, final CurrentHistoCryptoRepoFileDto currentHistoCryptoRepoFileDto) {
		assertNotNull(path, "path");
		assertNotNull(normalFileDto, "normalFileDto");
		assertNotNull(currentHistoCryptoRepoFileDto, "currentHistoCryptoRepoFileDto");
		assertNotNull(currentHistoCryptoRepoFileDto.getHistoCryptoRepoFileDto(), "currentHistoCryptoRepoFileDto.histoCryptoRepoFileDto");

		path = prefixPath(path);
		final File file = getFile(path);
		final UUID clientRepositoryId = getClientRepositoryIdOrFail();
		final File localRoot = getLocalRepoManager().getLocalRoot();

		try ( final LocalRepoTransaction transaction = getLocalRepoManager().beginWriteTransaction(); ) {
			final RepoFileDao repoFileDao = transaction.getDao(RepoFileDao.class);
			final TempFileChunkDao tempFileChunkDao = transaction.getDao(TempFileChunkDao.class);
			final HistoFileChunkDao histoFileChunkDao = transaction.getDao(HistoFileChunkDao.class);
			final FileChunkPayloadDao fileChunkPayloadDao = transaction.getDao(FileChunkPayloadDao.class);

			final RepoFile repoFile = repoFileDao.getRepoFile(localRoot, file);
			assertNotNull(repoFile, "repoFile"); // TODO or is this a collision which requires a special exception?!
			final SsNormalFile normalFile = (SsNormalFile) repoFile;

			final SortedMap<Long, FileChunk> offset2FileChunk = new TreeMap<>();
			for (FileChunk fileChunk : normalFile.getFileChunks())
				offset2FileChunk.put(fileChunk.getOffset(), fileChunk);

			final Collection<TempFileChunk> tempFileChunks = tempFileChunkDao.getTempFileChunks(normalFile, clientRepositoryId);
			for (final TempFileChunk tempFileChunk : tempFileChunks) {
				final FileChunkPayload fileChunkPayload = fileChunkPayloadDao.getFileChunkPayload(tempFileChunk);
				if (fileChunkPayload == null) {
					logger.warn("endPutFile: fileChunkPayload == null for {}", tempFileChunk);
					continue;
				}

				FileChunk fileChunk = offset2FileChunk.get(tempFileChunk.getOffset());
				if (fileChunk == null) {
					fileChunk = createObject(FileChunk.class);
					fileChunk.setNormalFile(normalFile);
					fileChunk.setOffset(tempFileChunk.getOffset());
					fileChunk.setLength(0); // no need in Subshare ;-)
					fileChunk.setSha1("X"); // no need in Subshare ;-) but: not-null-constraint!
					offset2FileChunk.put(fileChunk.getOffset(), fileChunk);
					normalFile.getFileChunks().add(fileChunk); // should implicitly persist it!
				}
				// FileChunk is read-only (once written to the DB) - hence we do not attempt to write anything - we don't need, anyway.

				final FileChunkPayload oldFileChunkPayload = fileChunkPayloadDao.getFileChunkPayload(fileChunk);
				if (oldFileChunkPayload != null) {
					if (histoFileChunkDao.getHistoFileChunkCount(oldFileChunkPayload) == 0)
						fileChunkPayloadDao.deletePersistent(oldFileChunkPayload);
					else
						oldFileChunkPayload.setFileChunk(null);

					transaction.flush(); // make sure it's written to the DB, before we re-associate the new one (=> unique key might be violated)
				}

				fileChunkPayload.setTempFileChunk(null);
				fileChunkPayload.setFileChunk(fileChunk);
			}
			tempFileChunkDao.deletePersistentAll(tempFileChunks);

			CurrentHistoCryptoRepoFile currentHistoCryptoRepoFile = CurrentHistoCryptoRepoFileDtoConverter.create(transaction).putCurrentHistoCryptoRepoFile(currentHistoCryptoRepoFileDto);
			currentHistoCryptoRepoFile.setLastSyncFromRepositoryId(clientRepositoryId);
			final HistoCryptoRepoFile histoCryptoRepoFile = assertNotNull(currentHistoCryptoRepoFile.getHistoCryptoRepoFile(),
					"currentHistoCryptoRepoFile[" + currentHistoCryptoRepoFileDto.getCryptoRepoFileId() + "].histoCryptoRepoFile");
//			final HistoCryptoRepoFile histoCryptoRepoFile = HistoCryptoRepoFileDtoConverter.create(transaction).putHistoCryptoRepoFile(histoCryptoRepoFileDto);
//
//			// TO DO must sign CurrentHistoCryptoRepoFile on client-side and upload! DONE?!
//			createOrUpdateCurrentHistoCryptoRepoFile(transaction, histoCryptoRepoFile);

			// normalFileDto.length is the real file size (before encryption) which may include random padding
			// (to prevent an attacker from identifying files by their sizes). It therefore matches the offsets
			// exactly. The actual length stored on the server might be much larger (due to each encrypted chunk
			// containing additional data added by the encryption process).

			for (final FileChunk fileChunk : offset2FileChunk.values()) {
				if (fileChunk.getOffset() >= normalFileDto.getLength())
					normalFile.getFileChunks().remove(fileChunk); // it's dependentElement => remove causes DELETE in DB
			}
			transaction.flush();

			final Collection<HistoFileChunk> histoFileChunks = histoFileChunkDao.getHistoFileChunks(histoCryptoRepoFile);
			final Map<Long, HistoFileChunk> offset2HistoFileChunk = new HashMap<>(histoFileChunks.size());
			for (HistoFileChunk histoFileChunk : histoFileChunks)
				offset2HistoFileChunk.put(histoFileChunk.getOffset(), histoFileChunk);

			logger.info("endPutFile: offset2HistoFileChunk contains {} entries already in DB: {}", offset2HistoFileChunk.size(), offset2HistoFileChunk);

			for (final FileChunk fileChunk : normalFile.getFileChunks()) {
				final FileChunkPayload fileChunkPayload = fileChunkPayloadDao.getFileChunkPayload(fileChunk);
				final long offset = fileChunk.getOffset();
				assertNotNull(fileChunkPayload,
						"fileChunkPayloadDao.getFileChunkPayload(fileChunk) :: fileChunk.normalFile.name='" + fileChunk.getNormalFile().getName() + "', fileChunk.offset=" + offset);

				HistoFileChunk histoFileChunk = offset2HistoFileChunk.remove(offset);
				if (histoFileChunk == null)
					histoFileChunk = new HistoFileChunk();

				histoFileChunk.setHistoCryptoRepoFile(histoCryptoRepoFile);
//				histoFileChunk.setNormalFile(fileChunk.getNormalFile());
				histoFileChunk.setOffset(offset);
				histoFileChunk.setLength(fileChunk.getLength());
				histoFileChunk.setFileChunkPayload(fileChunkPayload);
				histoFileChunkDao.makePersistent(histoFileChunk);
			}

			if (offset2HistoFileChunk.isEmpty())
				logger.info("endPutFile: offset2HistoFileChunk is now empty => nothing to delete.");
			else {
				logger.info("endPutFile: offset2HistoFileChunk contains {} entries to be deleted: {}", offset2HistoFileChunk.size(), offset2HistoFileChunk);
				histoFileChunkDao.deletePersistentAll(offset2HistoFileChunk.values());
			}

			normalFile.setInProgress(false);

			transaction.commit();
		}
	}

	@Override
	protected void detectAndHandleFileCollision(
			LocalRepoTransaction transaction, UUID fromRepositoryId, File file,
			RepoFile normalFileOrSymlink) {
		super.detectAndHandleFileCollision(transaction, fromRepositoryId, file, normalFileOrSymlink);

		// TODO must not invoke super method! Must throw exception instead! We must not handle collisions in the server! We must throw
		// a detailed exception instead.
	}

	@Override
	public ChangeSetDto getChangeSetDto(final boolean localSync, final Long lastSyncToRemoteRepoLocalRepositoryRevisionSynced) {
//		if (isOnClient())
//			return getDelegateOnClient().getChangeSetDto(localSync);
//		else
			// we must *never* do a LocalSync on the server!
			return super.getChangeSetDto(false, lastSyncToRemoteRepoLocalRepositoryRevisionSynced);
	}

	@Override
	public Long getLastCryptoKeySyncFromRemoteRepoRemoteRepositoryRevisionSynced() {
		try ( final LocalRepoTransaction tx = getLocalRepoManager().beginReadTransaction(); ) {
			final RemoteRepository remoteRepository = tx.getDao(RemoteRepositoryDao.class)
					.getRemoteRepositoryOrFail(getClientRepositoryIdOrFail());

			final LastCryptoKeySyncFromRemoteRepo lcksfrr = tx.getDao(LastCryptoKeySyncFromRemoteRepoDao.class)
					.getLastCryptoKeySyncFromRemoteRepo(remoteRepository);
			if (lcksfrr == null)
				return null;

			final long result = lcksfrr.getRemoteRepositoryRevisionSynced();
			tx.commit();
			return result < 0 ? null : result;
		}
	}

//	protected boolean isOnServer() {
//		return isServerThread();
//	}
//
//	protected boolean isOnClient() {
//		return ! isOnServer();
//	}
//
//	private static boolean isServerThread() {
//		final StackTraceElement[] stackTrace = new Exception().getStackTrace();
//		for (final StackTraceElement stackTraceElement : stackTrace) {
//			final String className = stackTraceElement.getClassName();
//			if ("org.eclipse.jetty.server.Server".equals(className))
//				return true;
//
//			if (className.startsWith("co.codewizards.cloudstore.rest.server.service."))
//				return true;
//
//			if (className.startsWith("org.subshare.rest.server.service."))
//				return true;
//		}
//		return false;
//	}

//	@Override
//	public void close() {
//		final CryptreeFileRepoTransportImpl d = delegateOnClient;
//		delegateOnClient = null;
//
//		if (d != null)
//			d.close();
//
//		super.close();
//	}
}
