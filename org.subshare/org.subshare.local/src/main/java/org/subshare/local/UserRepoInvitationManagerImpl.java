package org.subshare.local;

import static co.codewizards.cloudstore.core.util.AssertUtil.assertNotNull;
import static co.codewizards.cloudstore.core.util.UrlUtil.appendNonEncodedPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.subshare.core.Cryptree;
import org.subshare.core.CryptreeFactoryRegistry;
import org.subshare.core.dto.PermissionType;
import org.subshare.core.dto.UserRepoInvitationDto;
import org.subshare.core.pgp.Pgp;
import org.subshare.core.pgp.PgpDecoder;
import org.subshare.core.pgp.PgpEncoder;
import org.subshare.core.pgp.PgpKey;
import org.subshare.core.pgp.PgpRegistry;
import org.subshare.core.user.User;
import org.subshare.core.user.UserRegistry;
import org.subshare.core.user.UserRepoInvitation;
import org.subshare.core.user.UserRepoInvitationDtoConverter;
import org.subshare.core.user.UserRepoInvitationManager;
import org.subshare.core.user.UserRepoInvitationToken;
import org.subshare.core.user.UserRepoKey;
import org.subshare.core.user.UserRepoKeyRing;
import org.subshare.local.persistence.SsRemoteRepository;
import org.subshare.local.persistence.InvitationUserRepoKeyPublicKey;
import org.subshare.local.persistence.UserRepoKeyPublicKeyDao;
import org.subshare.local.persistence.VerifySignableAndWriteProtectedEntityListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.auth.SignatureException;
import co.codewizards.cloudstore.core.dto.jaxb.CloudStoreJaxbContext;
import co.codewizards.cloudstore.core.repo.local.LocalRepoManager;
import co.codewizards.cloudstore.core.repo.local.LocalRepoTransaction;
import co.codewizards.cloudstore.local.persistence.RemoteRepository;
import co.codewizards.cloudstore.local.persistence.RemoteRepositoryDao;

public class UserRepoInvitationManagerImpl implements UserRepoInvitationManager {
	private static final String USER_REPO_INVITATION_DTO_XML_FILE_NAME = "userRepoInvitationDto.xml";

	private static final Logger logger = LoggerFactory.getLogger(UserRepoInvitationManagerImpl.class);

	private static final String MANIFEST_PROPERTY_VERSION = "version";

	private static final String MANIFEST_PROPERTY_CONTENT_TYPE = "contentType";

	private static final String MANIFEST_PROPERTIES_FILE_NAME = "MANIFEST.properties";

	private final UserRepoInvitationDtoConverter userRepoInvitationDtoConverter = new UserRepoInvitationDtoConverter();

	private UserRegistry userRegistry;
	private LocalRepoManager localRepoManager;

	private LocalRepoTransaction transaction;
	private Cryptree cryptree;
	private User grantingUser;

	@Override
	public int getPriority() {
		return 0;
	}

	@Override
	public UserRegistry getUserRegistry() {
		return userRegistry;
	}
	@Override
	public void setUserRegistry(UserRegistry userRegistry) {
		this.userRegistry = userRegistry;
	}

	@Override
	public LocalRepoManager getLocalRepoManager() {
		return localRepoManager;
	}
	@Override
	public void setLocalRepoManager(LocalRepoManager localRepoManager) {
		this.localRepoManager = localRepoManager;
	}

	@Override
	public UserRepoInvitationToken createUserRepoInvitationToken(final String localPath, final User user, final PermissionType permissionType, final long validityDurationMillis) {
		assertNotNull("localPath", localPath);
		assertNotNull("user", user);
		assertNotNull("permissionType", permissionType);

		final UserRepoInvitation userRepoInvitation = createUserRepoInvitation(localPath, user, permissionType, validityDurationMillis);
		final User grantingUser = assertNotNull("grantingUser", this.grantingUser);

		final byte[] userRepoInvitationData = toUserRepoInvitationData(userRepoInvitation);

//		try {
//			FileOutputStream fout = new FileOutputStream(File.createTempFile("xxx-", ".zip"));
//			fout.write(userRepoInvitationData);
//			fout.close();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}

		final PgpKey signPgpKey = grantingUser.getPgpKeyContainingPrivateKeyOrFail();

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final PgpEncoder encoder = getPgpOrFail().createEncoder(new ByteArrayInputStream(userRepoInvitationData), out);
		encoder.setSignPgpKey(signPgpKey);
		encoder.getEncryptPgpKeys().addAll(user.getPgpKeys());
		try {
			encoder.encode();
		} catch (final IOException x) {
			throw new RuntimeException(x);
		}

		return new UserRepoInvitationToken(out.toByteArray());
	}

