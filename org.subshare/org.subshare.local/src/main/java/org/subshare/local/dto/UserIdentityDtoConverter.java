package org.subshare.local.dto;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import org.subshare.core.dto.UserIdentityDto;
import org.subshare.local.persistence.UserIdentity;

public class UserIdentityDtoConverter {

	public UserIdentityDto toUserIdentityDto(final UserIdentity userIdentity) {
		assertNotNull(userIdentity, "userIdentity");
		final UserIdentityDto userIdentityDto = new UserIdentityDto();
		userIdentityDto.setUserIdentityId(userIdentity.getUserIdentityId());
		userIdentityDto.setOfUserRepoKeyId(userIdentity.getOfUserRepoKeyPublicKey().getUserRepoKeyId());
		userIdentityDto.setEncryptedUserIdentityPayloadDto(userIdentity.getEncryptedUserIdentityPayloadDtoData());
		userIdentityDto.setSignature(userIdentity.getSignature());
		return userIdentityDto;
	}

}
