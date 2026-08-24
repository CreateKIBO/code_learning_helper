package com.codementor.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.LightPlatformTestCase;

import java.util.concurrent.atomic.AtomicBoolean;

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
}
