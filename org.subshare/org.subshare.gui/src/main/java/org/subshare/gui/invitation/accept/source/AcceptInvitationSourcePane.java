package org.subshare.gui.invitation.accept.source;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import static org.subshare.gui.util.FxmlUtil.*;

import java.util.Iterator;

import javafx.beans.InvalidationListener;
import javafx.fxml.FXML;
import javafx.scene.layout.GridPane;

import org.subshare.core.file.DataFileFilter;
import org.subshare.core.file.EncryptedDataFile;
import org.subshare.gui.filetree.FileTreePane;
import org.subshare.gui.invitation.accept.AcceptInvitationData;

import co.codewizards.cloudstore.core.oio.File;

public abstract class AcceptInvitationSourcePane extends GridPane {
	private final AcceptInvitationData acceptInvitationData;

	@FXML
	private FileTreePane fileTreePane;

	public AcceptInvitationSourcePane(final AcceptInvitationData acceptInvitationData) {
		this.acceptInvitationData = assertNotNull("acceptInvitationData", acceptInvitationData);
		loadDynamicComponentFxml(AcceptInvitationSourcePane.class, this);
		fileTreePane.fileFilterProperty().set(new DataFileFilter().setAcceptContentType(EncryptedDataFile.CONTENT_TYPE_VALUE));
		fileTreePane.getSelectedFiles().addListener((InvalidationListener) observable -> onSelectedFilesChanged());
		onSelectedFilesChanged();
	}

	protected boolean isComplete() {
		return acceptInvitationData.getInvitationFile() != null;
	}

	protected void onSelectedFilesChanged() {
		final Iterator<File> selectedFilesIterator = fileTreePane.getSelectedFiles().iterator();
		File file = selectedFilesIterator.hasNext() ? selectedFilesIterator.next() : null;

		if (file != null && ! file.isFile())
			file = null;

		if (file != null) {
			// TODO try to decrypt and check whether it's really an invitation token file!
			// Show an error message somehow and set file to null, if the decryption fails or if it's
			// not an invitation token file!
		}

		acceptInvitationData.setInvitationFile(file);
		updateComplete();
	}

	protected abstract void updateComplete();

	@Override
	public void requestFocus() {
		super.requestFocus();
		fileTreePane.requestFocus();
	}
}
