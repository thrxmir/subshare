package org.subshare.gui;

import static co.codewizards.cloudstore.core.oio.OioFileFactory.*;
import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static co.codewizards.cloudstore.core.util.Util.*;
import static org.subshare.gui.util.ResourceBundleUtil.*;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subshare.core.pgp.Pgp;
import org.subshare.core.pgp.PgpKey;
import org.subshare.core.pgp.PgpKeyId;
import org.subshare.core.pgp.man.PgpPrivateKeyPassphraseStore;
import org.subshare.gui.backup.exp.ExportBackupWizard;
import org.subshare.gui.error.ErrorHandler;
import org.subshare.gui.ls.LocalServerInitLs;
import org.subshare.gui.ls.PgpLs;
import org.subshare.gui.ls.PgpPrivateKeyPassphraseManagerLs;
import org.subshare.gui.pgp.privatekeypassphrase.PgpPrivateKeyPassphrasePromptDialog;
import org.subshare.gui.splash.SplashPane;
import org.subshare.gui.util.PlatformUtil;
import org.subshare.gui.welcome.ServerWizard;
import org.subshare.gui.welcome.Welcome;
import org.subshare.gui.welcome.pgp.privatekeypassphrase.IntroWizard;
import org.subshare.gui.wizard.WizardDialog;
import org.subshare.gui.wizard.WizardState;
import org.subshare.ls.server.SsLocalServer;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import co.codewizards.cloudstore.core.appid.AppIdRegistry;
import co.codewizards.cloudstore.core.config.ConfigDir;
import co.codewizards.cloudstore.core.oio.File;
import co.codewizards.cloudstore.core.updater.CloudStoreUpdaterCore;
import co.codewizards.cloudstore.core.updater.Version;
import co.codewizards.cloudstore.core.util.DerbyUtil;
import co.codewizards.cloudstore.ls.client.LocalServerClient;
import co.codewizards.cloudstore.ls.server.LocalServer;

public class SubShareGui extends Application {

	private static final Logger logger = LoggerFactory.getLogger(SubShareGui.class);

	private SsLocalServer localServer;
	private Stage primaryStage;
	private SplashPane splashPane;
	private volatile int exitCode = 0;

	@Override
	public void start(final Stage primaryStage) throws Exception {
		this.primaryStage = primaryStage;

		if (isAfterUpdateHook()) {
			createUpdaterCore().getUpdaterDir().deleteRecursively();

			showUpdateDoneDialog();
			System.exit(1);
		}

		// Show splash...
		showSplash();

		// ...and do initialisation in background.
		startInitThread();
	}

	private boolean isAfterUpdateHook() {
		for (final String parameter : getParameters().getRaw()) {
			if ("afterUpdateHook".equals(parameter))
				return true;
		}
		return false;
	}

	private void showSplash() { // TODO we should *additionally* implement a Preloader, later. JavaFX has a special Preloader class.
		splashPane = new SplashPane();
		final Scene scene = new Scene(splashPane, 400, 300);
		scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
		primaryStage.setScene(scene);
		primaryStage.setTitle("SubShare");
		primaryStage.show();
	}

	private void startInitThread() {
		new Thread() {
			{
				setName("Initialisation");
			}

			@Override
			public void run() {
				try {
					Thread.setDefaultUncaughtExceptionHandler(ErrorHandler.getUncaughtExceptionHandler());
					initLogging();

					// We create the LocalServer before constructing the UI to make sure, the UI can access everything.
					// TODO we should start an external JVM and keep it running when closing - or maybe handle this differently?!
					localServer = new SsLocalServer();
					if (! localServer.start())
						localServer = null;

					LocalServerInitLs.initPrepare();

					final Set<PgpKeyId> pgpKeyIdsHavingPrivateKeyBeforeRestore = getIdsOfMasterKeysWithPrivateKey();
					tryPgpKeysNoPassphrase();
					PlatformUtil.runAndWait(() -> promptPgpKeyPassphrases(primaryStage.getScene().getWindow()));
					if (exitCode != 0) {
						stopLater();
						return;
					}

					final Welcome welcome = new Welcome(primaryStage.getScene().getWindow());
					if (! welcome.welcome()) {
						exitCode = 1;
						stopLater();
						return;
					}

					// If private keys were restored in the Welcome process, we must redo PGP passphrase handling.
					if (!getIdsOfMasterKeysWithPrivateKey().equals(pgpKeyIdsHavingPrivateKeyBeforeRestore)) {
						tryPgpKeysNoPassphrase();
						PlatformUtil.runAndWait(() -> promptPgpKeyPassphrases(primaryStage.getScene().getWindow()));
					}

					PlatformUtil.runAndWait(() -> backupIfNeeded());

					Platform.runLater(new Runnable() {
						@Override
						public void run() {
							try {
								final Parent root = FXMLLoader.load(
										SubShareGui.class.getResource("MainPane.fxml"),
										getMessages(SubShareGui.class));

								final Scene scene = new Scene(root, 800, 600);
								scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
								primaryStage.hide();
								primaryStage.setScene(scene);
								primaryStage.show();
								splashPane = null;

								LocalServerInitLs.initFinish();
							} catch (Exception x) {
								ErrorHandler.handleError(x);
								exitCode = 666;
								stopLater();
							}
						}
					});
				} catch (Exception x) {
					ErrorHandler.handleError(x);
					exitCode = 666;
					stopLater();
				}
			}
		}.start();
	}

