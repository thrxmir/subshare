package org.subshare.core.pgp.gnupg;

import static co.codewizards.cloudstore.core.oio.OioFileFactory.createFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.codewizards.cloudstore.core.config.Config;
import co.codewizards.cloudstore.core.oio.File;
import co.codewizards.cloudstore.core.util.IOUtil;

public class GnuPgDir {

	private static final Logger logger = LoggerFactory.getLogger(GnuPgDir.class);

	public static final String CONFIG_KEY_GNU_PG_DIR = "gnupg.dir";

	private static final class Holder {
		public static final GnuPgDir instance = new GnuPgDir();
	}

	public static GnuPgDir getInstance() {
		return Holder.instance;
	}

	protected GnuPgDir() { }

	public File getFile() {
		final String dirString = Config.getInstance().getProperty(CONFIG_KEY_GNU_PG_DIR, "${user.home}/.gnupg");
		logger.debug("getFile: dirString={}", dirString);
		final String resolvedDir = IOUtil.replaceTemplateVariables(dirString, System.getProperties());
		final File result = createFile(resolvedDir).getAbsoluteFile();
		logger.debug("getFile: result={}", result);
		return result;
	}
}
