package org.subshare.core.user;

import static co.codewizards.cloudstore.core.oio.OioFileFactory.*;
import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static co.codewizards.cloudstore.core.util.StringUtil.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.subshare.core.dto.DeletedUid;
import org.subshare.core.dto.UserDto;
import org.subshare.core.dto.UserRegistryDto;
import org.subshare.core.dto.jaxb.UserRegistryDtoIo;
import org.subshare.core.fbor.FileBasedObjectRegistry;
import org.subshare.core.pgp.PgpKey;
import org.subshare.core.pgp.PgpKeyId;
import org.subshare.core.pgp.PgpRegistry;
import org.subshare.core.pgp.PgpUserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.dto.Uid;
import co.codewizards.cloudstore.core.oio.File;

public class UserRegistryImpl extends FileBasedObjectRegistry implements UserRegistry {

	private static final Logger logger = LoggerFactory.getLogger(UserRegistryImpl.class);

	private static final String PAYLOAD_ENTRY_NAME = UserRegistryDto.class.getSimpleName() + ".xml";

	private final Map<Uid, User> userId2User = new LinkedHashMap<>();

	private final List<DeletedUid> deletedUserIds = new ArrayList<>();
	private final List<DeletedUid> deletedUserRepoKeyIds = new ArrayList<>();

	private List<User> cache_users;
	private Map<String, Set<User>> cache_email2Users;
	private Map<Uid, User> cache_userRepoKeyId2User;

	private final File userRegistryFile;
	private Uid version;

	private static final class Holder {
		public static final UserRegistryImpl instance = new UserRegistryImpl();
	}

	public static UserRegistry getInstance() {
		return Holder.instance;
	}

	protected UserRegistryImpl() {
		userRegistryFile = createFile(ConfigDir.getInstance().getFile(), USER_REGISTRY_FILE_NAME);
		read();
	}

	@Override
	protected String getContentType() {
		return "application/vnd.subshare.user-registry";
	}

	@Override
	protected File getFile() {
		return userRegistryFile;
	}

	@Override
	protected void read() {
		super.read();
		readPgpUsers();
		writeIfNeeded();
	}

	@Override
	protected void read(InputStream in) throws IOException {
		version = null;

		super.read(in);

		if (version == null)
			version = new Uid();
	}

	@Override
	protected void readPayloadEntry(ZipInputStream zin, ZipEntry zipEntry) throws IOException {
		if (!PAYLOAD_ENTRY_NAME.equals(zipEntry.getName())) {
			logger.warn("readPayloadEntry: Ignoring unexpected zip-entry: {}", zipEntry.getName());
			return;
		}
		final UserDtoConverter userDtoConverter = new UserDtoConverter();
		final UserRegistryDtoIo userRegistryDtoIo = new UserRegistryDtoIo();
		final UserRegistryDto userRegistryDto = userRegistryDtoIo.deserialize(zin);

		for (User user : getUsers())
			removeUser(user);

		for (final UserDto userDto : userRegistryDto.getUserDtos()) {
			final User user = userDtoConverter.fromUserDto(userDto);
			addUser(user);
		}

		deletedUserIds.clear();
		deletedUserIds.addAll(userRegistryDto.getDeletedUserIds());

		deletedUserRepoKeyIds.clear();
		deletedUserRepoKeyIds.addAll(userRegistryDto.getDeletedUserRepoKeyIds());

		version = userRegistryDto.getVersion();
	}

	public Uid getVersion() {
		return assertNotNull("version", version);
	}

