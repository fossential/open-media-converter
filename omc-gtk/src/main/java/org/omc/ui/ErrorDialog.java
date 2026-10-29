package org.omc.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.gnome.glib.GLib;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ButtonsType;
import org.gnome.gtk.DialogFlags;
import org.gnome.gtk.Expander;
import org.gnome.gtk.IconSize;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.MessageType;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.TextView;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.gnome.gtk.WrapMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.omc.exception.MediaConverterException;

/**
 * Error dialog helper for displaying user-friendly error messages with detailed
 * information.
 * Provides "Show Details" expander and "Copy to Clipboard" functionality.
 * 
 * <p>
 * Requirements: REQ-007.1
 * </p>
 */
public class ErrorDialog {

        private static final Logger logger = LoggerFactory.getLogger(ErrorDialog.class);

        /**
         * Cap for simultaneous error windows. A failing batch can emit one
         * error per file within a few idle cycles; without a cap that floods
         * the screen with modal windows. Excess dialogs queue FIFO and appear
         * as earlier ones are dismissed.
         */
        private static final int MAX_CONCURRENT_ERROR_DIALOGS = 3;

        private static final ErrorDialogQueue DIALOG_QUEUE = new ErrorDialogQueue(MAX_CONCURRENT_ERROR_DIALOGS);


        /**
         * Shows an error dialog for a generic exception on the GTK main thread.
         * Excess dialogs beyond the concurrency cap are queued and shown when
         * earlier ones close.
         *
         * @param parent    The parent window
         * @param title     The dialog title
         * @param exception The exception to display
         */
        public static void showError(Window parent, String title, Exception exception) {
                logger.error("Showing error dialog: {}", title, exception);

                String userMessage = exception.getMessage() != null ? exception.getMessage()
                                : I18n.tr("An unexpected error occurred");
                String detailsMessage = buildDetailsMessage(exception);

                GLib.idleAdd(0, () -> {
                        DIALOG_QUEUE.offer(() -> showErrorDialog(parent, title, userMessage, detailsMessage));
                        return false;
                });
        }

        /**
         * Shows an error dialog with a custom message on the GTK main thread.
         * Excess dialogs beyond the concurrency cap are queued and shown when
         * earlier ones close.
         *
         * @param parent  The parent window
         * @param title   The dialog title
         * @param message The error message
         */
        public static void showError(Window parent, String title, String message) {
                logger.error("Showing error dialog: {} - {}", title, message);

                GLib.idleAdd(0, () -> {
                        DIALOG_QUEUE.offer(() -> showErrorDialog(parent, title, message, null));
                        return false;
                });
        }

