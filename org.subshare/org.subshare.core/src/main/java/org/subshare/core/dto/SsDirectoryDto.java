package org.subshare.core.dto;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.subshare.core.io.InputStreamSource;
import org.subshare.core.io.MultiInputStream;
import org.subshare.core.sign.Signature;

import co.codewizards.cloudstore.core.dto.DirectoryDto;

@SuppressWarnings("serial")
@XmlRootElement
public class SsDirectoryDto extends DirectoryDto implements SsRepoFileDto {
	public static final String SIGNED_DATA_TYPE = "Directory";

	private String parentName;

	private String realName;

	@XmlElement
	private SignatureDto signatureDto;

	@Override
	public String getParentName() {
		return parentName;
	}
	@Override
	public void setParentName(final String parentName) {
		this.parentName = parentName;
	}

	public String getRealName() {
		return realName;
	}
	public void setRealName(final String realName) {
		this.realName = realName;
	}

	@Override
	public void setName(final String name) {
		if ("".equals(name))
			realName = getName();

		super.setName(name);
	}

	@Override
	public String getSignedDataType() {
		return SIGNED_DATA_TYPE;
	}

	@Override
	public int getSignedDataVersion() {
		return 0;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * <b>Important:</b> The implementation in {@code SsDirectory} must exactly match the one in {@code SsDirectoryDto}!
	 */
	@Override
	public InputStream getSignedData(final int signedDataVersion) {
		try {
			byte separatorIndex = 0;
			return new MultiInputStream(
					InputStreamSource.Helper.createInputStreamSource(getName()),

					InputStreamSource.Helper.createInputStreamSource(++separatorIndex),
					InputStreamSource.Helper.createInputStreamSource(parentName),

					InputStreamSource.Helper.createInputStreamSource(++separatorIndex),
					InputStreamSource.Helper.createInputStreamSource(getLastModified())
					);
		} catch (final IOException x) {
			throw new RuntimeException(x);
		}
	}

	@XmlTransient
	@Override
	public Signature getSignature() {
		return signatureDto;
	}

	@Override
	public void setSignature(final Signature signature) {
		this.signatureDto = SignatureDto.copyIfNeeded(signature);
	}

}