	protected synchronized void readPgpUsers() {
		final List<User> newUsers = new ArrayList<>();
		final Map<PgpKeyId, PgpKey> pgpKeyId2PgpKey = new HashMap<>();
		final Map<String, List<User>> email2NewUsers = new HashMap<>();
		final Map<PgpKeyId, List<User>> pgpKeyId2Users = createPgpKeyId2Users();
		for (final PgpKey pgpKey : PgpRegistry.getInstance().getPgpOrFail().getMasterKeys()) {
			pgpKeyId2PgpKey.put(pgpKey.getPgpKeyId(), pgpKey);

			List<User> usersByPgpKeyId = pgpKeyId2Users.get(pgpKey.getPgpKeyId());
			if (usersByPgpKeyId == null) {
				usersByPgpKeyId = new ArrayList<User>(1);
				pgpKeyId2Users.put(pgpKey.getPgpKeyId(), usersByPgpKeyId);
			}

			User user = usersByPgpKeyId.isEmpty() ? null : usersByPgpKeyId.get(0);
			if (user == null) {
				boolean newUser = true;
				user = createUser();
				for (final String userId : pgpKey.getUserIds())
					populateUserFromPgpUserId(user, userId);

				// Try to deduplicate by e-mail address.
				for (final String email : user.getEmails()) {
					final Collection<User> usersByEmail = getUsersByEmail(email);
					if (! usersByEmail.isEmpty()) {
						newUser = false;
						user = usersByEmail.iterator().next();

						for (final String userId : pgpKey.getUserIds())
							populateUserFromPgpUserId(user, userId);
					}
				}

				if (newUser) {
					for (final String email : user.getEmails()) {
						List<User> l = email2NewUsers.get(email);
						if (l == null) {
							l = new ArrayList<>();
							email2NewUsers.put(email, l);
						}
						if (l.isEmpty())
							l.add(user);
						else {
							newUser = false;
							user = l.get(0);

							for (final String userId : pgpKey.getUserIds())
								populateUserFromPgpUserId(user, userId);
						}
					}
				}

				if (newUser)
					newUsers.add(user);
			}

			if (! user.getPgpKeyIds().contains(pgpKey.getPgpKeyId()))
				user.getPgpKeyIds().add(pgpKey.getPgpKeyId());

			usersByPgpKeyId.add(user);
		}

		for (User user : newUsers) {
			// The order of PgpKeyIds does not say anything - the numbers are random! I only sort
			// them for the sake of deterministic behaviour.
			final PgpKeyId pgpKeyId = new TreeSet<>(user.getPgpKeyIds()).iterator().next();
			final PgpKey pgpKey = pgpKeyId2PgpKey.get(pgpKeyId);
			assertNotNull("pgpKey", pgpKey);
			final Uid userId = new Uid(getLast16(pgpKey.getFingerprint()));
			user.setUserId(userId);
			addUser(user);
		}
	}

	private synchronized Map<PgpKeyId, List<User>> createPgpKeyId2Users() {
		final Map<PgpKeyId, List<User>> result = new HashMap<PgpKeyId, List<User>>();
		for (final User user : userId2User.values()) {
			for (final PgpKeyId pgpKeyId : user.getPgpKeyIds()) {
				List<User> users = result.get(pgpKeyId);
				if (users == null) {
					users = new ArrayList<User>(1);
					result.put(pgpKeyId, users);
				}
				users.add(user);
			}
		}
		return result;
	}

	private static byte[] getLast16(byte[] fingerprint) {
		final byte[] result = new byte[16];

		if (fingerprint.length < result.length)
			throw new IllegalArgumentException("fingerprint.length < " + result.length);

		System.arraycopy(fingerprint, fingerprint.length - result.length, result, 0, result.length);
		return result;
	}

	private static void populateUserFromPgpUserId(final User user, final String pgpUserIdStr) {
		assertNotNull("user", user);
		final PgpUserId pgpUserId = new PgpUserId(assertNotNull("pgpUserIdStr", pgpUserIdStr));
		if (! isEmpty(pgpUserId.getEmail()) && ! user.getEmails().contains(pgpUserId.getEmail()))
			user.getEmails().add(pgpUserId.getEmail());

		final String fullName = pgpUserId.getName();
		if (! isEmpty(fullName)) {
			final String[] firstAndLastName = extractFirstAndLastNameFromFullName(fullName);

			if (isEmpty(user.getFirstName()) && !firstAndLastName[0].isEmpty())
				user.setFirstName(firstAndLastName[0]);

			if (isEmpty(user.getLastName()) && !firstAndLastName[1].isEmpty())
				user.setLastName(firstAndLastName[1]);
		}
	}

