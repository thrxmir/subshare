package org.subshare.gui;

import static org.subshare.gui.util.ResourceBundleUtil.*;

import java.io.IOException;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.layout.BorderPane;

public class SampleComponent extends BorderPane {

	public SampleComponent() {
		final FXMLLoader fxmlLoader = new FXMLLoader(
				SampleComponent.class.getResource("Sample.fxml"),
				getResourceBundle(SampleComponent.class, "messages"));

		fxmlLoader.setRoot(this);
		fxmlLoader.setController(this);
		try {
			fxmlLoader.load();
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
	}

	@FXML
	protected void clicked(final ActionEvent event) {
		System.out.println("clicked! " + event);
	}
}