	@Override
	public void importUserRepoInvitationToken(final UserRepoInvitationToken userRepoInvitationToken) {
		assertNotNull("userRepoInvitationToken", userRepoInvitationToken);

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final PgpDecoder pgpDecoder = getPgpOrFail().createDecoder(new ByteArrayInputStream(userRepoInvitationToken.getSignedEncryptedUserRepoInvitationData()), out);
		try {
			pgpDecoder.decode();
		} catch (final SignatureException | IOException e) {
			throw new RuntimeException(e);
		}

		final UserRepoInvitation userRepoInvitation = fromUserRepoInvitationData(out.toByteArray());
		importUserRepoInvitation(userRepoInvitation);
	}

	private byte[] toUserRepoInvitationData(final UserRepoInvitation userRepoInvitation) {
		assertNotNull("userRepoInvitation", userRepoInvitation);
		try {
			final Marshaller marshaller = CloudStoreJaxbContext.getJaxbContext().createMarshaller();

			final UserRepoInvitationDto userRepoInvitationDto = userRepoInvitationDtoConverter.toUserRepoInvitationDto(userRepoInvitation);

			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final ZipOutputStream zout = new ZipOutputStream(out);

			writeManifest(zout);

			zout.putNextEntry(new ZipEntry(USER_REPO_INVITATION_DTO_XML_FILE_NAME));
			marshaller.marshal(userRepoInvitationDto, zout);
			zout.closeEntry();

			zout.close();

			return out.toByteArray();
		} catch (final JAXBException | IOException x) {
			throw new RuntimeException(x);
		}
	}

	private void writeManifest(ZipOutputStream zout) throws IOException {
		final byte[] manifestData = createManifestData();
		zout.putNextEntry(createManifestZipEntry(manifestData));
		zout.write(manifestData);
		zout.closeEntry();
	}

