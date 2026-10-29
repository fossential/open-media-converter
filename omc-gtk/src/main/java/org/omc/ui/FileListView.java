package org.omc.ui;

import org.omc.model.ConversionFile;
import org.omc.model.ConversionStatus;
import org.omc.model.FileListSortState;
import org.omc.model.FileListSortState.SortDirection;
import org.omc.model.FileListSortState.SortField;
import java.util.BitSet;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.foreign.MemorySegment;

/**
 * Custom component for displaying and managing the file list using GTK
 * ColumnView.
 * 
 * <p>
 * This component uses GTK 4's ColumnView to display
 * conversion files with their metadata, status, and progress.
 * </p>
 * 
 * <p>
 * Requirements: REQ-002.2 - File list management with status tracking
 * </p>
 */
public class FileListView {

    private static final Logger logger = LoggerFactory.getLogger(FileListView.class);

    private final ColumnView columnView;
    private final StringList stringListModel;
    private final org.gnome.gtk.SortListModel sortListModel;
    private final MultiSelection<?> selectionModel;
    private final Map<String, Integer> fileIdToIndexMap;
    private final List<ConversionFile> files;
    private final org.omc.controller.ApplicationWorkflowController controller;
    private DoubleClickListener doubleClickListener;
    private FileListSortState currentSortState;
    private SortChangeListener sortChangeListener;
    private ColumnViewColumn nameColumn, sizeColumn, formatColumn, outputFormatColumn;

    // Track the last sorted column and direction for 3-click cycle (asc -> desc ->
    // clear)
    // Maps column to the last direction it was sorted in
    private final Map<ColumnViewColumn, SortDirection> columnSortHistory;

    // Store original sorters so we can temporarily remove and restore them during
    // clearSort()
    private final Map<ColumnViewColumn, Sorter> columnSorters;

    // Cache for progress column widgets to enable direct updates without row rebind
    // Maps fileId -> ProgressWidgets (progressBar, statusLabel, progressLabel)
    private final Map<String, ProgressWidgets> progressWidgetCache;

    // Snapshot of the controller's ConversionSettings for output-format
    // resolution. Refreshed in setFiles()/refreshSettingsCache() instead of
    // querying the controller inside every onBind, which made scrolling O(n)
    // in controller calls.
    private volatile org.omc.model.ConversionSettings cachedSettings;

    // Receiver used for override-only resolution when the settings snapshot is
    // unavailable (controller failure); the override branch never reads
    // global sections, so an empty settings instance resolves identically.
    private static final org.omc.model.ConversionSettings EMPTY_SETTINGS =
            org.omc.model.ConversionSettings.builder().build();

    /**
     * Container for cached progress column widgets to enable direct updates.
     * 
     * <p>
     * This allows updating progress bars without triggering full row redraws,
     * eliminating flickering during progress updates.
     * </p>
     */
    private static class ProgressWidgets {
        final ProgressBar progressBar;
        final Label statusLabel;
        final Label progressLabel;

        ProgressWidgets(ProgressBar progressBar, Label statusLabel, Label progressLabel) {
            this.progressBar = progressBar;
            this.statusLabel = statusLabel;
            this.progressLabel = progressLabel;
        }
    }

    /**
     * Interface for handling double-click events on files.
     * 
     * <p>
     * Requirement REQ-FL-2.1: Double-click to view conversion details
     * </p>
     * <p>
     * Task 52: Define DoubleClickListener interface
     * </p>
     */
    public interface DoubleClickListener {
        /**
         * Called when a file is double-clicked in the list.
         * 
         * @param fileId the ID of the double-clicked file
         */
        void onFileDoubleClicked(String fileId);
    }

    /**
     * Interface for handling sort state changes.
     * 
     * <p>
     * Requirement REQ-FL-4.5: Sort state persistence
     * </p>
     * <p>
     * Task 77: Define SortChangeListener interface
     * </p>
     */
    public interface SortChangeListener {
        /**
         * Called when the file list sort state changes.
         * 
         * @param sortState the new sort state
         */
        void onSortChanged(FileListSortState sortState);
    }

    /**
     * Constructs the file list view component and sets up the GTK model.
     * 
     * <p>
     * Requirement REQ-FL-1.1: Output format column requires access to controller
     * </p>
     * <p>
     * Task 37: Pass ApplicationWorkflowController reference to FileListView
     * </p>
     * 
     * @param columnView the GTK ColumnView widget
     * @param controller the application workflow controller for accessing settings
     */
    public FileListView(ColumnView columnView, org.omc.controller.ApplicationWorkflowController controller) {
        this.columnView = columnView;
        this.controller = controller;
        this.files = new ArrayList<>();
        this.fileIdToIndexMap = new HashMap<>();
        this.progressWidgetCache = new HashMap<>();

        // Initialize sort state to unsorted (Task 77: REQ-FL-4.5)
        this.currentSortState = FileListSortState.unsorted();
        this.columnSortHistory = new HashMap<>();
        this.columnSorters = new HashMap<>();

        // Create StringList model for simple string-based display
        // We'll store file IDs in the model and look up actual data from our files list
        this.stringListModel = new StringList(new String[0]);

        // Wrap StringList in a SortListModel for sorting support (Bug 5 fix)
        // Note: We set the sorter after columns are configured in setupColumns()
        this.sortListModel = new org.gnome.gtk.SortListModel(stringListModel, null);

        // Create selection model with multi-select support
        this.selectionModel = new MultiSelection<>(sortListModel);

        // Set the model on the ColumnView
        columnView.setModel(selectionModel);

        // Apply custom CSS for progress bar styling
        applyCssStyles();

        // Set up column renderers
        setupColumns();

        // Set up double-click gesture (Task 53)
        setupDoubleClickGesture();

        // Set up sort change detection (Task 78: REQ-FL-4.5)
        setupSortChangeDetection();

        logger.debug("FileListView initialized with GTK model");
    }

