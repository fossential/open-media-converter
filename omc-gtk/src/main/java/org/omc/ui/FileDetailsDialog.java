package org.omc.ui;

import org.omc.model.ConversionFile;
import org.omc.model.ConversionProgress;
import org.omc.model.ConversionResult;
import org.omc.model.ConversionSettings;
import org.omc.model.ConversionStatus;
import org.omc.model.ConversionTool;
import org.omc.model.FileFormat;

import org.gnome.gdk.Clipboard;
import org.gnome.glib.GLib;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Dialog to display conversion details including tool output, metadata, and
 * status.
 * 
 * <p>
 * This dialog adapts its content based on the file's conversion status:
 * </p>
 * <ul>
 * <li><b>PENDING</b>: Shows source path, output format, and settings
 * preview</li>
 * <li><b>IN_PROGRESS</b>: Shows progress bar, real-time output, speed, and
 * ETA</li>
 * <li><b>COMPLETED</b>: Shows source/output paths, tool output, and
 * statistics</li>
 * <li><b>FAILED</b>: Shows error message and tool output for debugging</li>
 * <li><b>CANCELLED</b>: Shows partial output and cancellation timestamp</li>
 * </ul>
 * 
 * <p>
 * Requirements:
 * </p>
 * <ul>
 * <li>REQ-FL-2.1: Double-click to view details with modal dialog</li>
 * <li>REQ-FL-2.2: Display tool output for completed/failed conversions</li>
 * <li>REQ-FL-2.3: Display progress for in-progress conversions</li>
 * <li>REQ-FL-2.4: Display placeholder for pending conversions</li>
 * </ul>
 * 
 * @see ConversionFile
 * @see ConversionResult
 * @see MainWindowJavaGi#showFileDetailsDialog(String)
 */
public class FileDetailsDialog {