	private static String[] extractFirstAndLastNameFromFullName(String fullName) {
		fullName = assertNotNull("fullName", fullName).trim();

		if (fullName.endsWith(")")) {
			final int lastOpenBracket = fullName.lastIndexOf('(');
			if (lastOpenBracket >= 0) {
				fullName = fullName.substring(0, lastOpenBracket).trim();
			}
		}

		final int lastSpace = fullName.lastIndexOf(' ');
		if (lastSpace < 0)
			return new String[] { "", fullName };
		else {
			final String firstName = fullName.substring(0, lastSpace).trim();
			final String lastName = fullName.substring(lastSpace + 1).trim();
			return new String[] { firstName, lastName };
		}
	}

	@Override
	public User createUser() {
		return new UserImpl();
	}

	@Override
	public synchronized Collection<User> getUsers() {
		if (cache_users == null)
			cache_users = Collections.unmodifiableList(new ArrayList<User>(userId2User.values()));

		return cache_users;
	}

	private void cleanCache() {
		cache_users = null;
		cache_email2Users = null;
		cache_userRepoKeyId2User = null;
	}

	@Override
	public synchronized Collection<User> getUsersByEmail(final String email) {
		assertNotNull("email", email);
		if (cache_email2Users == null) {
			final Map<String, Set<User>> cache_email2Users = new HashMap<String, Set<User>>();
			for (final User user : getUsers()) {
				for (String eml : user.getEmails()) {
					eml = eml.toLowerCase(Locale.UK);
					Set<User> users = cache_email2Users.get(eml);
					if (users == null) {
						users = new HashSet<User>();
						cache_email2Users.put(eml, users);
					}
					users.add(user);
				}
			}

			for (final Map.Entry<String, Set<User>> me : cache_email2Users.entrySet())
				me.setValue(Collections.unmodifiableSet(me.getValue()));

			this.cache_email2Users = Collections.unmodifiableMap(cache_email2Users);
		}

		final Set<User> users = cache_email2Users.get(email.toLowerCase(Locale.UK));
		return users != null ? users : Collections.unmodifiableList(new LinkedList<User>());
	}

	@Override
	public synchronized void addUser(final User user) {
		assertNotNull("user", user);
		assertNotNull("user.userId", user.getUserId());
		userId2User.put(user.getUserId(), user);
		// TODO we either need to hook listeners into user and get notified about all changes to update this registry!
		// OR we need to provide a public write/save/store (or similarly named) method.

//		for (final PgpKeyId pgpKeyId : user.getPgpKeyIds())
//			pgpKeyId2User.put(pgpKeyId, user); // TODO what about collisions? remove pgpKeyId from the other user?!

		user.addPropertyChangeListener(userPropertyChangeListener);

		cleanCache();
		markDirty();
	}

	@Override
	public synchronized void removeUser(final User user) {
		_removeUser(user);
		deletedUserIds.add(new DeletedUid(user.getUserId()));
	}

	protected synchronized void _removeUser(final User user) {
		assertNotNull("user", user);
		userId2User.remove(user.getUserId());

//		for (final PgpKeyId pgpKeyId : user.getPgpKeyIds())
//			pgpKeyId2User.remove(pgpKeyId);

		user.removePropertyChangeListener(userPropertyChangeListener);

		cleanCache();
		markDirty();
	}

	@Override
	public synchronized User getUserByUserIdOrFail(final Uid userId) {
		final User user = getUserByUserId(userId);
		if (user == null)
			throw new IllegalArgumentException("No User found for userId=" + userId);

		return user;
	}