	private Set<PgpKeyId> getIdsOfMasterKeysWithPrivateKey() {
		final Collection<PgpKey> masterKeysWithPrivateKey = PgpLs.getPgpOrFail().getMasterKeysWithSecretKey();
		final Set<PgpKeyId> pgpKeyIds = new HashSet<>(masterKeysWithPrivateKey.size());
		for (PgpKey pgpKey : masterKeysWithPrivateKey)
			pgpKeyIds.add(pgpKey.getPgpKeyId());

		return pgpKeyIds;
	}

	protected void stopLater() {
		Platform.runLater(() -> {
			try {
				stop();
			} catch (Exception x) {
				logger.error("stopLater: " + x, x);
			}
		});
	}

	protected void backupIfNeeded() {
		final ExportBackupWizard wizard = new ExportBackupWizard();
		if (wizard.isNeeded())
			new WizardDialog(primaryStage.getScene().getWindow(), wizard).showAndWait();
	}

	@Override
	public void stop() throws Exception {
		PlatformUtil.assertFxApplicationThread();

		if (exitCode == 0)
			backupIfNeeded();

		final CloudStoreUpdaterCore updaterCore = createUpdaterCore();
		if (updaterCore.createUpdaterDirIfUpdateNeeded())
			showUpdateStartingDialog(updaterCore);

		PlatformUtil.notifyExiting();

		final LocalServer _localServer = localServer;
		localServer = null;

		super.stop();

		new Thread() {
			{
				setName(SubShareGui.class.getSimpleName() + ".StopThread");
//				setDaemon(true);
			}

			@Override
			public void run() {
				LocalServerClient.getInstance().close();
				if (_localServer != null)
					_localServer.stop();

				try {
					Thread.sleep(1000L);
				} catch (InterruptedException e) { doNothing(); }

				System.exit(exitCode);
			}

		}.start();
	}

	private CloudStoreUpdaterCore createUpdaterCore() {
		return new CloudStoreUpdaterCore();
	}

	public static void main(final String[] args) {
		launch(args);
	}

	private void tryPgpKeysNoPassphrase() throws InterruptedException {
		final Pgp pgp = PgpLs.getPgpOrFail();
		final PgpPrivateKeyPassphraseStore pgpPrivateKeyPassphraseStore = PgpPrivateKeyPassphraseManagerLs.getPgpPrivateKeyPassphraseStore();
		final Date now = new Date();

		for (final PgpKey pgpKey : pgp.getMasterKeysWithSecretKey()) {
			if (! pgpKey.isValid(now))
				continue;

			final PgpKeyId pgpKeyId = pgpKey.getPgpKeyId();
			if (pgpPrivateKeyPassphraseStore.hasPassphrase(pgpKeyId))
				continue;

			// We try an empty password to prevent a dialog from popping up, if the PGP key is not passphrase-protected.

			// To prevent log pollution as well as speeding this up (LocalServer-RPC does retries in case of *all* exceptions),
			// I first invoke testPassphrase(...) (even though this would not be necessary).
			if (pgp.testPassphrase(pgpKey, new char[0])) {
				try {
					pgpPrivateKeyPassphraseStore.putPassphrase(pgpKeyId, new char[0]);
					// successful => next PGP key
				} catch (Exception x) {
					doNothing();
				}
			}
		}
	}