    /**
     * Applies custom CSS styles for the file list view.
     * Sets minimum height for progress bars to fill the row height.
     */
    @SuppressWarnings("deprecation")
    private void applyCssStyles() {
        // Create CSS provider for custom styles
        var cssProvider = new org.gnome.gtk.CssProvider();

        // Define CSS to make progress bars fill the available height and style overlay
        // text
        String css = """
                .file-list-progress {
                    min-height: 24px;
                }
                .file-list-progress trough {
                    min-height: 24px;
                }
                .file-list-progress progress {
                    min-height: 24px;
                }
                .progress-text-overlay {
                    font-size: 11px;
                    font-weight: bold;
                    color: rgba(0, 0, 0, 0.9);
                    text-shadow: 0 0 2px rgba(255, 255, 255, 0.8);
                }
                """;

        cssProvider.loadFromString(css);

        // Add CSS provider to the default display
        // Priority: GTK_STYLE_PROVIDER_PRIORITY_APPLICATION = 600
        var display = org.gnome.gdk.Display.getDefault();
        if (display != null) {
            org.gnome.gtk.StyleContext.addProviderForDisplay(
                    display,
                    cssProvider,
                    600 // GTK_STYLE_PROVIDER_PRIORITY_APPLICATION
            );
            logger.debug("Applied custom CSS styles for progress bars");
        } else {
            logger.warn("Could not get default display for CSS styling");
        }
    }

    /**
     * Sets up column renderers for the ColumnView.
     * 
     * <p>
     * Requirement REQ-002.2: Columns for Name, Size, Format, Output Format, Status,
     * Progress
     * </p>
     * <p>
     * Requirement REQ-FL-1.1: Output Format column display
     * </p>
     */
    private void setupColumns() {
        // Get column references from the ColumnView (defined in UI XML)
        var columns = columnView.getColumns();

        // Column indices:
        // 0: Name
        // 1: Size
        // 2: Format
        // 3: Output Format (NEW - REQ-FL-1.1)
        // 4: Status (shifted from 3)
        // 5: Progress (shifted from 4)
        if (columns.getNItems() >= 6) {
            nameColumn = (ColumnViewColumn) columns.getItem(0);
            sizeColumn = (ColumnViewColumn) columns.getItem(1);
            formatColumn = (ColumnViewColumn) columns.getItem(2);
            outputFormatColumn = (ColumnViewColumn) columns.getItem(3);
            var statusColumn = (ColumnViewColumn) columns.getItem(4);
            var progressColumn = (ColumnViewColumn) columns.getItem(5);

            setupNameColumn(nameColumn);
            setupNameSorting(nameColumn);
            setupSizeColumn(sizeColumn);
            setupSizeSorting(sizeColumn);
            setupFormatColumn(formatColumn);
            setupFormatSorting(formatColumn);
            setupOutputFormatColumn(outputFormatColumn);
            setupOutputFormatSorting(outputFormatColumn);
            setupStatusColumn(statusColumn);
            setupProgressColumn(progressColumn);

            // Connect ColumnView's sorter to SortListModel (Bug 5 fix)
            // GTK 4 automatically creates a ColumnViewSorter when columns have sorters
            var columnViewSorter = columnView.getSorter();
            if (columnViewSorter != null) {
                sortListModel.setSorter(columnViewSorter);
                logger.debug("Connected ColumnView sorter to SortListModel");
            } else {
                logger.warn("ColumnView sorter is null - sorting may not work");
            }
        } else {
            logger.warn("Expected 6 columns in ColumnView, found: {}", columns.getNItems());
        }
    }

    /**
     * Sets up the Name column renderer.
     * 
     * <p>
     * Requirement REQ-FL-1.2: Name column shows only filename without badge icon
     * </p>
     */
    private void setupNameColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;

            // Create simple Label for filename (badge icon removed per REQ-FL-1.2)
            var label = new Label("");
            label.setXalign(0.0f); // Left-align

            listItem.setChild(label);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var label = (Label) listItem.getChild();

            if (stringObject != null && label != null) {
                // Get file ID from string model
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);