    private static final Logger logger = LoggerFactory.getLogger(FileDetailsDialog.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Window parentWindow;
    private Window dialog;
    /** File whose details the live dialog shows (audit: window-storm reuse). */
    private volatile String displayedFileId;

    // Global conversion settings used for output-format resolution (may be
    // null when constructed without settings; resolution then uses the same
    // core helper on an empty settings instance, which is equivalent for the
    // override-only branch).
    private ConversionSettings globalSettings;

    // Receiver fallback for override-only resolution without global settings.
    private static final ConversionSettings EMPTY_SETTINGS = ConversionSettings.builder().build();

    /**
     * Creates a new FileDetailsDialog.
     *
     * @param parentWindow The parent window for modal behavior
     */
    public FileDetailsDialog(Window parentWindow) {
        this(parentWindow, null);
    }

    /**
     * Creates a new FileDetailsDialog with the current global settings.
     *
     * @param parentWindow   The parent window for modal behavior
     * @param globalSettings Current conversion settings used for output-format
     *                       resolution (nullable)
     */
    public FileDetailsDialog(Window parentWindow, ConversionSettings globalSettings) {
        this.parentWindow = Objects.requireNonNull(parentWindow, "Parent window cannot be null");
        this.globalSettings = globalSettings;
    }

    /**
     * Shows the dialog with file details.
     * Requirement REQ-FL-2.1: Show conversion details dialog.
     * 
     * @param file   The conversion file to display details for
     * @param result The conversion result (nullable for pending/in-progress files)
     */
    /**
     * Refreshes the global settings snapshot used for output-format
     * resolution (the dialog instance is reused across settings changes).
     *
     * @param settings current conversion settings (nullable)
     */
    public void updateSettings(ConversionSettings settings) {
        this.globalSettings = settings;
    }

    public void show(ConversionFile file, ConversionResult result) {
        if (file == null) {
            logger.warn("Cannot show dialog: file is null");
            return;
        }

        // Re-present an already-open dialog for the SAME file instead of
        // stacking duplicate windows on repeated double-clicks (audit fix).
        Window existing = dialog;
        if (existing != null && existing.isVisible() && file.id().equals(displayedFileId)) {
            GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                existing.present();
                return false;
            });
            return;
        }

        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            try {
                buildDialog(file, result);
                dialog.present();
                logger.debug("Showing details dialog for file: {}", file.id());
            } catch (Exception e) {
                logger.error("Error showing file details dialog", e);
                ErrorDialog.showError(parentWindow, I18n.tr("Error"), I18n.tr("Failed to display file details: ") + e.getMessage());
            }
            return false;
        });
    }

    /**
     * Builds the dialog window and assembles all sections.
     * Requirement REQ-FL-2.1: Dialog structure and layout.
     * 
     * @param file   The conversion file
     * @param result The conversion result (nullable)
     */
    private void buildDialog(ConversionFile file, ConversionResult result) {
        // Only one details window per dialog instance is live at a time:
        // close the previous file's window before building the new one.
        Window previous = dialog;
        if (previous != null) {
            previous.close();
        }

        Window newDialog = new Window();
        dialog = newDialog;
        displayedFileId = file.id();
        newDialog.setTitle(I18n.tr("%s - Conversion Details", file.fileName()));
        newDialog.setTransientFor(parentWindow);
        newDialog.setModal(false);
        newDialog.setDefaultSize(700, 500);
        newDialog.setResizable(true);

        // Main layout container
        Box mainBox = new Box(Orientation.VERTICAL, 12);
        mainBox.setMarginTop(12);
        mainBox.setMarginBottom(12);
        mainBox.setMarginStart(12);
        mainBox.setMarginEnd(12);

        // Header section with file information
        mainBox.append(buildHeaderSection(file));

        // Separator
        mainBox.append(new Separator(Orientation.HORIZONTAL));

        // Content section (varies by status)
        Widget contentSection = buildContentSection(file, result);
        mainBox.append(contentSection);

        // Footer with action buttons
        mainBox.append(buildFooterSection(result, newDialog));

        newDialog.setChild(mainBox);
    }

    /**
     * Builds the header section with file metadata.
     * Requirement REQ-FL-2.1: Display file information.
     * 
     * @param file The conversion file
     * @return Widget containing header information
     */
    private Widget buildHeaderSection(ConversionFile file) {
        Box headerBox = new Box(Orientation.VERTICAL, 6);

        // Filename (bold, large)
        Label filenameLabel = Label.builder().setLabel("").build();
        filenameLabel.setMarkup("<span size='large' weight='bold'>" +
                escapeMarkup(file.fileName()) + "</span>");
        filenameLabel.setXalign(0);
        headerBox.append(filenameLabel);

        // File path
        Label pathLabel = new Label(I18n.tr("Source: ") + file.path().toString());
        pathLabel.setXalign(0);
        pathLabel.setSelectable(true);
        pathLabel.addCssClass("dim-label");
        headerBox.append(pathLabel);

        // File size
        String sizeFormatted = I18n.bytes(file.size());
        Label sizeLabel = new Label(I18n.tr("Size: ") + sizeFormatted);
        sizeLabel.setXalign(0);
        sizeLabel.addCssClass("dim-label");
        headerBox.append(sizeLabel);

        // Input format
        Label formatLabel = new Label(I18n.tr("Format: ") + file.format().name());
        formatLabel.setXalign(0);
        formatLabel.addCssClass("dim-label");
        headerBox.append(formatLabel);

        return headerBox;
    }

    /**
     * Builds the content section based on file status.
     * Requirement REQ-FL-2.1: Status-based content display.
     * 
     * @param file   The conversion file
     * @param result The conversion result (nullable)
     * @return Widget containing status-specific content
     */
    private Widget buildContentSection(ConversionFile file, ConversionResult result) {
        return switch (file.status()) {
            case PENDING -> buildPendingContent(file);
            case IN_PROGRESS -> buildInProgressContent(file);
            case COMPLETED -> buildCompletedContent(file, result);
            case FAILED -> buildFailedContent(file, result);
            case CANCELLED -> buildCancelledContent(file, result);
        };
    }

    /**
     * Builds content for pending files.
     * Requirement REQ-FL-2.4: Display placeholder for pending conversions.
     * 
     * @param file The conversion file
     * @return Widget with pending content
     */
    private Widget buildPendingContent(ConversionFile file) {
        ScrolledWindow scrolledWindow = new ScrolledWindow();
        scrolledWindow.setVexpand(true);
        scrolledWindow.setMinContentHeight(200);

        Box contentBox = new Box(Orientation.VERTICAL, 12);
        contentBox.setMarginTop(12);
        contentBox.setMarginBottom(12);
        contentBox.setMarginStart(12);
        contentBox.setMarginEnd(12);

        // Pending message
        Label messageLabel = Label.builder().setLabel("").build();
        messageLabel.setMarkup("<span size='large'>" + escapeMarkup(I18n.tr("⏳ This file has not been converted yet")) + "</span>");
        messageLabel.setXalign(0);
        contentBox.append(messageLabel);

        // Source path
        Label sourceLabel = new Label(I18n.tr("Source: ") + file.path().toString());
        sourceLabel.setXalign(0);
        sourceLabel.setSelectable(true);
        sourceLabel.setWrap(true);
        contentBox.append(sourceLabel);

        // Output format info
        String outputFormat = determineOutputFormat(file);
        Label formatLabel = new Label(I18n.tr("Output format will be: ") + outputFormat);
        formatLabel.setXalign(0);
        contentBox.append(formatLabel);

        // Settings preview (if custom settings)
        if (file.hasCustomSettings() && file.settingsOverride().presetName() != null) {
            Label presetLabel = new Label(I18n.tr("Preset: ") + file.settingsOverride().presetName());
            presetLabel.setXalign(0);
            contentBox.append(presetLabel);
        }

        scrolledWindow.setChild(contentBox);
        return scrolledWindow;
    }

    /**
     * Builds content for in-progress files.
     * Requirement REQ-FL-2.3: Display progress for in-progress conversions.
     * 
     * @param file The conversion file
     * @return Widget with progress information
     */
    private Widget buildInProgressContent(ConversionFile file) {
        ScrolledWindow scrolledWindow = new ScrolledWindow();
        scrolledWindow.setVexpand(true);
        scrolledWindow.setMinContentHeight(200);

        Box contentBox = new Box(Orientation.VERTICAL, 12);
        contentBox.setMarginTop(12);
        contentBox.setMarginBottom(12);
        contentBox.setMarginStart(12);
        contentBox.setMarginEnd(12);

        // In-progress message
        Label messageLabel = Label.builder().setLabel("").build();
        messageLabel.setMarkup("<span size='large'>" + escapeMarkup(I18n.tr("⚙️ Conversion in progress...")) + "</span>");
        messageLabel.setXalign(0);
        contentBox.append(messageLabel);

        // Progress bar
        ProgressBar progressBar = new ProgressBar();
        progressBar.setFraction(file.progress() / 100.0);
        progressBar.setText(I18n.tr("%d%%", file.progress()));
        progressBar.setShowText(true);
        contentBox.append(progressBar);

        // Progress details
        ConversionProgress progressInfo = file.progressInfo();
        if (progressInfo != null) {
            // Conversion speed (using formatSpeed for display)
            String speedStr = I18n.speed(progressInfo.bytesPerSecond());
            Label speedLabel = new Label(I18n.tr("Speed: ") + speedStr);
            speedLabel.setXalign(0);
            contentBox.append(speedLabel);

            // Estimated time remaining
            Duration eta = progressInfo.estimatedTimeRemaining();
            if (eta != null && !eta.isZero() && !eta.isNegative()) {
                Label etaLabel = new Label(I18n.tr("Estimated time remaining: ") + formatDuration(eta));
                etaLabel.setXalign(0);
                contentBox.append(etaLabel);
            }
        }

        // Note about real-time output
        Label noteLabel = new Label(I18n.tr("Tool output will be available after conversion completes."));
        noteLabel.setXalign(0);
        noteLabel.addCssClass("dim-label");
        contentBox.append(noteLabel);

        scrolledWindow.setChild(contentBox);
        return scrolledWindow;
    }

    /**
     * Builds content for completed files.
     * Requirement REQ-FL-2.2: Display tool output for completed conversions.
     * 
     * @param file   The conversion file
     * @param result The conversion result
     * @return Widget with completion details and tool output
     */
    private Widget buildCompletedContent(ConversionFile file, ConversionResult result) {
        Box container = new Box(Orientation.VERTICAL, 12);
        container.setVexpand(true);

        // Metadata section
        Box metadataBox = new Box(Orientation.VERTICAL, 6);

        // Success message
        Label messageLabel = Label.builder().setLabel("").build();
        messageLabel.setMarkup("<span size='large'>" + escapeMarkup(I18n.tr("✓ Conversion completed successfully")) + "</span>");
        messageLabel.setXalign(0);
        metadataBox.append(messageLabel);

        if (result != null) {
            // Source path
            Label sourceLabel = new Label(I18n.tr("Source: ") + file.path().toString());
            sourceLabel.setXalign(0);
            sourceLabel.setSelectable(true);
            sourceLabel.setWrap(true);
            metadataBox.append(sourceLabel);

            // Output path
            result.outputPath().ifPresent(outputPath -> {
                Label outputLabel = new Label(I18n.tr("Output: ") + outputPath.toString());
                outputLabel.setXalign(0);
                outputLabel.setSelectable(true);
                outputLabel.setWrap(true);
                metadataBox.append(outputLabel);
            });

            // Duration
            Duration conversionTime = result.conversionTime();
            if (conversionTime != null) {
                Label durationLabel = new Label(I18n.tr("Duration: ") + formatDuration(conversionTime));
                durationLabel.setXalign(0);
                metadataBox.append(durationLabel);
            }

            // Tool used
            ConversionTool toolUsed = result.toolUsed();
            if (toolUsed != null) {
                Label toolLabel = new Label(I18n.tr("Tool: ") + toolUsed.name());
                toolLabel.setXalign(0);
                metadataBox.append(toolLabel);
            }

            // File size comparison
            if (result.outputSize() > 0) {
                String outputSize = I18n.bytes(result.outputSize());
                Label sizeLabel = new Label(I18n.tr("Output size: ") + outputSize);
                sizeLabel.setXalign(0);
                metadataBox.append(sizeLabel);
            }
        }

        container.append(metadataBox);

        // Tool output section
        if (result != null && result.toolOutput().isPresent()) {
            Label outputHeaderLabel = new Label(I18n.tr("Tool Output:"));
            outputHeaderLabel.setXalign(0);
            outputHeaderLabel.setMarginTop(6);
            container.append(outputHeaderLabel);

            ScrolledWindow scrolledWindow = new ScrolledWindow();
            scrolledWindow.setVexpand(true);
            scrolledWindow.setMinContentHeight(200);

            TextView textView = new TextView();
            textView.setEditable(false);
            textView.setMonospace(true);
            textView.setWrapMode(WrapMode.WORD_CHAR);
            textView.getBuffer().setText(result.toolOutput().get(), -1);

            scrolledWindow.setChild(textView);
            container.append(scrolledWindow);
        } else {
            Label noOutputLabel = new Label(I18n.tr("No output available"));
            noOutputLabel.setXalign(0);
            noOutputLabel.addCssClass("dim-label");
            container.append(noOutputLabel);
        }

        return container;
    }

    /**
     * Builds content for failed files.
     * Requirement REQ-FL-2.2: Display tool output for failed conversions.
     * 
     * @param file   The conversion file
     * @param result The conversion result
     * @return Widget with error details and tool output
     */
    private Widget buildFailedContent(ConversionFile file, ConversionResult result) {
        Box container = new Box(Orientation.VERTICAL, 12);
        container.setVexpand(true);

        // Error message section
        Box errorBox = new Box(Orientation.VERTICAL, 6);

        // Failed message
        Label messageLabel = Label.builder().setLabel("").build();
        messageLabel.setMarkup("<span size='large'>" + escapeMarkup(I18n.tr("✗ Conversion failed")) + "</span>");
        messageLabel.setXalign(0);
        errorBox.append(messageLabel);

        // Error message from file or result
        String errorMessage = file.errorMessage();
        if (errorMessage == null && result != null) {
            errorMessage = result.errorMessage().orElse(null);
        }

        if (errorMessage != null) {
            Label errorLabel = new Label(I18n.tr("Error: ") + errorMessage);
            errorLabel.setXalign(0);
            errorLabel.setWrap(true);
            errorLabel.setMaxWidthChars(80);
            errorLabel.addCssClass("error");
            errorBox.append(errorLabel);
        }

        // Source path
        Label sourceLabel = new Label(I18n.tr("Source: ") + file.path().toString());
        sourceLabel.setXalign(0);
        sourceLabel.setSelectable(true);
        sourceLabel.setWrap(true);
        errorBox.append(sourceLabel);

        container.append(errorBox);

        // Tool output section
        if (result != null && result.toolOutput().isPresent()) {
            Label outputHeaderLabel = new Label(I18n.tr("Tool Output:"));
            outputHeaderLabel.setXalign(0);
            outputHeaderLabel.setMarginTop(6);
            container.append(outputHeaderLabel);

            ScrolledWindow scrolledWindow = new ScrolledWindow();
            scrolledWindow.setVexpand(true);
            scrolledWindow.setMinContentHeight(200);

            TextView textView = new TextView();
            textView.setEditable(false);
            textView.setMonospace(true);
            textView.setWrapMode(WrapMode.WORD_CHAR);
            textView.getBuffer().setText(result.toolOutput().get(), -1);

            scrolledWindow.setChild(textView);
            container.append(scrolledWindow);
        } else {
            Label noOutputLabel = new Label(I18n.tr("No output available"));
            noOutputLabel.setXalign(0);
            noOutputLabel.addCssClass("dim-label");
            container.append(noOutputLabel);
        }

        return container;
    }

    /**
     * Builds content for cancelled files.
     * Requirement REQ-FL-2.2: Display partial output for cancelled conversions.
     * 
     * @param file   The conversion file
     * @param result The conversion result
     * @return Widget with cancellation details and partial output
     */
    private Widget buildCancelledContent(ConversionFile file, ConversionResult result) {
        Box container = new Box(Orientation.VERTICAL, 12);
        container.setVexpand(true);

        // Cancellation message section
        Box messageBox = new Box(Orientation.VERTICAL, 6);

        // Cancelled message
        Label messageLabel = Label.builder().setLabel("").build();
        messageLabel.setMarkup("<span size='large'>" + escapeMarkup(I18n.tr("⚠ Conversion cancelled")) + "</span>");
        messageLabel.setXalign(0);
        messageBox.append(messageLabel);

        // Source path
        Label sourceLabel = new Label(I18n.tr("Source: ") + file.path().toString());
        sourceLabel.setXalign(0);
        sourceLabel.setSelectable(true);
        sourceLabel.setWrap(true);
        messageBox.append(sourceLabel);

        // Cancellation note
        Label noteLabel = new Label(I18n.tr("The conversion was cancelled before completion."));
        noteLabel.setXalign(0);
        noteLabel.addCssClass("dim-label");
        messageBox.append(noteLabel);

        container.append(messageBox);

        // Partial tool output section
        if (result != null && result.toolOutput().isPresent()) {
            Label outputHeaderLabel = new Label(I18n.tr("Partial Tool Output:"));
            outputHeaderLabel.setXalign(0);
            outputHeaderLabel.setMarginTop(6);
            container.append(outputHeaderLabel);

            ScrolledWindow scrolledWindow = new ScrolledWindow();
            scrolledWindow.setVexpand(true);
            scrolledWindow.setMinContentHeight(200);

            TextView textView = new TextView();
            textView.setEditable(false);
            textView.setMonospace(true);
            textView.setWrapMode(WrapMode.WORD_CHAR);
            textView.getBuffer().setText(result.toolOutput().get(), -1);

            scrolledWindow.setChild(textView);
            container.append(scrolledWindow);
        } else {
            Label noOutputLabel = new Label(I18n.tr("No output available"));
            noOutputLabel.setXalign(0);
            noOutputLabel.addCssClass("dim-label");
            container.append(noOutputLabel);
        }

        return container;
    }

    /**
     * Builds the footer section with action buttons.
     * Requirement REQ-FL-2.1: Dialog buttons (Close, Copy to Clipboard).
     * 
     * @param result The conversion result (for clipboard copy)
     * @return Widget containing footer buttons
     */
    private Widget buildFooterSection(ConversionResult result, Window dialogWindow) {
        Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
        buttonBox.setHalign(Align.END);
        buttonBox.setMarginTop(12);

        // Copy to Clipboard button (enabled only if tool output exists)
        boolean hasOutput = result != null && result.toolOutput().isPresent();
        if (hasOutput) {
            Button copyButton = Button.withLabel(I18n.tr("_Copy to Clipboard"));
            copyButton.setUseUnderline(true);
            copyButton.onClicked(() -> {
                copyToolOutputToClipboard(result, dialogWindow, copyButton);
            });
            buttonBox.append(copyButton);
        }

        // Close button: closes exactly the window this footer was built for,
        // not whichever window the mutable dialog field currently references.
        Button closeButton = Button.withLabel(I18n.tr("_Close"));
        closeButton.setUseUnderline(true);
        closeButton.onClicked(dialogWindow::close);
        buttonBox.append(closeButton);

        return buttonBox;
    }

    /**
     * Copies tool output to system clipboard and shows confirmation.
     * Requirement REQ-FL-2.1: Copy to clipboard functionality.
     *
     * @param result       The conversion result with tool output
     * @param dialogWindow The window this action belongs to (captured at build
     *                     time, not the mutable dialog field)
     * @param copyButton   The button that triggered the copy
     */
    private void copyToolOutputToClipboard(ConversionResult result, Window dialogWindow, Button copyButton) {
        if (result == null || result.toolOutput().isEmpty()) {
            logger.warn("Cannot copy to clipboard: no tool output available");
            return;
        }

        try {
            String toolOutput = result.toolOutput().get();
            org.gnome.gdk.Clipboard clipboard = dialogWindow.getClipboard();
            clipboard.setText(toolOutput);

            logger.info("Tool output copied to clipboard ({} characters)", toolOutput.length());

            // Show brief confirmation (could use toast in GTK 4.10+)
            showCopyConfirmation(copyButton);
        } catch (Exception e) {
            logger.error("Failed to copy to clipboard", e);
            ErrorDialog.showError(parentWindow, I18n.tr("Copy Failed"), I18n.tr("Failed to copy tool output to clipboard"));
        }
    }

    /**
     * Shows a brief confirmation that output was copied.
     *
     * @param copyButton The button to temporarily relabel
     */
    private void showCopyConfirmation(Button copyButton) {
        String originalLabel = copyButton.getLabel();
        copyButton.setLabel(I18n.tr("Copied!"));
        // Revert after 2 seconds
        GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 2000, () -> {
            copyButton.setLabel(originalLabel);
            return false; // Don't repeat
        });
        logger.debug("Tool output copied to clipboard");
    }

    /**
     * Determines the output format for a file (for pending status).
     *
     * <p>
     * Format resolution is delegated to the canonical core helper
     * {@link ConversionSettings#resolveOutputFormat(ConversionFile)}; this
     * method keeps the dialog's display precedence (override format name,
     * preset name, "Custom settings", then "Global settings" for files without
     * an override).
     * </p>
     *
     * @param file The conversion file
     * @return Output format string or "Not Set"
     */
    private String determineOutputFormat(ConversionFile file) {
        if (file.hasCustomSettings()) {
            ConversionSettings resolver = globalSettings != null ? globalSettings : EMPTY_SETTINGS;
            FileFormat outputFormat = resolver.resolveOutputFormat(file);
            if (outputFormat != null) {
                return outputFormat.name();
            } else {
                return file.settingsOverride().presetName() != null ? file.settingsOverride().presetName()
                        : I18n.tr("Custom settings");
            }
        }
        return I18n.tr("Global settings");
    }

    /**
     * Formats a duration for display.
     * 
     * @param duration The duration to format
     * @return Formatted duration string (e.g., "1m 23s")
     */
    private String formatDuration(Duration duration) {
        return I18n.duration(duration);
    }

    /**
     * Escapes markup characters for GTK labels.
     * 
     * @param text The text to escape
     * @return Escaped text safe for markup
     */
    private String escapeMarkup(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