        /**
         * Creates and shows the error dialog with details expander and copy button.
         * Must be called on GTK main thread.
         * 
         * @param parent  The parent window
         * @param title   The dialog title
         * @param message The user-friendly error message
         * @param details The detailed error information (stack trace, etc.)
         */
        private static void showErrorDialog(Window parent, String title, String message, String details) {
                try {
                        // Create a custom window for error dialog (GTK 4 style)
                        Window dialog = new Window();
                        dialog.setTransientFor(parent);
                        dialog.setModal(true);
                        dialog.setTitle(title);
                        dialog.setDefaultSize(550, -1);
                        dialog.setResizable(false);

                        // Create content box
                        Box contentBox = new Box(Orientation.VERTICAL, 12);
                        contentBox.setMarginTop(12);
                        contentBox.setMarginBottom(12);
                        contentBox.setMarginStart(12);
                        contentBox.setMarginEnd(12);

                        // Error icon and message box
                        Box messageBox = new Box(Orientation.HORIZONTAL, 12);

                        // Error icon
                        Image errorIcon = Image.fromIconName("dialog-error");
                        errorIcon.setIconSize(IconSize.LARGE);
                        messageBox.append(errorIcon);

                        // Message label
                        Label messageLabel = new Label(message);
                        messageLabel.setWrap(true);
                        messageLabel.setMaxWidthChars(50);
                        messageLabel.setXalign(0);
                        messageBox.append(messageLabel);

                        contentBox.append(messageBox);

                        // Add details expander if details are provided
                        if (details != null && !details.isEmpty()) {
                                Expander detailsExpander = new Expander(I18n.tr("Show Details"));
                                detailsExpander.setMarginTop(12);

                                ScrolledWindow scrolledWindow = new ScrolledWindow();
                                scrolledWindow.setMinContentHeight(150);
                                scrolledWindow.setMaxContentHeight(300);
                                scrolledWindow.setHexpand(true);
                                scrolledWindow.setVexpand(true);

                                TextView detailsTextView = new TextView();
                                detailsTextView.setEditable(false);
                                detailsTextView.setMonospace(true);
                                detailsTextView.setWrapMode(WrapMode.WORD_CHAR);
                                detailsTextView.getBuffer().setText(details, -1);

                                scrolledWindow.setChild(detailsTextView);
                                detailsExpander.setChild(scrolledWindow);
                                contentBox.append(detailsExpander);
                        }

                        // Button box at bottom
                        Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
                        buttonBox.setHalign(Align.END);
                        buttonBox.setMarginTop(12);

                        // Copy button (if details provided)
                        if (details != null && !details.isEmpty()) {
                                Button copyButton = Button.withLabel(I18n.tr("_Copy Details"));
                                copyButton.setUseUnderline(true);
                                final String detailsText = details;
                                copyButton.onClicked(() -> {
                                        copyToClipboard(copyButton, detailsText);
                                });
                                buttonBox.append(copyButton);
                        }

                        // OK button
                        Button okButton = Button.withLabel(I18n.tr("_OK"));
                        okButton.setUseUnderline(true);
                        okButton.onClicked(() -> {
                                dialog.close();
                        });
                        buttonBox.append(okButton);

                        contentBox.append(buttonBox);

                        // Set content and show
                        dialog.setChild(contentBox);
                        // Release the queue slot when the dialog is dismissed,
                        // letting the next queued error (if any) be shown.
                        dialog.onCloseRequest(() -> {
                                DIALOG_QUEUE.onDialogClosed();
                                return false;
                        });
                        dialog.present();

                } catch (Exception e) {
                        logger.error("Error creating error dialog", e);
                        // Fallback to simple message dialog
                        showFallbackErrorDialog(parent, title, message);
                }
        }

        /**
         * Shows a simple fallback error dialog if the detailed dialog fails.
         * 
         * @param parent  The parent window
         * @param title   The dialog title
         * @param message The error message
         */
        @SuppressWarnings("deprecation")
        private static void showFallbackErrorDialog(Window parent, String title, String message) {
                try {
                        org.gnome.gtk.MessageDialog dialog = new org.gnome.gtk.MessageDialog(
                                        parent,
                                        DialogFlags.MODAL,
                                        MessageType.ERROR,
                                        ButtonsType.OK,
                                        message);
                        dialog.setTitle(title);
                        dialog.show();
                        dialog.onResponse(responseId -> {
                                dialog.destroy();
                                // Fallback dialog also occupies a queue slot.
                                DIALOG_QUEUE.onDialogClosed();
                        });
                } catch (Exception e) {
                        logger.error("Error showing fallback error dialog", e);
                }
        }

        /**
         * Copies text to the system clipboard.
         * 
         * @param widget A widget to get the clipboard from
         * @param text   The text to copy
         */
        private static void copyToClipboard(Widget widget, String text) {
                try {
                        org.gnome.gdk.Clipboard clipboard = widget.getClipboard();
                        clipboard.setText(text);
                        logger.info("Error details copied to clipboard");
                } catch (Exception e) {
                        logger.error("Failed to copy to clipboard", e);
                }
        }

        /**
         * Builds a detailed error message including stack trace.
         * 
         * @param exception The exception
         * @return Detailed error message
         */
        private static String buildDetailsMessage(Exception exception) {
                StringBuilder details = new StringBuilder();

                // Add exception type and message
                details.append(I18n.tr("Exception: ")).append(exception.getClass().getSimpleName()).append("\n");
                details.append(I18n.tr("Message: ")).append(exception.getMessage()).append("\n");

                // Add error code for MediaConverterException
                if (exception instanceof MediaConverterException) {
                        MediaConverterException mme = (MediaConverterException) exception;
                        details.append(I18n.tr("Error Code: ")).append(mme.getErrorCode().getCode()).append("\n");
                        if (mme.getContext() != null) {
                                details.append(I18n.tr("Context: ")).append(mme.getContext()).append("\n");
                        }
                }

                // Add stack trace
                details.append(I18n.tr("\nStack Trace:\n"));
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                exception.printStackTrace(pw);
                details.append(sw.toString());

                return details.toString();
        }
}
