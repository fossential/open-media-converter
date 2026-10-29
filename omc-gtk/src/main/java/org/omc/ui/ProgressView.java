package org.omc.ui;

import org.omc.model.BatchProgress;
import org.omc.model.ConversionProgress;
import org.gnome.gtk.Label;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.Revealer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom component for displaying conversion progress.
 * 
 * <p>
 * Displays overall batch progress with file counts, progress bars,
 * time remaining estimates, and conversion speed metrics.
 * </p>
 * 
 * <p>
 * <strong>Thread Safety:</strong> This component is NOT thread-safe for GTK
 * widget updates.
 * All public methods that modify the UI MUST be called on the GTK main thread.
 * Callers from background threads should use {@code GLib.idleAdd()} to marshal
 * calls
 * to the main thread (typically handled by {@link MainWindowJavaGi}).
 * </p>
 * 
 * <p>
 * The internal {@code fileProgressMap} uses {@code ConcurrentHashMap} for
 * thread-safe
 * storage, but GTK widget operations require main thread execution.
 * </p>
 * 
 * <p>
 * Requirements: REQ-004.3 - Progress tracking and reporting
 * </p>
 */
public class ProgressView {

    private static final Logger logger = LoggerFactory.getLogger(ProgressView.class);

    private final Revealer revealer;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private final Label timeRemainingLabel;
    private final Label conversionSpeedLabel;

    // Track individual file progress for detailed logging (thread-safe)
    private final Map<String, ConversionProgress> fileProgressMap = new ConcurrentHashMap<>();

    /**
     * Constructs the progress view component.
     * 
     * <p>
     * All widgets must be non-null and properly initialized GTK widgets.
     * </p>
     * 
     * @param revealer             the GTK Revealer widget (must not be null)
     * @param progressBar          the progress bar widget (must not be null)
     * @param statusLabel          the status label widget (must not be null)
     * @param timeRemainingLabel   the time remaining label widget (must not be
     *                             null)
     * @param conversionSpeedLabel the conversion speed label widget (must not be
     *                             null)
     * @throws NullPointerException if any widget is null
     */
    public ProgressView(Revealer revealer, ProgressBar progressBar, Label statusLabel,
            Label timeRemainingLabel, Label conversionSpeedLabel) {
        this.revealer = Objects.requireNonNull(revealer, "revealer must not be null");
        this.progressBar = Objects.requireNonNull(progressBar, "progressBar must not be null");
        this.statusLabel = Objects.requireNonNull(statusLabel, "statusLabel must not be null");
        this.timeRemainingLabel = Objects.requireNonNull(timeRemainingLabel,
                "timeRemainingLabel must not be null");
        this.conversionSpeedLabel = Objects.requireNonNull(conversionSpeedLabel,
                "conversionSpeedLabel must not be null");

        logger.debug("ProgressView initialized");
    }

    /**
     * Shows the progress view.
     * 
     * <p>
     * <strong>Thread Safety:</strong> Must be called on the GTK main thread.
     * </p>
     */
    public void show() {
        revealer.setRevealChild(true);
        logger.debug("Progress view shown");
    }

    /**
     * Hides the progress view.
     * 
     * <p>
     * <strong>Thread Safety:</strong> Must be called on the GTK main thread.
     * </p>
     */
    public void hide() {
        revealer.setRevealChild(false);
        logger.debug("Progress view hidden");
    }

    /**
     * Updates the overall batch progress display.
     * 
     * <p>
     * This is the primary method for updating progress, using the
     * BatchProgress model that contains all necessary metrics.
     * </p>
     * 
     * <p>
     * <strong>Thread Safety:</strong> Must be called on the GTK main thread.
     * Use {@code GLib.idleAdd()} from background threads.
     * </p>
     * 
     * @param batchProgress the batch progress information
     */
    public void updateOverallProgress(BatchProgress batchProgress) {
        if (batchProgress == null) {
            logger.warn("Received null BatchProgress, ignoring update");
            return;
        }

        if (batchProgress.paused()) {
            progressBar.setFraction(batchProgress.overallPercentage() / 100.0);
            progressBar.setText(I18n.tr("Paused"));
        } else if (batchProgress.indeterminate()) {
            progressBar.pulse();
            progressBar.setText(I18n.tr("Converting..."));
        } else {
            progressBar.setFraction(batchProgress.overallPercentage() / 100.0);
            progressBar.setText(I18n.tr("%d%%", batchProgress.overallPercentage()));
        }

        // Update status label with file counts
        String status = batchProgress.paused() ? I18n.tr("Conversion Paused")
                : batchProgress.indeterminate()
                        ? I18n.tr("Converting files (%d of %d finished)",
                                batchProgress.totalFiles() - batchProgress.pendingFiles() - batchProgress.inProgressFiles(),
                                batchProgress.totalFiles())
                        : I18n.tr("Converting %d of %d files (%d%% complete)",
                                batchProgress.completedFiles() + batchProgress.inProgressFiles(),
                                batchProgress.totalFiles(), batchProgress.overallPercentage());
        statusLabel.setLabel(status);

        // Preserve paused/indeterminate semantics while localizing display units.
        String timeRemaining = batchProgress.paused() ? I18n.tr("Paused")
                : batchProgress.indeterminate() || batchProgress.estimatedTimeRemaining().isZero()
                        || batchProgress.estimatedTimeRemaining().isNegative() ? I18n.tr("Unknown")
                                : I18n.duration(batchProgress.estimatedTimeRemaining());
        timeRemainingLabel.setLabel(I18n.tr("Time remaining: %s", timeRemaining));

        String speed = batchProgress.paused() ? I18n.tr("Paused")
                : batchProgress.indeterminate() ? I18n.tr("Unknown")
                        : batchProgress.bytesPerSecond() <= 0 ? I18n.tr("%.1f B/s", 0.0)
                                : I18n.speed(batchProgress.bytesPerSecond());
        conversionSpeedLabel.setLabel(I18n.tr("Speed: %s", speed));

        logger.debug("Overall progress updated: {} - {} at {}",
                status, timeRemaining, speed);
    }

    /**
     * Updates progress for an individual file.
     * 
     * <p>
     * This method tracks per-file progress for detailed monitoring
     * and logging. The individual progress is stored internally but
     * the UI displays overall batch progress via updateOverallProgress().
     * </p>
     * 
     * @param fileId   the unique file identifier
     * @param progress the conversion progress for this file
     */
    public void updateFileProgress(String fileId, ConversionProgress progress) {
        if (fileId == null || progress == null) {
            logger.warn("Received invalid file progress update: fileId={}, progress={}",
                    fileId, progress);
            return;
        }

        // Store file progress for tracking
        fileProgressMap.put(fileId, progress);

        logger.debug("File progress updated: {} - {}% at {}",
                fileId, progress.percentage(), progress.formatSpeed());
    }

    /**
     * Clears all tracked file progress.
     *
     * <p>
     * Should be called when starting a new batch conversion
     * or when clearing the file list.
     * </p>
     */
    public void clearFileProgress() {
        fileProgressMap.clear();
        logger.debug("File progress tracking cleared");
    }
}
