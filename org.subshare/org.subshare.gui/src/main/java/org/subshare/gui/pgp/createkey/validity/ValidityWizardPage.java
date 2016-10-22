package org.subshare.gui.pgp.createkey.validity;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;

import org.subshare.core.pgp.CreatePgpKeyParam;
import org.subshare.gui.pgp.createkey.advanced.AdvancedWizardPage;
import org.subshare.gui.wizard.WizardPage;

import javafx.scene.Parent;

public class ValidityWizardPage extends WizardPage {

	private final CreatePgpKeyParam createPgpKeyParam;

	public ValidityWizardPage(CreatePgpKeyParam createPgpKeyParam) {
		super("OpenPGP key validity");
		this.createPgpKeyParam = assertNotNull("createPgpKeyParam", createPgpKeyParam);
		setNextPage(new AdvancedWizardPage(createPgpKeyParam));
	}

	@Override
	protected Parent createContent() {
		return new ValidityPane(createPgpKeyParam);
	}
}
