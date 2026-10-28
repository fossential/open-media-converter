package org.omc.ui;

import org.gnome.gtk.License;
import org.gnome.gtk.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * About dialog helper for showing application information.
 * Creates and displays a GTK AboutDialog with application metadata.
 * 
 * Requirements:
 * - REQ-102.1: Application About dialog with version, license, and credits
 */
public class AboutDialogHelper {
    private static final Logger logger = LoggerFactory.getLogger(AboutDialogHelper.class);

    // Application metadata
    private static final String APPLICATION_NAME = "Open Media Converter";
    // Version from the jar manifest (set by Maven); falls back to the pom
    // version when absent, e.g. when running from unpacked classes.
    private static final String FALLBACK_VERSION = "0.1.2";
    private static final String WEBSITE = "https://github.com/omc/open-media-converter";
    private static final String WEBSITE_LABEL = "GitHub Repository";
    private static final String COPYRIGHT = "Copyright © 2025 Open Media Converter Contributors";

    // Application description
    private static final String COMMENTS = "A native Linux desktop application for converting video, audio, image, and document formats.\n\n"
            +
            "Supports batch processing with progress tracking, format presets, and persistent session state.";

    // Authors
    private static final String[] AUTHORS = {
            "Open Media Converter Contributors",
            "https://github.com/omc/open-media-converter/graphs/contributors"
    };

    // License text (MIT License)
    private static final String LICENSE_TEXT = "MIT License\n\n" +
            "Copyright (c) 2025 Open Media Converter Contributors\n\n" +
            "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files (the \"Software\"), to deal\n" +
            "in the Software without restriction, including without limitation the rights\n" +
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n" +
            "copies of the Software, and to permit persons to whom the Software is\n" +
            "furnished to do so, subject to the following conditions:\n\n" +
            "The above copyright notice and this permission notice shall be included in all\n" +
            "copies or substantial portions of the Software.\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n" +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n" +
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n" +
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n" +
            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n" +
            "SOFTWARE.";

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private AboutDialogHelper() {
        // Utility class
    }

    /**
     * Resolves the application version for the About dialog.
     *
     * <p>
     * Reads {@code Implementation-Version} from the package (populated from
     * the jar manifest during a Maven build). Falls back to the pom version
     * when the manifest attribute is absent, e.g. when running from unpacked
     * classes in tests or an IDE.
     * </p>
     *
     * @return the implementation version, or {@code "0.1.2"} when unset
     */
    static String resolveVersion() {
        String version = AboutDialogHelper.class.getPackage().getImplementationVersion();
        return version != null ? version : FALLBACK_VERSION;
    }

    /**
     * Shows the About dialog with application information.
     * Creates a GTK AboutDialog and displays it modally over the parent window.
     *
     * Requirement REQ-102.1: Display application name, version, copyright, license,
     * website, authors, and description
     *
     * @param parent the parent window for the dialog
     */
    public static void show(Window parent) {
        logger.debug("Showing About dialog");

        try {
            // Create AboutDialog
            org.gnome.gtk.AboutDialog dialog = new org.gnome.gtk.AboutDialog();

            // Set parent window for modal behavior
            if (parent != null) {
                dialog.setTransientFor(parent);
                dialog.setModal(true);
            }

            // Set application metadata
            // Requirement REQ-102.1: Application name and version
            dialog.setProgramName(APPLICATION_NAME);
            dialog.setVersion(resolveVersion());

            // Requirement REQ-102.1: Copyright and license
            dialog.setCopyright(COPYRIGHT);
            dialog.setLicense(LICENSE_TEXT);
            dialog.setLicenseType(License.MIT_X11);
            dialog.setWrapLicense(true);

            // Requirement REQ-102.1: Website URL
            dialog.setWebsite(WEBSITE);
            dialog.setWebsiteLabel(WEBSITE_LABEL);

            // Requirement REQ-102.1: Authors list
            dialog.setAuthors(AUTHORS);

            // Requirement REQ-102.1: Application description
            dialog.setComments(COMMENTS);

            // Set logo icon name to use our custom application icon
            // Requirement REQ-102.1: Application icon
            dialog.setLogoIconName("open-media-converter");

            // Add system information
            String systemInfo = String.format(
                    "Built with Java %s\nGTK 4 via java-gi bindings",
                    System.getProperty("java.version"));
            dialog.setSystemInformation(systemInfo);

            // Show the dialog
            dialog.present();

            logger.debug("About dialog shown successfully");

        } catch (Exception e) {
            logger.error("Failed to show About dialog", e);
            // Fallback: Show error dialog if About dialog fails
            ErrorDialog.showError(parent, "Failed to show About dialog", e);
        }
    }
}