                if (file != null) {
                    // Display filename only (preset info now shown in Output Format column)
                    label.setLabel(file.fileName());
                }
            }
        });

        column.setFactory(factory);
    }

    /**
     * Sets up the Size column renderer.
     */
    private void setupSizeColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;
            var label = new Label("");
            label.setXalign(1.0f); // Right-align
            listItem.setChild(label);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var label = (Label) listItem.getChild();

            if (stringObject != null) {
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);
                if (file != null) {
                    label.setLabel(formatFileSize(file.size()));
                }
            }
        });

        column.setFactory(factory);
    }

    /**
     * Sets up the Format column renderer.
     */
    private void setupFormatColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;
            var label = new Label("");
            label.setXalign(0.0f);
            listItem.setChild(label);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var label = (Label) listItem.getChild();

            if (stringObject != null) {
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);
                if (file != null) {
                    label.setLabel(file.format().name());
                }
            }
        });

        column.setFactory(factory);
    }

    /**
     * Sets up the Output Format column renderer.
     * 
     * <p>
     * Requirement REQ-FL-1.1: Display output format or preset name
     * </p>
     * <p>
     * Task 33: Implement Output Format column rendering
     * </p>
     */
    private void setupOutputFormatColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;
            var label = new Label("");
            label.setXalign(0.0f); // Left-align
            listItem.setChild(label);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var label = (Label) listItem.getChild();

            if (stringObject != null) {
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);
                if (file != null) {
                    // Resolve output format display string (Task 34)
                    String outputFormat = resolveOutputFormat(file);
                    label.setLabel(outputFormat);
                }
            }
        });

        column.setFactory(factory);
    }

    /**
     * Sets up the Status column renderer.
     */
    private void setupStatusColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;
            var label = new Label("");
            label.setXalign(0.0f);
            listItem.setChild(label);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var label = (Label) listItem.getChild();

            if (stringObject != null) {
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);
                if (file != null) {
                    label.setLabel(formatStatus(file.status()));

                    // Cache status label for direct updates (eliminates flickering)
                    ProgressWidgets widgets = progressWidgetCache.get(fileId);
                    if (widgets != null) {
                        // Update existing cache entry with new status label reference
                        progressWidgetCache.put(fileId, new ProgressWidgets(
                                widgets.progressBar, label, widgets.progressLabel));
                    } else {
                        // Create partial cache entry (progress bar will be added later)
                        progressWidgetCache.put(fileId, new ProgressWidgets(null, label, null));
                    }
                }
            }
        });

        factory.onUnbind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();

            if (stringObject != null) {
                // Release only if the cache still points at THESE widgets:
                // recycled rows re-bind to other files, and blindly keeping
                // (or clearing) the entry would corrupt another file's updates.
                releaseStatusLabel(stringObject.getString(), (Label) listItem.getChild());
            }
        });

        column.setFactory(factory);
    }

    /**
     * Releases a cached status label on row unbind when it is still the cached
     * instance. Nulls the slot instead of dropping the whole entry so a live
     * progress-bar reference from the sibling column survives; drops the key
     * once no widget references remain.
     */
    private void releaseStatusLabel(String fileId, Label label) {
        ProgressWidgets widgets = progressWidgetCache.get(fileId);
        if (widgets != null && widgets.statusLabel == label) {
            if (widgets.progressBar == null && widgets.progressLabel == null) {
                progressWidgetCache.remove(fileId);
            } else {
                progressWidgetCache.put(fileId,
                        new ProgressWidgets(widgets.progressBar, null, widgets.progressLabel));
            }
        }
    }

    /**
     * Releases cached progress widgets on row unbind under the same
     * identity-checked rules as {@link #releaseStatusLabel}.
     */
    private void releaseProgressWidgets(String fileId, ProgressBar progressBar, Label progressLabel) {
        ProgressWidgets widgets = progressWidgetCache.get(fileId);
        if (widgets != null && widgets.progressBar == progressBar && widgets.progressLabel == progressLabel) {
            if (widgets.statusLabel == null) {
                progressWidgetCache.remove(fileId);
            } else {
                progressWidgetCache.put(fileId,
                        new ProgressWidgets(null, widgets.statusLabel, null));
            }
        }
    }

    /**
     * Sets up the Progress column renderer with a progress bar.
     */
    private void setupProgressColumn(ColumnViewColumn column) {
        var factory = new SignalListItemFactory();

        factory.onSetup(item -> {
            var listItem = (ListItem) item;

            // Create an Overlay to stack the progress bar and text label
            var overlay = new Overlay();

            // Create progress bar with proper styling for height
            var progressBar = new ProgressBar();
            progressBar.setShowText(false); // We'll use our own label overlay

            // Set vertical alignment to fill and expand to use available height
            progressBar.setValign(org.gnome.gtk.Align.FILL);
            progressBar.setVexpand(true);

            // Add CSS class for custom styling
            progressBar.addCssClass("file-list-progress");

            // Create label for progress text, overlaid on top of progress bar
            var label = new Label("");
            label.setXalign(0.5f); // Center horizontally
            label.setYalign(0.5f); // Center vertically
            label.addCssClass("progress-text-overlay");

            // Add progress bar as base and label as overlay
            overlay.setChild(progressBar);
            overlay.addOverlay(label);

            listItem.setChild(overlay);
        });

        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var overlay = (Overlay) listItem.getChild();

            if (stringObject != null && overlay != null) {
                String fileId = stringObject.getString();
                ConversionFile file = findFileById(fileId);
                if (file != null) {
                    // Get the progress bar (first child of overlay)
                    var progressBar = (ProgressBar) overlay.getChild();

                    // Get the label overlay (first overlay child)
                    var progressLabel = (Label) overlay.getFirstChild().getNextSibling();

                    renderProgress(file, progressBar, progressLabel);

                    // Cache progress widgets for direct updates (eliminates flickering)
                    ProgressWidgets widgets = progressWidgetCache.get(fileId);
                    if (widgets != null && widgets.statusLabel != null) {
                        // Update existing cache entry with progress bar and label references
                        progressWidgetCache.put(fileId, new ProgressWidgets(
                                progressBar, widgets.statusLabel, progressLabel));
                    } else {
                        // Create partial cache entry (status label will be added later)
                        progressWidgetCache.put(fileId, new ProgressWidgets(progressBar, null, progressLabel));
                    }
                }
            }
        });

        factory.onUnbind(item -> {
            var listItem = (ListItem) item;
            var stringObject = (org.gnome.gtk.StringObject) listItem.getItem();
            var overlay = (Overlay) listItem.getChild();

            if (stringObject != null && overlay != null) {
                var progressBar = (ProgressBar) overlay.getChild();
                var progressLabel = (Label) overlay.getFirstChild().getNextSibling();
                releaseProgressWidgets(stringObject.getString(), progressBar, progressLabel);
            }
        });

        column.setFactory(factory);
    }

    /**
     * Sets the list of files to display.
     * 
     * <p>
     * Requirement REQ-002.2: Display file list with metadata
     * </p>
     * 
     * @param files the files to display
     */
    public void setFiles(List<ConversionFile> files) {
        this.files.clear();
        this.fileIdToIndexMap.clear();
        this.progressWidgetCache.clear(); // Clear widget cache when file list is reset

        // Rebuild the settings snapshot so output-format binds resolve against
        // current settings without a controller call per row.
        refreshSettingsCache();

        // Clear the string list model
        stringListModel.splice(0, stringListModel.getNItems(), new String[0]);

        if (files != null) {
            this.files.addAll(files);

            // Build array of file IDs for the model
            String[] fileIds = new String[files.size()];
            for (int i = 0; i < files.size(); i++) {
                ConversionFile file = files.get(i);
                fileIds[i] = file.id();
                fileIdToIndexMap.put(file.id(), i);
            }

            // Add all file IDs to the model
            stringListModel.splice(0, 0, fileIds);
        }

        logger.debug("File list updated with {} files", this.files.size());
    }

    /**
     * Updates a single file in the list.
     * 
     * <p>
     * Requirement REQ-004.3: Real-time progress updates
     * </p>
     * 
     * @param fileId the file ID
     * @param file   the updated file information
     */
    public void updateFile(String fileId, ConversionFile file) {
        Integer index = fileIdToIndexMap.get(fileId);
        if (index != null && index < files.size()) {
            files.set(index, file);

            // Use direct widget updates to avoid flickering
            // Instead of splice() which destroys and recreates widgets, we update them
            // directly
            ProgressWidgets widgets = progressWidgetCache.get(fileId);
            if (widgets != null) {
                renderProgress(file, widgets.progressBar, widgets.progressLabel);

                // Update status label directly if available
                if (widgets.statusLabel != null) {
                    widgets.statusLabel.setLabel(formatStatus(file.status()));
                }

                logger.debug("File updated (direct widget update): {} at index {} - status={}, progress={}%",
                        fileId, index, file.status(), file.progress());
            } else {
                // Widgets not cached yet (row not visible) - fallback to model update
                // This will trigger a rebind when the row becomes visible
                stringListModel.splice(index, 1, new String[] { fileId });

                logger.debug("File updated (model splice fallback): {} at index {} - status={}, progress={}%",
                        fileId, index, file.status(), file.progress());
            }
        } else {
            logger.warn("File ID not found for update: {}", fileId);
        }
    }

    /**
     * Gets the list of selected file IDs.
     * 
     * <p>
     * Requirement REQ-002.2: File selection for operations
     * </p>
     * 
     * @return list of selected file IDs
     */
    public List<String> getSelectedFileIds() {
        List<String> selectedIds = new ArrayList<>();

        // Get the selection bitset from MultiSelection
        var selection = selectionModel.getSelection();

        // Selection positions refer to the selection model's own (visual/sorted)
        // order, which differs from the insertion-ordered files list whenever the
        // GTK model is sorted (e.g. after a column header click). Resolve each
        // position through the model's item at that position instead of files.get(i).
        int nItems = selectionModel.getNItems();
        for (int i = 0; i < nItems; i++) {
            if (selection.contains(i)) {
                var item = selectionModel.getItem(i);
                if (item instanceof org.gnome.gtk.StringObject stringObject
                        && stringObject.getString() != null) {
                    selectedIds.add(stringObject.getString());
                }
            }
        }

        logger.debug("Getting selected file IDs: {}", selectedIds.size());
        return selectedIds;
    }

    /**
     * Selects all files in the list.
     * 
     * <p>
     * Requirement REQ-102.2: Ctrl+A keyboard shortcut
     * </p>
     */
    public void selectAll() {
        selectionModel.selectAll();
        logger.debug("Select all triggered");
    }

    private void renderProgress(ConversionFile file, ProgressBar bar, Label label) {
        boolean paused = file.status() == ConversionStatus.IN_PROGRESS
                && file.progressInfo() != null && file.progressInfo().paused();
        boolean indeterminate = file.status() == ConversionStatus.IN_PROGRESS
                && file.progressInfo() != null && file.progressInfo().indeterminate();
        if (bar != null) {
            if (indeterminate && !paused) bar.pulse();
            else bar.setFraction(file.progress() / 100.0);
        }
        if (label != null) {
            String text = paused ? I18n.tr("Paused") : indeterminate ? I18n.tr("Converting...") : I18n.tr("%d%%", file.progress());
            if (!paused && !indeterminate && file.progressInfo() != null
                    && !file.progressInfo().indeterminate() && !file.progressInfo().paused()
                    && (file.status() == ConversionStatus.IN_PROGRESS || file.status() == ConversionStatus.COMPLETED)) {
                text = I18n.tr("%s at %s", text, I18n.speed(file.progressInfo().bytesPerSecond()));
            }
            label.setLabel(text);
        }
    }

    /** Finds the queued file by its stable identifier. */
    private ConversionFile findFileById(String fileId) {
        Integer index = fileIdToIndexMap.get(fileId);
        if (index != null && index < files.size()) {
            return files.get(index);
        }
        return null;
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     * 
     * @param bytes the size in bytes
     * @return formatted size string (e.g., "1.5 MB")
     */
    private String formatFileSize(long bytes) {
        return I18n.bytes(bytes);
    }

    /**
     * Formats a conversion status to a user-friendly string.
     * 
     * @param status the conversion status
     * @return formatted status string
     */
    private String formatStatus(ConversionStatus status) {
        return switch (status) {
            case PENDING -> I18n.tr("Pending");
            case IN_PROGRESS -> I18n.tr("Converting...");
            case COMPLETED -> I18n.tr("Completed");
            case FAILED -> I18n.tr("Failed");
            case CANCELLED -> I18n.tr("Cancelled");
        };
    }

    /**
     * Resolves the output format display string for a file.
     * 
     * <p>
     * Requirement REQ-FL-1.1: Output format resolution logic
     * </p>
     * <p>
     * Task 34: Implement resolveOutputFormat() method
     * </p>
     * 
     * @param file the conversion file
     * @return the output format string (preset name, format name, or "Not Set")
     */
    private String resolveOutputFormat(org.omc.model.ConversionFile file) {
        // Check for custom settings override
        if (file.hasCustomSettings()) {
            org.omc.model.FileSettingsOverride override = file.settingsOverride();

            // Prefer preset name if available (e.g., "High Quality", "Web Optimized")
            if (override.presetName() != null && !override.presetName().isEmpty()) {
                return override.presetName();
            }

            // Otherwise resolve the format from the override section (own
            // category only; never falls through to global settings)
            org.omc.model.ConversionSettings resolver =
                    cachedSettings != null ? cachedSettings : EMPTY_SETTINGS;
            org.omc.model.FileFormat format = resolver.resolveOutputFormat(file);
            return format != null ? format.name() : I18n.tr("Not Set");
        }

        // Use global settings for file category
        return resolveFormatFromGlobalSettings(file);
    }

    /**
     * Refreshes the cached ConversionSettings snapshot from the controller.
     *
     * <p>
     * Called from {@link #setFiles(List)} and by the main window whenever
     * settings-changing dialogs commit, so per-row binds never query the
     * controller directly (the old per-bind lookup made scrolling O(n) in
     * controller calls).
     * </p>
     */
    public void refreshSettingsCache() {
        try {
            this.cachedSettings = controller.getCurrentSettings();
        } catch (RuntimeException e) {
            logger.warn("Failed to cache conversion settings for output-format column", e);
            this.cachedSettings = null;
        }
    }

        /**
     * Resolves output format from global ConversionSettings for the file's
     * category.
     * 
     * <p>
     * Requirement REQ-FL-1.1: Extract format from global settings
     * </p>
     * <p>
     * Task 36: Add helper method for global settings format resolution
     * </p>
     * 
     * @param file the conversion file
     * @return the format name or "Not Set"
     */
    private String resolveFormatFromGlobalSettings(org.omc.model.ConversionFile file) {
        if (cachedSettings == null) {
            return I18n.tr("Not Set");
        }
        try {
            org.omc.model.FileFormat outputFormat =
                    cachedSettings.resolveOutputFormat(file.format().getCategory());
            return outputFormat != null ? outputFormat.name() : I18n.tr("Not Set");
        } catch (RuntimeException e) {
            logger.warn("Failed to resolve output format from global settings", e);
            return I18n.tr("Not Set");
        }
    }

    /**
     * Sets up double-click gesture for opening file details dialog.
     * 
     * <p>
     * Requirement REQ-FL-2.1: Double-click to view conversion details
     * </p>
     * <p>
     * Task 51: Add setupDoubleClickGesture() method
     * </p>
     * <p>
     * Bug 4 fix: Use onReleased instead of onPressed for more reliable double-click
     * detection
     * </p>
     */
    private void setupDoubleClickGesture() {
        // Create GestureClick for detecting double-clicks
        var gesture = new GestureClick();
        gesture.setButton(1); // Left mouse button only
        gesture.setPropagationPhase(PropagationPhase.CAPTURE);

        // Register onReleased handler to detect double-click
        // Using onReleased is more reliable than onPressed for double-click detection
        gesture.onReleased((nPress, x, y) -> {
            if (nPress == 2) { // Double-click detected
                handleDoubleClick();
            }
        });

        // Add gesture controller to ColumnView
        columnView.addController(gesture);

        logger.debug("Double-click gesture setup complete");
    }

    /**
     * Sets up detection of sort changes on sortable columns.
     * 
     * <p>
     * Listens to the ColumnView's main sorter (ColumnViewSorter) to detect when
     * users click on column headers to sort. When a sort change is detected, we
     * determine which column is active and its direction, then notify the listener.
     * </p>
     * 
     * <p>
     * Requirement REQ-FL-4.5: Sort state persistence
     * </p>
     * <p>
     * Task 78: Detect sort changes in FileListView
     * </p>
     */
    private void setupSortChangeDetection() {
        // Get the ColumnView's main sorter (automatically created by GTK when columns
        // have sorters)
        var columnViewSorter = columnView.getSorter();

        if (columnViewSorter == null) {
            logger.warn("ColumnView sorter is null - sort change detection disabled");
            return;
        }

        // Listen to the main sorter's changed signal
        // This fires when the user clicks any column header to sort
        columnViewSorter.onChanged((change) -> {
            detectAndNotifySortChange();
        });

        logger.debug("Sort change detection setup complete (listening to ColumnView sorter)");
    }

    /**
     * Detects the current sort state by examining which column sorter is active,
     * and notifies the listener if the state has changed.
     * 
     * <p>
     * Implements 3-click cycle: ascending -> descending -> clear (unsorted)
     * </p>
     * <p>
     * Task 78: Determine active column and direction from ColumnView sorter
     * </p>
     */
    private void detectAndNotifySortChange() {
        // Get the ColumnView's sorter (should be a ColumnViewSorter)
        var sorter = columnView.getSorter();
        if (!(sorter instanceof ColumnViewSorter columnViewSorter)) {
            logger.warn("ColumnView sorter is not a ColumnViewSorter - cannot detect sort changes");
            return;
        }

        // Get the primary (currently active) sort column and direction
        var primaryColumn = columnViewSorter.getPrimarySortColumn();
        var primaryOrder = columnViewSorter.getPrimarySortOrder();

        if (primaryColumn == null) {
            logger.debug("No column is currently sorted");
            // Clear sort tracking history
            columnSortHistory.clear();
            return;
        }

        // Map the active column to our SortField enum
        SortField activeSortField = null;
        if (primaryColumn.equals(nameColumn)) {
            activeSortField = SortField.NAME;
        } else if (primaryColumn.equals(sizeColumn)) {
            activeSortField = SortField.SIZE;
        } else if (primaryColumn.equals(formatColumn)) {
            activeSortField = SortField.FORMAT;
        } else if (primaryColumn.equals(outputFormatColumn)) {
            activeSortField = SortField.OUTPUT_FORMAT;
        }

        if (activeSortField == null) {
            logger.debug("Active sort column is not a recognized sortable column");
            return;
        }

        // Convert GTK SortType to our SortDirection
        SortDirection sortDirection = primaryOrder == SortType.DESCENDING
                ? SortDirection.DESCENDING
                : SortDirection.ASCENDING;

        // Implement 3-click cycle: asc -> desc -> clear
        // Check if this is the same column as last time
        SortDirection lastDirection = columnSortHistory.get(primaryColumn);

        if (lastDirection != null && lastDirection == SortDirection.DESCENDING &&
                sortDirection == SortDirection.ASCENDING) {
            // User clicked on a descending column and GTK wrapped it back to ascending
            // This is the third click - we should clear the sort instead
            logger.debug("Third click detected on column {} - clearing sort", activeSortField);
            clearSort();
            return;
        }

        // Update history for this column
        columnSortHistory.put(primaryColumn, sortDirection);

        // Create new sort state
        FileListSortState newSortState = switch (activeSortField) {
            case NAME -> FileListSortState.byName(sortDirection);
            case SIZE -> FileListSortState.bySize(sortDirection);
            case FORMAT -> FileListSortState.byFormat(sortDirection);
            case OUTPUT_FORMAT -> FileListSortState.byOutputFormat(sortDirection);
        };

        // Only notify if state actually changed
        if (!newSortState.equals(currentSortState)) {
            currentSortState = newSortState;

            if (sortChangeListener != null) {
                sortChangeListener.onSortChanged(newSortState);
                logger.debug("Sort changed to: {} {}", activeSortField, sortDirection);
            }
        }
    }

    /**
     * Clears the current sort and returns the file list to unsorted (insertion
     * order) state.
     * 
     * <p>
     * This is called on the third click of a column header to implement the 3-click
     * cycle:
     * ascending -> descending -> unsorted
     * </p>
     */
    private void clearSort() {
        logger.debug("Clearing sort (third click)");

        // Reset sort tracking state
        columnSortHistory.clear();
        currentSortState = FileListSortState.unsorted();

        // Temporarily disable the listener to prevent recursion
        SortChangeListener savedListener = this.sortChangeListener;
        this.sortChangeListener = null;

        try {
            // Temporarily remove sorters from all columns to prevent GTK from re-sorting
            // This is necessary because GTK will automatically re-apply sorting when we
            // modify the model
            // Null checks for testing environments where columns may not be initialized
            if (nameColumn != null)
                nameColumn.setSorter(null);
            if (sizeColumn != null)
                sizeColumn.setSorter(null);
            if (formatColumn != null)
                formatColumn.setSorter(null);
            if (outputFormatColumn != null)
                outputFormatColumn.setSorter(null);

            // Get current files in their original insertion order
            String[] fileIds = files.stream().map(ConversionFile::id).toArray(String[]::new);

            // Clear and reload model to remove sorting and restore insertion order
            stringListModel.splice(0, stringListModel.getNItems(), new String[0]);
            stringListModel.splice(0, 0, fileIds);

            // Restore the sorters so future clicks work
            // Use a small delay to ensure GTK has processed the model changes
            org.gnome.glib.GLib.idleAdd(0, () -> {
                if (nameColumn != null)
                    nameColumn.setSorter(columnSorters.get(nameColumn));
                if (sizeColumn != null)
                    sizeColumn.setSorter(columnSorters.get(sizeColumn));
                if (formatColumn != null)
                    formatColumn.setSorter(columnSorters.get(formatColumn));
                if (outputFormatColumn != null)
                    outputFormatColumn.setSorter(columnSorters.get(outputFormatColumn));
                return false; // Don't repeat
            });

            logger.debug("Sort cleared - list restored to insertion order");
        } finally {
            // Restore listener
            this.sortChangeListener = savedListener;
        }

        // Notify listener that sort was cleared (after restoring listener reference)
        if (savedListener != null) {
            savedListener.onSortChanged(FileListSortState.unsorted());
            logger.debug("Sort cleared - notified listener with unsorted state");
        }
    }

    /**
     * Handles double-click events by notifying the listener.
     * 
     * <p>
     * Requirement REQ-FL-2.1: Double-click to view conversion details
     * </p>
     * <p>
     * Task 51: Get selected file ID and call listener
     * </p>
     */
    private void handleDoubleClick() {
        // Get selected file IDs (should be exactly one for double-click)
        List<String> selectedIds = getSelectedFileIds();

        if (selectedIds.isEmpty()) {
            logger.debug("Double-click ignored: no file selected");
            return;
        }

        // Use first selected file (double-click typically selects one item)
        String fileId = selectedIds.get(0);

        // Notify listener if registered
        if (doubleClickListener != null) {
            logger.debug("Double-click on file: {}", fileId);
            doubleClickListener.onFileDoubleClicked(fileId);
        } else {
            logger.warn("Double-click ignored: no listener registered");
        }
    }

    /**
     * Sets the double-click listener for handling file double-click events.
     * 
     * <p>
     * Requirement REQ-FL-2.1: Double-click to view conversion details
     * </p>
     * <p>
     * Task 52: Add setDoubleClickListener() method
     * </p>
     * 
     * @param listener the listener to notify on double-click, or null to remove
     */
    public void setDoubleClickListener(DoubleClickListener listener) {
        this.doubleClickListener = listener;
        logger.debug("Double-click listener {}", listener != null ? "registered" : "removed");
    }

    /**
     * Sets the sort change listener for handling column sort state changes.
     * 
     * <p>
     * Requirement REQ-FL-4.5: Sort state persistence
     * </p>
     * <p>
     * Task 77: Add sort state tracking to FileListView
     * </p>
     * 
     * @param listener the listener to notify on sort changes, or null to remove
     */
    public void setSortChangeListener(SortChangeListener listener) {
        this.sortChangeListener = listener;
        logger.debug("Sort change listener {}", listener != null ? "registered" : "removed");
    }

    /**
     * Gets the current sort state.
     * 
     * <p>
     * Requirement REQ-FL-4.5: Sort state persistence
     * </p>
     * 
     * @return the current sort state
     */
    public FileListSortState getCurrentSortState() {
        return currentSortState;
    }

    /**
     * Helper method to extract ConversionFile from a GTK item MemorySegment.
     * 
     * @param item the GTK item as MemorySegment
     * @return the corresponding ConversionFile, or null if item is null or file not
     *         found
     */
    private ConversionFile getFileFromItem(MemorySegment item) {
        if (item == null)
            return null;
        var str = new org.gnome.gtk.StringObject(item);
        return findFileById(str.getString());
    }

    /**
     * Sets up natural alphanumeric sorting for the Name column.
     * 
     * <p>
     * Requirement REQ-FL-4.1: Enable column sorting
     * </p>
     * <p>
     * Requirement REQ-FL-4.2: Natural sorting for names (case-insensitive
     * alphanumeric)
     * </p>
     * <p>
     * Task 71: Add setupNameSorting() method
     * </p>
     * 
     * @param column the Name column to enable sorting on
     */
    private void setupNameSorting(ColumnViewColumn column) {
        // Create CustomSorter with natural alphanumeric comparison
        var sorter = new CustomSorter((item1, item2) -> {
            ConversionFile file1 = getFileFromItem(item1);
            ConversionFile file2 = getFileFromItem(item2);

            if (file1 == null || file2 == null)
                return 0;

            // Compare filenames using natural sort
            return compareNatural(file1.fileName(), file2.fileName());
        });

        // Set sorter on column and store reference for clearSort()
        column.setSorter(sorter);
        columnSorters.put(column, sorter);
        logger.debug("Name column sorting enabled (natural alphanumeric)");
    }

    /**
     * Performs natural alphanumeric string comparison.
     * This ensures that "file1.mp4, file2.mp4, file10.mp4" sorts correctly
     * instead of "file1.mp4, file10.mp4, file2.mp4".
     * 
     * <p>
     * Requirement REQ-FL-4.2: Natural sorting for names
     * </p>
     * <p>
     * Task 72: Implement compareNatural() helper method
     * </p>
     * 
     * @param s1 first string to compare
     * @param s2 second string to compare
     * @return negative if s1 < s2, zero if equal, positive if s1 > s2
     */
    private int compareNatural(String s1, String s2) {
        // Split strings into sequences of digits and non-digits
        String[] parts1 = s1.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] parts2 = s2.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");

        int len = Math.min(parts1.length, parts2.length);

        for (int i = 0; i < len; i++) {
            String p1 = parts1[i];
            String p2 = parts2[i];

            // If both parts are numeric, compare as numbers
            if (p1.matches("\\d+") && p2.matches("\\d+")) {
                long n1 = Long.parseLong(p1);
                long n2 = Long.parseLong(p2);
                int cmp = Long.compare(n1, n2);
                if (cmp != 0)
                    return cmp;
            } else {
                // Otherwise, compare as strings (case-insensitive)
                int cmp = p1.compareToIgnoreCase(p2);
                if (cmp != 0)
                    return cmp;
            }
        }

        // If all compared parts are equal, longer string sorts after
        return Integer.compare(parts1.length, parts2.length);
    }

    /**
     * Sets up numeric sorting for the Size column.
     * 
     * <p>
     * Requirement REQ-FL-4.1: Enable column sorting
     * </p>
     * <p>
     * Requirement REQ-FL-4.3: Numeric sorting for size
     * </p>
     * <p>
     * Task 73: Add setupSizeSorting() method
     * </p>
     * 
     * @param column the Size column to enable sorting on
     */
    private void setupSizeSorting(ColumnViewColumn column) {
        // Create CustomSorter with numeric comparison
        var sorter = new CustomSorter((item1, item2) -> {
            // Wrap MemorySegment into StringObject instances
            if (item1 == null || item2 == null)
                return 0;

            var str1 = new org.gnome.gtk.StringObject(item1);
            var str2 = new org.gnome.gtk.StringObject(item2);

            String fileId1 = str1.getString();
            String fileId2 = str2.getString();

            // Lookup ConversionFile instances
            ConversionFile file1 = findFileById(fileId1);
            ConversionFile file2 = findFileById(fileId2);

            if (file1 == null || file2 == null)
                return 0;

            // Compare file sizes numerically
            return Long.compare(file1.size(), file2.size());
        });

        // Set sorter on column and store reference for clearSort()
        column.setSorter(sorter);
        columnSorters.put(column, sorter);
        logger.debug("Size column sorting enabled (numeric)");
    }

    /**
     * Sets up alphabetic sorting for the Format column.
     * 
     * <p>
     * Requirement REQ-FL-4.1: Enable column sorting
     * </p>
     * <p>
     * Requirement REQ-FL-4.4: Alphabetic sorting for formats
     * </p>
     * <p>
     * Task 74: Add setupFormatSorting() method
     * </p>
     * 
     * @param column the Format column to enable sorting on
     */
    private void setupFormatSorting(ColumnViewColumn column) {
        // Create CustomSorter with case-insensitive alphabetic comparison
        var sorter = new CustomSorter((item1, item2) -> {
            // Wrap MemorySegment into StringObject instances
            if (item1 == null || item2 == null)
                return 0;

            var str1 = new org.gnome.gtk.StringObject(item1);
            var str2 = new org.gnome.gtk.StringObject(item2);

            String fileId1 = str1.getString();
            String fileId2 = str2.getString();

            // Lookup ConversionFile instances
            ConversionFile file1 = findFileById(fileId1);
            ConversionFile file2 = findFileById(fileId2);

            if (file1 == null || file2 == null)
                return 0;

            // Compare format names alphabetically (case-insensitive)
            return file1.format().name().compareToIgnoreCase(file2.format().name());
        });

        // Set sorter on column and store reference for clearSort()
        column.setSorter(sorter);
        columnSorters.put(column, sorter);
        logger.debug("Format column sorting enabled (alphabetic)");
    }

    /**
     * Sets up alphabetic sorting for the Output Format column.
     * Special handling: "Not Set" and "Unknown" values sort to the end.
     * 
     * <p>
     * Requirement REQ-FL-4.1: Enable column sorting
     * </p>
     * <p>
     * Requirement REQ-FL-4.4: Alphabetic sorting for formats with special cases
     * </p>
     * <p>
     * Task 75: Add setupOutputFormatSorting() method
     * </p>
     * 
     * @param column the Output Format column to enable sorting on
     */
    private void setupOutputFormatSorting(ColumnViewColumn column) {
        // Create CustomSorter with special case handling
        var sorter = new CustomSorter((item1, item2) -> {
            // Wrap MemorySegment into StringObject instances
            if (item1 == null || item2 == null)
                return 0;

            var str1 = new org.gnome.gtk.StringObject(item1);
            var str2 = new org.gnome.gtk.StringObject(item2);

            String fileId1 = str1.getString();
            String fileId2 = str2.getString();

            // Lookup ConversionFile instances
            ConversionFile file1 = findFileById(fileId1);
            ConversionFile file2 = findFileById(fileId2);

            if (file1 == null || file2 == null)
                return 0;

            // Resolve output format for each file (reuse existing method)
            String format1 = resolveOutputFormat(file1);
            String format2 = resolveOutputFormat(file2);

            // Handle special cases: "Not Set" and "Unknown" sort to end
            boolean isSpecial1 = format1.equals(I18n.tr("Not Set")) || format1.equals(I18n.tr("Unknown"));
            boolean isSpecial2 = format2.equals(I18n.tr("Not Set")) || format2.equals(I18n.tr("Unknown"));

            if (isSpecial1 && !isSpecial2)
                return 1; // format1 sorts after format2
            if (!isSpecial1 && isSpecial2)
                return -1; // format1 sorts before format2

            // Both special or both normal: compare alphabetically (case-insensitive)
            return format1.compareToIgnoreCase(format2);
        });

        // Set sorter on column and store reference for clearSort()
        column.setSorter(sorter);
        columnSorters.put(column, sorter);
        logger.debug("Output Format column sorting enabled (alphabetic with special cases)");
    }

    /**
     * Restores the file list sort state programmatically.
     * 
     * <p>
     * Task 81: REQ-FL-4.5 - Apply saved sort state on application startup.
     * </p>
     * <p>
     * This method applies a saved sort state by programmatically triggering column
     * sorting
     * using GTK 4's
     * {@link org.gnome.gtk.ColumnView#sortByColumn(ColumnViewColumn, SortType)}
     * API.
     * </p>
     * 
     * <p>
     * <b>Implementation:</b>
     * </p>
     * <ul>
     * <li>Maps the sort field to the corresponding column</li>
     * <li>Converts the sort direction to GTK's SortType</li>
     * <li>Temporarily disables the sort change listener to prevent saving during
     * restore</li>
     * <li>Calls {@link org.gnome.gtk.ColumnView#sortByColumn} to apply the
     * sort</li>
     * <li>Restores the listener after sorting is complete</li>
     * </ul>
     * 
     * @param sortState the sort state to restore (null or unsorted state is
     *                  ignored)
     */
    public void restoreSortState(FileListSortState sortState) {
        if (sortState == null || !sortState.isSorted()) {
            logger.debug("Ignoring restore: sort state is null or unsorted");
            return;
        }

        logger.debug("Restoring sort state: {}", sortState);

        try {
            // Map SortField enum to ColumnViewColumn instance
            ColumnViewColumn targetColumn = switch (sortState.sortField()) {
                case NAME -> nameColumn;
                case SIZE -> sizeColumn;
                case FORMAT -> formatColumn;
                case OUTPUT_FORMAT -> outputFormatColumn;
            };

            if (targetColumn == null) {
                logger.warn("Cannot restore sort: target column is null for field {}", sortState.sortField());
                return;
            }

            // Get the column's sorter (verify it's configured)
            var sorter = targetColumn.getSorter();
            if (sorter == null) {
                logger.warn("Cannot restore sort: column has no sorter for field {}", sortState.sortField());
                return;
            }

            // Convert SortDirection to GTK SortType
            org.gnome.gtk.SortType gtkSortType = sortState.sortDir() == SortDirection.DESCENDING
                    ? org.gnome.gtk.SortType.DESCENDING
                    : org.gnome.gtk.SortType.ASCENDING;

            // Temporarily disable the sort change listener to prevent triggering save
            // during restore
            SortChangeListener savedListener = this.sortChangeListener;
            this.sortChangeListener = null;

            try {
                // Update internal state to match the restored state
                this.currentSortState = sortState;

                // Programmatically trigger sorting using GTK 4's sortByColumn API
                // This is the proper way to set column sorting programmatically
                columnView.sortByColumn(targetColumn, gtkSortType);

                logger.debug("Sort state restored successfully: column={}, direction={}",
                        sortState.sortField(), sortState.sortDir());
            } finally {
                // Restore the listener
                this.sortChangeListener = savedListener;
            }

        } catch (Exception e) {
            logger.error("Failed to restore sort state: {}", sortState, e);
        }
    }
}