	private void promptPgpKeyPassphrases(Window owner) {
		final boolean serverWizardIsNeeded = new ServerWizard(true, true).isNeeded();
		boolean introAlreadyShown = false;

		final Pgp pgp = PgpLs.getPgpOrFail();
		final PgpPrivateKeyPassphraseStore pgpPrivateKeyPassphraseStore = PgpPrivateKeyPassphraseManagerLs.getPgpPrivateKeyPassphraseStore();
		final Date now = new Date();

		for (final PgpKey pgpKey : pgp.getMasterKeysWithSecretKey()) {
			if (pgpKey.isRevoked() || !pgpKey.isValid(now))
				continue;

			final PgpKeyId pgpKeyId = pgpKey.getPgpKeyId();
			if (pgpPrivateKeyPassphraseStore.hasPassphrase(pgpKeyId))
				continue;

			if (serverWizardIsNeeded && ! introAlreadyShown) {
				// Show the user a nice introductory explaining why we're going to ask for the PGP key passphrase.
				introAlreadyShown = true;
				final IntroWizard introWizard = new IntroWizard();
				new WizardDialog(owner, introWizard).showAndWait();
				if (introWizard.getState() == WizardState.CANCELLED) {
					exitCode = 2;
					return;
				}
			}

			boolean retry = false;
			String errorMessage = null;
			do {
				final PgpPrivateKeyPassphrasePromptDialog dialog = new PgpPrivateKeyPassphrasePromptDialog(owner, pgpKey, errorMessage);
				dialog.showAndWait();

				retry = false;
				errorMessage = null;

				final char[] passphrase = dialog.getPassphrase();
				if (passphrase != null) {
					try {
						pgpPrivateKeyPassphraseStore.putPassphrase(pgpKeyId, passphrase);
					} catch (SecurityException x) {
						logger.error("promptPgpKeyPassphrases: " + x, x);
						retry = true;
						errorMessage = "Sorry, the passphrase you entered is wrong! Please try again.";
					} catch (Exception x) {
						ErrorHandler.handleError(x);
					}
				}
			} while (retry);
		}
	}

	private static void initLogging() throws IOException, JoranException {
		final File logDir = ConfigDir.getInstance().getLogDir();
		DerbyUtil.setLogFile(createFile(logDir, "derby.log"));

		final String logbackXmlName = "logback.client.xml";
		final File logbackXmlFile = createFile(ConfigDir.getInstance().getFile(), logbackXmlName);
		if (!logbackXmlFile.exists()) {
			AppIdRegistry.getInstance().copyResourceResolvingAppId(
					SubShareGui.class, logbackXmlName, logbackXmlFile);
		}

		final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		try {
		  final JoranConfigurator configurator = new JoranConfigurator();
		  configurator.setContext(context);
		  // Call context.reset() to clear any previous configuration, e.g. default
		  // configuration. For multi-step configuration, omit calling context.reset().
		  context.reset();
		  configurator.doConfigure(logbackXmlFile.createInputStream());
		} catch (final JoranException je) {
			// StatusPrinter will handle this
			doNothing();
		}
		StatusPrinter.printInCaseOfErrorsOrWarnings(context);
	}

	private void showUpdateStartingDialog(final CloudStoreUpdaterCore updaterCore) {
		assertNotNull("updaterCore", updaterCore);
		Alert alert = new Alert(AlertType.INFORMATION);
		alert.setHeaderText("Update to a new Subshare version!");

		final String text = String.format("You are currently using Subshare version %s.\n\nThe new version %s is available and is going to be installed, now.\n\nThe update might take a few minutes - please be patient!",
				updaterCore.getLocalVersion(), updaterCore.getRemoteVersion());

//		alert.setContentText(text);
		// The above does not adjust the dialog size :-( Using a Text node instead works better.

		final Text contentText = new Text(text);
		final HBox contentTextContainer = new HBox();
		contentTextContainer.getChildren().add(contentText);

		GridPane.setMargin(contentText, new Insets(8));
		alert.getDialogPane().setContent(contentTextContainer);

		alert.showAndWait();
	}

	private void showUpdateDoneDialog() {
		Alert alert = new Alert(AlertType.INFORMATION);
		alert.setHeaderText("Update to a new Subshare version done!");

		final Version localVersion = new CloudStoreUpdaterCore().getLocalVersion();

		final String text = String.format("Subshare was updated to version %s.",
				localVersion);

		final Text contentText = new Text(text);
		final HBox contentTextContainer = new HBox();
		contentTextContainer.getChildren().add(contentText);

		GridPane.setMargin(contentText, new Insets(8));
		alert.getDialogPane().setContent(contentTextContainer);

		alert.showAndWait();
	}
}
