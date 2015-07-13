package org.subshare.gui.wizard;

import static co.codewizards.cloudstore.core.util.AssertUtil.*;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.EventHandler;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

/**
 * basic wizard page class
 */
public abstract class WizardPage extends VBox {
	private Wizard wizard;
	protected final Button previousButton = new Button("P_revious");
	protected final Button nextButton = new Button("N_ext");
	protected final Button cancelButton = new Button("Cancel");
	protected final Button finishButton = new Button("_Finish");

	private final ObjectProperty<WizardPage> nextPageProperty = new SimpleObjectProperty<WizardPage>(this, "nextPage") { //$NON-NLS-1$
		@Override
		public void set(WizardPage newValue) {
			super.set(newValue);

			final Wizard wizard = WizardPage.this.getWizard();
			if (wizard != null && wizard != newValue.getWizard())
				newValue.setWizard(wizard);

			updateButtonsDisable();
		}
	};

	private final BooleanProperty completeProperty = new SimpleBooleanProperty(this, "complete", true) { //$NON-NLS-1$
		@Override
		protected void invalidated() {
			updateButtonsDisable();
		}
	};

	protected WizardPage(String title) {
		Label label = new Label(title);
		label.setStyle("-fx-font-weight: bold; -fx-padding: 0 0 5 0;"); //$NON-NLS-1$
		setId(getClass().getSimpleName());
		setSpacing(5);
		setStyle("-fx-padding:10; -fx-background-color: honeydew; -fx-border-color: derive(honeydew, -30%); -fx-border-width: 3;"); //$NON-NLS-1$

		previousButton.setOnAction(event -> getWizard().navToPreviousPage());
		previousButton.setGraphic(new ImageView(WizardPage.class.getResource("left-24x24.png").toExternalForm())); //$NON-NLS-1$

		nextButton.setOnAction(event -> getWizard().navToNextPage());
		nextButton.setGraphic(new ImageView(WizardPage.class.getResource("right-24x24.png").toExternalForm())); //$NON-NLS-1$

		cancelButton.setOnAction(event -> getWizard().cancel());
		cancelButton.setGraphic(new ImageView(WizardPage.class.getResource("cancel-24x24.png").toExternalForm())); //$NON-NLS-1$

		finishButton.setOnAction(event -> getWizard().finish());
		finishButton.setGraphic(new ImageView(WizardPage.class.getResource("ok-24x24.png").toExternalForm())); //$NON-NLS-1$

		finishButton.disabledProperty().addListener(observable -> {
			if (finishButton.disabledProperty().get()) {
				finishButton.setDefaultButton(false);
				nextButton.setDefaultButton(true);
			}
			else {
				nextButton.setDefaultButton(false);
				finishButton.setDefaultButton(true);
			}
		});

		addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
			@Override
			public void handle(KeyEvent event) {
				if (! event.isAltDown())
					return;

				switch (event.getCode()) {
					case LEFT:
						if (!previousButton.isDisabled())
							getWizard().navToPreviousPage();

						event.consume();
						break;
					case RIGHT:
						if (!nextButton.isDisabled())
							getWizard().navToNextPage();

						event.consume();
						break;
					default:
						; // nothing
				}
			}
		});
	}

	private HBox createButtonBar() {
		Region spring = new Region();
		HBox.setHgrow(spring, Priority.ALWAYS);
		HBox buttonBar = new HBox(5);
		cancelButton.setCancelButton(true);
		finishButton.setDefaultButton(true);
		buttonBar.getChildren().addAll(spring, previousButton, nextButton, cancelButton, finishButton);
		return buttonBar;
	}

	/**
	 * Callback-method associating this {@code WizardPage} with the given {@code wizard}.
	 * <p>
	 * Implementors should <i>not</i> override this method, but instead initialise their {@code WizardPage}-sub-class
	 * via overriding {@link #init()}!
	 * <p>
	 * Please note that a {@code WizardPage} cannot be re-used in another {@code Wizard}. Therefore, this method is
	 * only invoked with one single wizard. It may be invoked multiple times, but it makes sure {@link #init()} is
	 * only called once.
	 * @param wizard the {@link Wizard} this {@code WizardPage} is used in. Must not be <code>null</code>.
	 */
	public void setWizard(final Wizard wizard) {
		assertNotNull("wizard", wizard); //$NON-NLS-1$
		if (this.wizard == wizard)
			return;

		if (this.wizard != null)
			throw new IllegalStateException("this.wizard != null :: Cannot re-use WizardPage in another Wizard!"); //$NON-NLS-1$

		this.wizard = wizard;

		Region spring = new Region();
		VBox.setVgrow(spring, Priority.ALWAYS);

		Parent content = createContent();
		if (content == null)
			content = new HBox(new Text(String.format(
					">>> NO CONTENT <<<\n\nYour implementation of WizardPage.createContent() in class\n%s\nreturned null!", //$NON-NLS-1$
					this.getClass().getName())));

		getChildren().add(content);

		getChildren().addAll(spring, createButtonBar());
		finishButton.disableProperty().bind(wizard.canFinishProperty().not());

		init();
		wizard.registerWizardPage(this);

		final WizardPage nextPage = getNextPage();
		if (nextPage != null)
			nextPage.setWizard(wizard);
	}

	/**
	 * Initialisation method to be overridden by sub-classes.
	 * <p>
	 * This method is called once and may be used for initialisation instead of the constructor - which is recommended.
	 */
	protected void init() {
	}

	/**
	 * Callback-method telling this page that it is now shown to the user.
	 */
	protected void onShown() {
	}

	protected void onHidden() {
	}

	protected abstract Parent createContent();

	protected boolean hasNextPage() {
		return getNextPage() != null;
	}

	protected boolean hasPreviousPage() {
		if (getWizard() == null)
			return false;

		return getWizard().hasPreviousPage();
	}

	public ObjectProperty<WizardPage> nextPageProperty() { return nextPageProperty; }
	public WizardPage getNextPage() { return nextPageProperty.get(); }
	public void setNextPage(final WizardPage wizardPage) { nextPageProperty.set(wizardPage); }

	protected Wizard getWizard() {
		return wizard;
	}

	public BooleanProperty completeProperty() {
		return completeProperty;
	}

	public void updateButtonsDisable() {
		previousButton.setDisable(! hasPreviousPage());
		nextButton.setDisable(! (hasNextPage() && completeProperty.get()));
	}
}