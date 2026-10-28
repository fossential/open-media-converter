package org.omc.ui;

import org.omc.ui.AboutDialogHelper;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for AboutDialogHelper.
 * 
 * IMPORTANT: These tests are disabled in headless environments because they
 * require
 * GTK 4 display initialization. GTK widgets cannot be instantiated without a
 * valid
 * X11/Wayland display connection.
 * 
 * Tests will be skipped when:
 * - DISPLAY environment variable is not set
 * - CI environment variable is set (continuous integration)
 * - Running in headless mode
 * 
 * To run these tests locally, ensure you have:
 * - Active X11/Wayland session
 * - DISPLAY environment variable set (e.g., DISPLAY=:0)
 * - GTK 4 runtime available
 */
@ExtendWith(MockitoExtension.class)
class AboutDialogHelperTest {

    @Mock
    private Window mockParentWindow;

    @BeforeAll
    static void checkGtkEnvironment() {
        String display = System.getenv("DISPLAY");
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");

        if (display == null && waylandDisplay == null) {
            System.err.println("WARNING: No display environment detected. GTK tests will be skipped.");
            System.err.println("To run these tests, ensure DISPLAY or WAYLAND_DISPLAY is set.");
        }
    }

    /**
     * Test that AboutDialogHelper.show() handles null parent window.
     * 
     * DISABLED: This test requires GTK display initialization which is not
     * available
     * in headless test environments. Attempting to create GTK widgets without a
     * display
     * causes JVM crashes (SIGSEGV in native GTK code).
     * 
     * To enable: Run with active X11/Wayland display and remove @Disabled
     * annotation.
     */
    @Test
    @Disabled("Requires GTK display - causes JVM crash in headless environment (SIGSEGV)")
    void show_shouldHandleNullParentWindow() {
        // This test would instantiate real GTK AboutDialog which requires display
        // In headless environment, this causes: SIGSEGV in
        // org.gnome.gtk.AboutDialog.<init>
        AboutDialogHelper.show(null);
    }

    /**
     * Test that AboutDialogHelper.show() handles exceptions during dialog creation.
     * 
     * DISABLED: Cannot test GTK widget creation in headless environment.
     * Real GTK AboutDialog instantiation requires valid display connection.
     */
    @Test
    @Disabled("Requires GTK display - causes JVM crash in headless environment (SIGSEGV)")
    void show_shouldHandleExceptionDuringDialogCreation() {
        // Cannot mock GTK AboutDialog constructor called inside static method
        // Real instantiation causes SIGSEGV in test environment without display
        AboutDialogHelper.show(mockParentWindow);
    }

    /**
     * Test that AboutDialogHelper.show() calls ErrorDialog on failure.
     * 
     * DISABLED: Cannot safely trigger GTK failures without display.
     * Native GTK crashes occur before Java exception handling can catch them.
     */
    @Test
    @Disabled("Requires GTK display - causes JVM crash in headless environment (SIGSEGV)")
    void show_shouldCallErrorDialogOnFailure() {
        // Cannot test error handling because GTK initialization failure
        // causes native SIGSEGV before Java exception handlers execute
        AboutDialogHelper.show(mockParentWindow);
    }

    /**
     * Basic smoke test that verifies the class loads without errors.
     * This test CAN run in headless environments as it doesn't instantiate GTK
     * widgets.
     */
    @Test
    void aboutDialogHelper_shouldLoadClass() {
        // Verify class loads and has expected structure
        // This doesn't trigger GTK initialization
        Class<?> clazz = AboutDialogHelper.class;

        // Verify show method exists
        try {
            clazz.getMethod("show", Window.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("show(Window) method should exist", e);
        }
    }

    /**
     * The version must come from the package implementation version (set by the
     * Maven build) and fall back to the pom version when absent, e.g. when
     * running from target/classes in tests. Runs headless (no GTK).
     */
    @Test
    void resolveVersion_fallsBackToPomVersionWhenManifestMissing() {
        String fromManifest = AboutDialogHelper.class.getPackage().getImplementationVersion();
        String expected = fromManifest != null ? fromManifest : "0.1.2";
        assertEquals(expected, AboutDialogHelper.resolveVersion());
    }

    /** URLs must point at github.com (not the previous github.org typo). */
    @Test
    void urls_useGithubCom() throws Exception {
        java.lang.reflect.Field website = AboutDialogHelper.class.getDeclaredField("WEBSITE");
        website.setAccessible(true);
        java.lang.reflect.Field authors = AboutDialogHelper.class.getDeclaredField("AUTHORS");
        authors.setAccessible(true);

        String websiteUrl = (String) website.get(null);
        assertTrue(websiteUrl.startsWith("https://github.com/"),
                "Website URL must use github.com: " + websiteUrl);

        String[] authorEntries = (String[]) authors.get(null);
        boolean hasContributorUrl = false;
        for (String entry : authorEntries) {
            if (entry.startsWith("http")) {
                assertTrue(entry.startsWith("https://github.com/"),
                        "Author URL must use github.com: " + entry);
                hasContributorUrl = true;
            }
        }
        assertTrue(hasContributorUrl, "Authors list must include the contributors URL");
    }
}