	@Override
	public synchronized User getUserByUserId(final Uid userId) {
		assertNotNull("userId", userId);
//		if (cache_userId2User == null) {
//			final Map<Uid, User> m = new HashMap<>();
//
//			for (final User user : getUsers())
//				m.put(user.getUserId(), user);
//
//			cache_userId2User = m;
//		}
//		return cache_userId2User.get(userId);
		return userId2User.get(userId);
	}

	@Override
	public User getUserByUserRepoKeyIdOrFail(final Uid userRepoKeyId) {
		final User user = getUserByUserRepoKeyId(userRepoKeyId);
		if (user == null)
			throw new IllegalArgumentException("No User found for userRepoKeyId=" + userRepoKeyId);

		return user;
	}

	@Override
	public synchronized User getUserByUserRepoKeyId(final Uid userRepoKeyId) {
		assertNotNull("userRepoKeyId", userRepoKeyId);
		if (cache_userRepoKeyId2User == null) {
			final Map<Uid, User> m = new HashMap<>();

			for (final User user : getUsers()) {
				final UserRepoKeyRing userRepoKeyRing = user.getUserRepoKeyRing();
				if (userRepoKeyRing != null) {
					for (final UserRepoKey userRepoKey : userRepoKeyRing.getUserRepoKeys())
						if (m.put(userRepoKey.getUserRepoKeyId(), user) != null)
							throw new IllegalStateException("Duplicate userRepoKeyId!!! WTF?! " + userRepoKey.getUserRepoKeyId());
				}
				else {
					for (final UserRepoKey.PublicKey publicKey : user.getUserRepoKeyPublicKeys()) {
						if (m.put(publicKey.getUserRepoKeyId(), user) != null)
							throw new IllegalStateException("Duplicate userRepoKeyId!!! WTF?! " + publicKey.getUserRepoKeyId());
					}
				}
			}

			cache_userRepoKeyId2User = m;
		}
		return cache_userRepoKeyId2User.get(userRepoKeyId);
	}

	@Override
	public Collection<User> getUsersByPgpKeyIds(final Set<PgpKeyId> pgpKeyIds) {
		final List<User> result = new ArrayList<User>();
		iterateUsers: for (final User user : getUsers()) {
			for (final PgpKeyId pgpKeyId : user.getPgpKeyIds()) {
				if (pgpKeyIds.contains(pgpKeyId)) {
					result.add(user);
					continue iterateUsers;
				}
			}
		}
		return result;
	}