	private UserRepoInvitation fromUserRepoInvitationData(byte[] userRepoInvitationData) {
		assertNotNull("userRepoInvitationData", userRepoInvitationData);
		try {

			final ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(userRepoInvitationData));

			// We expect the very first entry to be the MANIFEST.properties!
			final Properties manifestProperties = readManifest(zin);
			final int version = getVersionFromManifestProperties(manifestProperties);
			if (version != 1)
				throw new IllegalArgumentException("userRepoInvitationData invalid: Unsupported version: " + version);

			final Map<String, Object> name2Dto = readName2Dto(zin);
			final UserRepoInvitationDto userRepoInvitationDto = (UserRepoInvitationDto) name2Dto.get(USER_REPO_INVITATION_DTO_XML_FILE_NAME);
			if (userRepoInvitationDto == null)
				throw new IllegalArgumentException("userRepoInvitationData invalid: Missing zip-entry: " + USER_REPO_INVITATION_DTO_XML_FILE_NAME);

			final UserRepoInvitation userRepoInvitation = userRepoInvitationDtoConverter.fromUserRepoInvitationDto(userRepoInvitationDto);
			return userRepoInvitation;
		} catch (final JAXBException | IOException x) {
			throw new RuntimeException(x);
		}
	}

	private Map<String, Object> readName2Dto(ZipInputStream zin) throws IOException, JAXBException {
		final Unmarshaller unmarshaller = CloudStoreJaxbContext.getJaxbContext().createUnmarshaller();
		final Map<String, Object> name2Dto = new HashMap<>();
		ZipEntry ze;
		while (null != (ze = zin.getNextEntry())) {
			if (!ze.getName().endsWith(".xml")) {
				logger.warn("fromUserRepoInvitationData: Ignoring file (not ending on '.xml'): {}", ze.getName());
				continue;
			}

			final Object dto = unmarshaller.unmarshal(new NonClosableInputStream(zin));
			name2Dto.put(ze.getName(), dto);
		}
		return name2Dto;
	}

	private static class NonClosableInputStream extends FilterInputStream {
		public NonClosableInputStream(InputStream in) {
			super(in);
		}

		@Override
		public void close() {
			// do nothing
		}
	}

	private Properties readManifest(final ZipInputStream zin) throws IOException {
		assertNotNull("zin", zin);

		final ZipEntry ze = zin.getNextEntry();
		if (ze == null)
			throw new IllegalArgumentException(String.format("userRepoInvitationData is not valid: It lacks the '%s' as very first zip-entry (there is no first ZipEntry)!", MANIFEST_PROPERTIES_FILE_NAME));

		if (!MANIFEST_PROPERTIES_FILE_NAME.equals(ze.getName()))
			throw new IllegalArgumentException(String.format("userRepoInvitationData is not valid: The very first zip-entry is not '%s' (it is '%s' instead)!", MANIFEST_PROPERTIES_FILE_NAME, ze.getName()));

		final Properties properties = new Properties();
		properties.load(zin);

		final String contentType = properties.getProperty(MANIFEST_PROPERTY_CONTENT_TYPE);
		if (!UserRepoInvitationToken.CONTENT_TYPE_USER_REPO_INVITATION.equals(contentType))
			throw new IllegalArgumentException(String.format("userRepoInvitationData is not valid: The manifest indicates the content-type '%s', but '%s' is expected!", contentType, UserRepoInvitationToken.CONTENT_TYPE_USER_REPO_INVITATION));

		return properties;
	}

	private int getVersionFromManifestProperties(final Properties manifestProperties) {
		final String versionStr = manifestProperties.getProperty(MANIFEST_PROPERTY_VERSION);
		final int version;
		try {
			version = Integer.parseInt(versionStr);
		} catch (NumberFormatException x) {
			throw new IllegalArgumentException(String.format("The manifest does not contain a valid version number ('%s' is not a valid integer)!", versionStr), x);
		}
		return version;
	}

	private ZipEntry createManifestZipEntry(final byte[] manifestData) {
		final ZipEntry ze = new ZipEntry(MANIFEST_PROPERTIES_FILE_NAME);
		ze.setMethod(ZipEntry.STORED);
		ze.setSize(manifestData.length);
		ze.setCompressedSize(manifestData.length);
		final CRC32 crc32 = new CRC32();
		crc32.update(manifestData);
		ze.setCrc(crc32.getValue());
		return ze;
	}

	private byte[] createManifestData() throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);

		writeManifestEntry(w, MANIFEST_PROPERTY_CONTENT_TYPE, UserRepoInvitationToken.CONTENT_TYPE_USER_REPO_INVITATION);
		writeManifestEntry(w, MANIFEST_PROPERTY_VERSION, Integer.toString(1));

		w.close();
		return out.toByteArray();
	}

	private void writeManifestEntry(final Writer w, final String key, final String value) throws IOException {
		w.write(key);
		w.write('=');
		w.write(value);
		w.write('\n');
	}

	protected UserRepoInvitation createUserRepoInvitation(final String localPath, final User user, PermissionType permissionType, final long validityDurationMillis) {
		assertNotNull("localPath", localPath);
		assertNotNull("user", user);
		assertNotNull("permissionType", permissionType);

		final UserRepoInvitation userRepoInvitation;
		try (final LocalRepoTransaction transaction = localRepoManager.beginWriteTransaction();)
		{
			this.transaction = transaction;

			grantingUser = createCryptreeAndDetermineGrantingUser(localPath);

			final UserRepoKey invitationUserRepoKey = grantingUser.createInvitationUserRepoKey(user, cryptree.getRemoteRepositoryId(), validityDurationMillis);
			user.getUserRepoKeyPublicKeys().add(invitationUserRepoKey.getPublicKey());
			cryptree.grantPermission(localPath, permissionType, invitationUserRepoKey.getPublicKey());

			final RemoteRepositoryDao remoteRepositoryDao = transaction.getDao(RemoteRepositoryDao.class);
			final RemoteRepository remoteRepository = remoteRepositoryDao.getRemoteRepositoryOrFail(cryptree.getRemoteRepositoryId());
			final URL remoteRoot = remoteRepository.getRemoteRoot();
			if (remoteRoot == null)
				throw new IllegalStateException("Could not determine the remoteRoot for the remoteRepositoryId " + cryptree.getRemoteRepositoryId());

			final String serverPath = cryptree.getServerPath(localPath);
			final URL completeUrl = appendNonEncodedPath(remoteRoot, serverPath);

//			final UserRepoKeyPublicKeyDao userRepoKeyPublicKeyDao = transaction.getDao(UserRepoKeyPublicKeyDao.class);
//			final UserRepoKeyPublicKey signingUserRepoKeyPublicKey = userRepoKeyPublicKeyDao.getUserRepoKeyPublicKeyOrFail(
//					invitationUserRepoKey.getPublicKey().getSignature().getSigningUserRepoKeyId());

			userRepoInvitation = new UserRepoInvitation(completeUrl, invitationUserRepoKey); // signingUserRepoKeyPublicKey.getPublicKey());

			transaction.commit();
		} finally {
			this.cryptree = null;
			this.transaction = null;
		}
		return userRepoInvitation;
	}

	private User createCryptreeAndDetermineGrantingUser(final String localPath) {
		final RemoteRepositoryDao remoteRepositoryDao = transaction.getDao(RemoteRepositoryDao.class);
		final Map<UUID, URL> remoteRepositoryId2RemoteRootMap = remoteRepositoryDao.getRemoteRepositoryId2RemoteRootMap();
		if (remoteRepositoryId2RemoteRootMap.size() > 1)
			throw new UnsupportedOperationException("Currently, only exactly one remote-repository is allowed per local repository!");

		if (remoteRepositoryId2RemoteRootMap.isEmpty())
			throw new IllegalStateException("There is no remote-repository connected with this local repository!");

		final UUID remoteRepositoryId = remoteRepositoryId2RemoteRootMap.keySet().iterator().next();
		final SsRemoteRepository remoteRepository = (SsRemoteRepository) remoteRepositoryDao.getRemoteRepositoryOrFail(remoteRepositoryId);

		for (final User user : userRegistry.getUsers()) {
			final UserRepoKeyRing userRepoKeyRing = user.getUserRepoKeyRing();
			if (userRepoKeyRing == null || user.getPgpKeyContainingPrivateKey() == null)
				continue;

			cryptree = CryptreeFactoryRegistry.getInstance().getCryptreeFactoryOrFail().getCryptreeOrCreate(
					transaction, remoteRepositoryId,
					remoteRepository.getRemotePathPrefix(),
					userRepoKeyRing);

			final UserRepoKey grantingUserRepoKey = cryptree.getUserRepoKey(localPath, PermissionType.grant);
			if (grantingUserRepoKey != null)
				return user;

			transaction.removeContextObject(cryptree);
		}

		throw new IllegalArgumentException("No User found having a local UserRepoKey allowed to grant access as desired!");
	}

	protected void importUserRepoInvitation(final UserRepoInvitation userRepoInvitation) {
		assertNotNull("userRepoInvitation", userRepoInvitation);
		final PgpKey decryptPgpKey = determineDecryptPgpKey(userRepoInvitation);
		final User user = findUserWithPgpKeyOrFail(decryptPgpKey);

//		final UUID localRepositoryId = cryptree.getTransaction().getLocalRepoManager().getRepositoryId();
//		final URL serverUrl = userRepoInvitation.getServerUrl();
//		try (final RepoTransport repoTransport = RepoTransportFactoryRegistry.getInstance().getRepoTransportFactory(serverUrl).createRepoTransport(serverUrl, localRepositoryId);)
//		{
//			CryptreeRep
//		}

		// We throw the temporary key away *LATER*. We keep it in our local key ring for a while to be able to decrypt
		// CryptoLinks that were encrypted with it, after the initial invitation. This allows the granting user to grant
		// further (read) permissions after sending the invitation.
		// Additionally, it makes the initial sync easier, because we can initially sync using the temporary key directly.
		// This is important, because right now, we don't have any CryptoLink, yet (this method is invoked *before* the
		// very first sync). Hence we cannot immediately replace the CryptoLink.fromUserRepoKeyPublicKey - we can do it
		// only after first syncing the keys down.
		user.getUserRepoKeyRingOrCreate().addUserRepoKey(userRepoInvitation.getInvitationUserRepoKey());

		// But we create our permanent key, already immediately.
		UserRepoKey userRepoKey = user.createUserRepoKey(userRepoInvitation.getInvitationUserRepoKey().getServerRepositoryId());

		try (final LocalRepoTransaction transaction = localRepoManager.beginWriteTransaction();)
		{
			this.transaction = transaction;

			cryptree = CryptreeFactoryRegistry.getInstance().getCryptreeFactoryOrFail().getCryptreeOrCreate(
					transaction, userRepoInvitation.getInvitationUserRepoKey().getServerRepositoryId(),
					"NOT_NEEDED_FOR_THIS_OPERATION", // not nice, but a sufficient workaround ;-)
					user.getUserRepoKeyRingOrCreate());

			final UserRepoKeyPublicKeyDao userRepoKeyPublicKeyDao = transaction.getDao(UserRepoKeyPublicKeyDao.class);
//			userRepoKeyPublicKeyDao.getUserRepoKeyPublicKeyOrCreate(userRepoInvitation.getSigningUserRepoKeyPublicKey());

//			cryptree.replaceInvitationUserRepoKey(userRepoInvitation.getInvitationUserRepoKey(), userRepoKey);
			cryptree.requestReplaceInvitationUserRepoKey(userRepoInvitation.getInvitationUserRepoKey(), userRepoKey.getPublicKey());

			final InvitationUserRepoKeyPublicKey invitationUserRepoKeyPublicKey =
					(InvitationUserRepoKeyPublicKey) userRepoKeyPublicKeyDao.getUserRepoKeyPublicKeyOrFail(userRepoInvitation.getInvitationUserRepoKey().getUserRepoKeyId());

			final VerifySignableAndWriteProtectedEntityListener verifySignableAndWriteProtectedEntityListener = transaction.getContextObject(VerifySignableAndWriteProtectedEntityListener.class);
			assertNotNull("verifySignableAndWriteProtectedEntityListener", verifySignableAndWriteProtectedEntityListener);
			verifySignableAndWriteProtectedEntityListener.removeSignable(invitationUserRepoKeyPublicKey);

			transaction.commit();
		} finally {
			this.cryptree = null;
			this.transaction = null;
		}

		userRegistry.write(); // TODO writeIfNeeded() and maybe make write() protected?!

	}

	private PgpKey determineDecryptPgpKey(final UserRepoInvitation userRepoInvitation) {
		final byte[] encryptedSignedPrivateKeyData = userRepoInvitation.getInvitationUserRepoKey().getEncryptedSignedPrivateKeyData();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final PgpDecoder decoder = getPgpOrFail().createDecoder(new ByteArrayInputStream(encryptedSignedPrivateKeyData), out);
		try {
			decoder.decode();
			return decoder.getDecryptPgpKey();
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Pgp getPgpOrFail() {
		return PgpRegistry.getInstance().getPgpOrFail();
	}

//	private User findUserWithUserRepoKeyRingOrFail(UserRepoKey userRepoKey) {
//		final Uid userRepoKeyId = userRepoKey.getUserRepoKeyId();
//		for (final User user : userRegistry.getUsers()) {
//			final UserRepoKeyRing userRepoKeyRing = user.getUserRepoKeyRing();
//			if (userRepoKeyRing != null && userRepoKeyRing.getUserRepoKey(userRepoKeyId) != null)
//				return user;
//		}
//		throw new IllegalArgumentException("No User found owning the UserRepoKey with id=" + userRepoKeyId);
//	}

	private User findUserWithPgpKeyOrFail(PgpKey pgpKey) {
		final Long pgpKeyId = pgpKey.getPgpKeyId();
		for (final User user : userRegistry.getUsers()) {
			if (user.getPgpKeyIds().contains(pgpKeyId))
				return user;
		}
		throw new IllegalArgumentException("No User associated with the PgpKey with id=" + pgpKeyId);
	}
}