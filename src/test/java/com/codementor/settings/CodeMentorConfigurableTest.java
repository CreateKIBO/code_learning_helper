package com.codementor.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.LightPlatformTestCase;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Regression coverage for Settings-dialog modality and asynchronous UI completion. */
public class CodeMentorConfigurableTest extends LightPlatformTestCase {

    public void testCompletionCallbackRunsWhileSettingsModalIsOpen() throws Throwable {
        Object modalEntity = new Object();
        AtomicBoolean nonModalCallback = new AtomicBoolean();
        AtomicBoolean settingsCallback = new AtomicBoolean();

        EdtTestUtil.runInEdtAndWait(() -> LaterInvocator.enterModal(modalEntity));
        try {
            ApplicationManager.getApplication().invokeLater(
                    () -> nonModalCallback.set(true), ModalityState.nonModal());
            CodeMentorConfigurable.invokeOnSettingsUi(
                    () -> settingsCallback.set(true));

            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

            assertFalse("non-modal work must wait for the dialog", nonModalCallback.get());
            assertTrue("pure Settings UI completion must run inside the dialog",
                    settingsCallback.get());
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> LaterInvocator.leaveModal(modalEntity));
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        }
        assertTrue(nonModalCallback.get());
    }

    public void testSelectingPresetFillsEndpointAndModelButClearsKeyInput() throws Throwable {
        CodeMentorConfigurable configurable = new CodeMentorConfigurable();
        AtomicReference<JComponent> component = new AtomicReference<>();
        EdtTestUtil.runInEdtAndWait(() -> component.set(configurable.createComponent()));
        try {
            JComboBox<?> presetCombo = descendants(component.get(), JComboBox.class).stream()
                    .filter(combo -> combo.getItemCount() > 0
                            && combo.getItemAt(0) instanceof ModelProviderPreset)
                    .findFirst()
                    .orElseThrow();
            JPasswordField keyField = descendants(
                    component.get(), JPasswordField.class).get(0);

            EdtTestUtil.runInEdtAndWait(() -> {
                keyField.setText("temporary-key");
                presetCombo.setSelectedItem(ModelProviderPreset.DEEPSEEK_V4_FLASH);
            });

            List<JTextField> textFields = descendants(component.get(), JTextField.class);
            assertEquals(0, keyField.getPassword().length);
            assertTrue(textFields.stream().anyMatch(field ->
                    "https://api.deepseek.com".equals(field.getText())));
            assertTrue(textFields.stream().anyMatch(field ->
                    "deepseek-v4-flash".equals(field.getText())));
        } finally {
            EdtTestUtil.runInEdtAndWait(configurable::disposeUIResources);
        }
    }

    private static <T extends Component> List<T> descendants(
            Component root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        collectDescendants(root, type, matches);
        return matches;
    }

    private static <T extends Component> void collectDescendants(
            Component component, Class<T> type, List<T> matches) {
        if (type.isInstance(component)) matches.add(type.cast(component));
        if (!(component instanceof Container container)) return;
        for (Component child : container.getComponents()) {
            collectDescendants(child, type, matches);
        }
    }
}