	private final PropertyChangeListener userPropertyChangeListener = new PropertyChangeListener() {
		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			final User user = (User) evt.getSource();
			assertNotNull("user", user);

			markDirty();
			cleanCache();
		}
	};

	@Override
	protected void markDirty() {
		super.markDirty();
		version = new Uid();
		deferredWrite();
	}

	@Override
	protected void writePayload(ZipOutputStream zout) throws IOException {
		final UserRegistryDtoIo userRegistryDtoIo = new UserRegistryDtoIo();
		final UserRegistryDto userRegistryDto = createUserRegistryDto();

		zout.putNextEntry(new ZipEntry(PAYLOAD_ENTRY_NAME));
		userRegistryDtoIo.serialize(userRegistryDto, zout);
		zout.closeEntry();
	}

	@Override
	protected void mergeFrom(ZipInputStream zin, ZipEntry zipEntry) {
		if (PAYLOAD_ENTRY_NAME.equals(zipEntry.getName())) {
			final UserRegistryDtoIo userRegistryDtoIo = new UserRegistryDtoIo();
			final UserRegistryDto userRegistryDto = userRegistryDtoIo.deserialize(zin);
			mergeFrom(userRegistryDto);
		}
	}

	protected synchronized void mergeFrom(final UserRegistryDto userRegistryDto) {
		assertNotNull("userRegistryDto", userRegistryDto);

		final List<UserDto> newUserDtos = new ArrayList<>(userRegistryDto.getUserDtos().size());
		for (final UserDto userDto : userRegistryDto.getUserDtos()) {
			final Uid userId = assertNotNull("userDto.userId", userDto.getUserId());
			final User user = getUserByUserId(userId);
			if (user == null)
				newUserDtos.add(userDto);
			else
				merge(user, userDto);
		}

		final Set<DeletedUid> newDeletedUserIds = new HashSet<>(userRegistryDto.getDeletedUserIds());
		newDeletedUserIds.removeAll(this.deletedUserIds);
		final Map<DeletedUid, User> newDeletedUsers = new HashMap<>(newDeletedUserIds.size());
		for (final DeletedUid deletedUserId : newDeletedUserIds) {
			final User user = getUserByUserId(deletedUserId.getUid());
			if (user != null)
				newDeletedUsers.put(deletedUserId, user);
		}

		final Set<DeletedUid> newDeletedUserRepoKeyIds = new HashSet<>(userRegistryDto.getDeletedUserRepoKeyIds());
		newDeletedUserRepoKeyIds.removeAll(this.deletedUserRepoKeyIds);
		final Map<DeletedUid, User> newDeletedUserRepoKeyId2User = new HashMap<>(newDeletedUserRepoKeyIds.size());
		for (final DeletedUid deletedUserRepoKeyId : newDeletedUserRepoKeyIds) {
			final User user = getUserByUserRepoKeyId(deletedUserRepoKeyId.getUid());
			if (user != null)
				newDeletedUserRepoKeyId2User.put(deletedUserRepoKeyId, user);
		}

		final UserDtoConverter userDtoConverter = new UserDtoConverter();
		for (final UserDto userDto : newUserDtos) {
			final User user = userDtoConverter.fromUserDto(userDto);
			addUser(user);
		}

		for (final Map.Entry<DeletedUid, User> me : newDeletedUsers.entrySet()) {
			_removeUser(me.getValue());
			deletedUserIds.add(me.getKey());
		}

		for (final Map.Entry<DeletedUid, User> me : newDeletedUserRepoKeyId2User.entrySet()) {
			// TODO implement this!
			throw new UnsupportedOperationException("NYI");
		}

		writeIfNeeded();
	}

	private void merge(final User toUser, final UserDto fromUserDto) {
		assertNotNull("toUser", toUser);
		assertNotNull("fromUserDto", fromUserDto);
		if (toUser.getChanged().before(fromUserDto.getChanged())) {
			toUser.setFirstName(fromUserDto.getFirstName());
			toUser.setLastName(fromUserDto.getLastName());

			if (!toUser.getEmails().equals(fromUserDto.getEmails())) {
				toUser.getEmails().clear();
				toUser.getEmails().addAll(fromUserDto.getEmails());
			}

			if (!toUser.getPgpKeyIds().equals(fromUserDto.getPgpKeyIds())) {
				toUser.getPgpKeyIds().clear();
				toUser.getPgpKeyIds().addAll(fromUserDto.getPgpKeyIds());
			}

			toUser.setChanged(fromUserDto.getChanged());
			if (!toUser.getChanged().equals(fromUserDto.getChanged())) // sanity check - to make sure listeners don't change it again
				throw new IllegalStateException("toUser.changed != fromUserDto.changed");
		}
	}

	private synchronized UserRegistryDto createUserRegistryDto() {
		final UserDtoConverter userDtoConverter = new UserDtoConverter();
		final UserRegistryDto userRegistryDto = new UserRegistryDto();
		for (final User user : userId2User.values()) {
			final UserDto userDto = userDtoConverter.toUserDto(user);
			userRegistryDto.getUserDtos().add(userDto);
		}
		userRegistryDto.getDeletedUserIds().addAll(deletedUserIds);
		userRegistryDto.setVersion(version);
		return userRegistryDto;
	}
}
