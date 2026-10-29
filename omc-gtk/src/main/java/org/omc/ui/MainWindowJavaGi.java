package org.omc.ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.gnome.gio.Menu;
import org.gnome.gio.MenuItem;
import org.gnome.gio.Notification;
import org.gnome.gio.ThemedIcon;
import org.gnome.gio.SimpleAction;
import org.gnome.glib.GLib;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PopoverMenu;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.omc.controller.ApplicationWorkflowController;
import org.omc.exception.FileOperationException;
import org.omc.model.BatchProgress;
import org.omc.model.ConversionFile;
import org.omc.model.ConversionProgress;
import org.omc.model.ConversionResult;
import org.omc.model.ConversionSettings;
import org.omc.model.ConversionStatus;
import org.omc.model.FileFormat;
import org.omc.model.FileListSortState;
import org.omc.model.FormatCategory;
import org.omc.model.SectionPreset;
import org.omc.model.WindowState;

/**
 * Main application window using GTK 4 via java-gi bindings.
 * Loads UI from main_window.ui and provides interface for file management and
 * conversion.
 * 
 * <p>
 * Requirements: REQ-001.1, REQ-102.1
 * </p>
 */
public class MainWindowJavaGi extends ApplicationWindow {

    private static final Logger logger = LoggerFactory.getLogger(MainWindowJavaGi.class);

    // Controller
    private final ApplicationWorkflowController controller;

    // UI Components from Builder
    private GtkBuilder builder;

    // Track window size
    private int currentWidth = 1000;
    private int currentHeight = 700;

    // Header Bar Widgets
    private Button addFilesButton;
    private Button addFolderButton;
    private Button settingsButton;
    private MenuButton menuButton;

    // File List View
    private ScrolledWindow fileListScrolledWindow;
    private ColumnView fileListColumnView;
    private FileListView fileListView;

    /** Single reused details dialog (tracks its live window per file). */
    private FileDetailsDialog fileDetailsDialog;

    // Progress View
    private Revealer progressRevealer;
    private ProgressBar overallProgressBar;
    private Label statusLabel;
    private Label timeRemainingLabel;
    private Label conversionSpeedLabel;
    private Button pauseButton;
    private Button resumeButton;
    private Button cancelButton;
    private ProgressView progressView;

    // Action Bar Widgets
    private Button removeSelectedButton;
    private Button clearAllButton;
    private Button convertButton;
    private Label fileCountLabel;
    private Label totalSizeLabel;

    // Status Bar
    private Label statusBarLabel;

    // Batch completion tracking
    // Requirement REQ-004.2: Track overall batch progress
    // Counters, the completion latch and the UI-side "conversion in progress"
    // flag live in the pure BatchUiState seam (see bottom of this class) so
    // the semantics are unit-testable headless and a rejected or double
    // start can never corrupt a running batch.
    private final BatchUiState batchState = new BatchUiState();

    // Coalescer for engine -> UI updates: keeps only the latest progress
    // runnable per file (and per "batch") and flushes them in one GLib idle
    // callback. Terminal events (status changes, completions) are submitted
    // forced and are never dropped.
    private final IdleCoalescer uiUpdateCoalescer = new IdleCoalescer();

    // Context menu state (GTK thread only)
    // The selection a visible context menu acts on. Single-registration
    // actions below read this instead of capturing per-popup lists, so
    // right-clicks never accumulate actions or stale closures.
    private List<String> contextMenuSelection = List.of();
    // Preset action names registered for the current menu; stale entries
    // (deleted/renamed presets) are removed before each popup.
    private final java.util.Set<String> registeredPresetActionNames = new java.util.HashSet<>();
    // Single reused context menu popover, parented to the file list once.
    // Creating a new PopoverMenu per right-click leaked a parented widget
    // each time; only the menu model is rebuilt per invocation.
    private PopoverMenu contextMenuPopover;

    // Shutdown tracking
    // Prevents multiple confirmation dialogs during shutdown
    private volatile boolean shutdownInProgress = false;
    private final java.util.concurrent.ExecutorService fileAdmission =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "file-admission");
                worker.setDaemon(true);
                return worker;
            });

    /**
     * Constructs the main window and loads UI from XML.
     * 
     * @param app        the GTK application instance
     * @param controller the application workflow controller
     */
    public MainWindowJavaGi(Application app, ApplicationWorkflowController controller) {
        super(app);
        this.controller = controller;

        logger.info("Initializing MainWindowJavaGi");

        loadUI();
        setupWidgetReferences();
        setupComponents();
        setupWindowActions();
        connectSignals();
        setupWindowIcon();

        logger.info("MainWindowJavaGi initialization complete");
    }

    /**
     * Loads the UI definition from main_window.ui using GtkBuilder.
     */
    private void loadUI() {
        try {
            logger.debug("Loading UI from main_window.ui");

            // Load UI file from classpath as string (try-with-resources closes the stream)
            String uiXml;
            try (InputStream inputStream = getClass().getResourceAsStream("/ui/main_window.ui")) {
                if (inputStream == null) {
                    throw new RuntimeException("Could not find /ui/main_window.ui in classpath");
                }
                uiXml = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining("\n"));
            }

            // Create builder and load from string
            uiXml = I18n.translateUi(uiXml);
            builder = new GtkBuilder();
            builder.addFromString(uiXml, SettingsDialogJavaGi.uiByteLength(uiXml));

            // Get the main window from builder and copy its properties
            ApplicationWindow window = (ApplicationWindow) builder.getObject("mainWindow");

            // Copy window properties - use default size directly since GTK 4 doesn't expose
            // getDefaultWidth
            setTitle(window.getTitle());
            setDefaultSize(1000, 700); // Use hardcoded default matching UI file

            // Get the main content and set it as our child
            Widget content = window.getChild();
            if (content != null) {
                // Remove from old parent and add to this window
                window.setChild(null);
                setChild(content);
            }

            // Get the header bar and set it as our titlebar
            Widget titlebar = window.getTitlebar();
            if (titlebar != null) {
                window.setTitlebar(null);
                setTitlebar(titlebar);
            }

            // Destroy the stripped builder window: its child and titlebar have
            // been reparented into this window, so the empty shell must not
            // linger for the whole session.
            window.destroy();

            logger.debug("UI loaded successfully");

        } catch (Exception e) {
            logger.error("Failed to load UI from main_window.ui", e);

            // Fallback: Create minimal UI programmatically
            setTitle("Open Media Converter");
            setDefaultSize(1000, 700);

            Box box = new Box(Orientation.VERTICAL, 0);
            Label errorLabel = new Label(I18n.tr("Failed to load UI. Please check logs."));
            box.append(errorLabel);
            setChild(box);
        }
    }

    /**
     * Gets references to all widgets from the builder.
     */
    private void setupWidgetReferences() {
        try {
            // Header bar widgets
            addFilesButton = (Button) builder.getObject("addFilesButton");
            addFolderButton = (Button) builder.getObject("addFolderButton");
            settingsButton = (Button) builder.getObject("settingsButton");
            menuButton = (MenuButton) builder.getObject("menuButton");

            // File list view
            fileListScrolledWindow = (ScrolledWindow) builder.getObject("fileListScrolledWindow");
            fileListColumnView = (ColumnView) builder.getObject("fileListColumnView");

            // Progress view widgets
            progressRevealer = (Revealer) builder.getObject("progressRevealer");
            overallProgressBar = (ProgressBar) builder.getObject("overallProgressBar");
            statusLabel = (Label) builder.getObject("statusLabel");
            timeRemainingLabel = (Label) builder.getObject("timeRemainingLabel");
            conversionSpeedLabel = (Label) builder.getObject("conversionSpeedLabel");
            pauseButton = (Button) builder.getObject("pauseButton");
            resumeButton = (Button) builder.getObject("resumeButton");
            cancelButton = (Button) builder.getObject("cancelButton");

            // Action bar widgets
            removeSelectedButton = (Button) builder.getObject("removeSelectedButton");
            clearAllButton = (Button) builder.getObject("clearAllButton");
            convertButton = (Button) builder.getObject("convertButton");
            fileCountLabel = (Label) builder.getObject("fileCountLabel");
            totalSizeLabel = (Label) builder.getObject("totalSizeLabel");

            // Status bar
            statusBarLabel = (Label) builder.getObject("statusBarLabel");

            // Release the builder: every widget reference has been looked up,
            // so retaining it only keeps the whole object graph alive.
            builder = null;

            logger.debug("Widget references obtained successfully");

        } catch (Exception e) {
            logger.error("Failed to get widget references from builder", e);
            throw new RuntimeException("Failed to initialize UI components", e);
        }
    }

    /**
     * Sets up custom components with business logic integration.
     */
    private void setupComponents() {
        // Initialize FileListView component (Task 37: pass controller for output format
        // resolution)
        fileListView = new FileListView(fileListColumnView, controller);

        // Setup double-click listener (Task 54: Implement double-click handler)
        // Requirement REQ-FL-2.1: Double-click opens file details dialog
        fileListView.setDoubleClickListener(this::showFileDetailsDialog);

        // Setup sort change listener (Task 79: REQ-FL-4.5)
        // Save sort state when user changes column sorting
        fileListView.setSortChangeListener(sortState -> {
            logger.debug("Sort state changed: {}", sortState);
            controller.saveSortState(sortState);
        });

        // NOTE: Sort state restoration moved to restoreSortStateFromController()
        // which must be called AFTER controller.initialize() to ensure state is loaded

        // Initialize ProgressView component
        progressView = new ProgressView(
                progressRevealer,
                overallProgressBar,
                statusLabel,
                timeRemainingLabel,
                conversionSpeedLabel);

        // Initially hide progress view
        progressView.hide();

        // Initially disable progress control buttons (no conversion in progress)
        pauseButton.setSensitive(false);
        resumeButton.setSensitive(false);
        cancelButton.setSensitive(false);

        // Setup context menu for file list
        // Requirement REQ-3.3: Right-click context menu for preset application
        setupFileListContextMenu();

        logger.debug("Custom components initialized");
    }

    /**
     * Sets up window-level actions and keyboard shortcuts.
     * These are context-specific actions that apply only to this window.
     * 
     * Requirements: REQ-102.2 - Keyboard shortcuts (Delete and Ctrl+A)
     */
    private void setupWindowActions() {
        logger.debug("Setting up window-level actions");

        // Remove Selected action (Delete key)
        // Requirement REQ-102.2: Delete key removes selected items
        org.gnome.gio.SimpleAction removeAction = new org.gnome.gio.SimpleAction("remove-selected", null);
        removeAction.onActivate(param -> {
            logger.debug("Remove Selected action activated");
            triggerRemoveSelected();
        });
        addAction(removeAction);

        // Select All action (Ctrl+A)
        // Requirement REQ-102.2: Ctrl+A selects all items
        org.gnome.gio.SimpleAction selectAllAction = new org.gnome.gio.SimpleAction("select-all", null);
        selectAllAction.onActivate(param -> {
            logger.debug("Select All action activated");
            triggerSelectAll();
        });
        addAction(selectAllAction);

        // Context menu actions are registered ONCE here and read
        // contextMenuSelection at activation time. Previously every
        // right-click re-registered them (plus one action per preset with a
        // unique hex name), leaking actions and stale selection closures.
        org.gnome.gio.SimpleAction clearAction = new org.gnome.gio.SimpleAction("clear-preset", null);
        clearAction.onActivate(param -> {
            logger.debug("Clearing custom settings from {} files", contextMenuSelection.size());
            clearCustomSettingsFromFiles(contextMenuSelection);
        });
        addAction(clearAction);

        org.gnome.gio.SimpleAction openLocationAction = new org.gnome.gio.SimpleAction("open-file-location", null);
        openLocationAction.onActivate(param -> {
            if (contextMenuSelection.size() == 1) {
                logger.debug("Opening file location for file: {}", contextMenuSelection.get(0));
                handleOpenFileLocation(contextMenuSelection.get(0));
            }
        });
        addAction(openLocationAction);

        // Register accelerators with the application
        // Note: Window actions use "win." prefix
        Application app = getApplication();
        if (app != null) {
            app.setAccelsForAction("win.remove-selected", new String[] { "Delete" });
            app.setAccelsForAction("win.select-all", new String[] { "<Primary>a" });
            logger.debug("Registered window action accelerators: Delete and Ctrl+A");
        } else {
            logger.warn("Application not available, cannot register accelerators");
        }
    }

    /**
     * Sets up the window icon for desktop integration.
     * Loads the application icon from resources.
     * 
     * Requirements: REQ-102.1 - Application icon for desktop integration
     */
    private void setupWindowIcon() {
        try {
            // Set the icon name - GTK will look for this in the icon theme paths
            // Our icons are in src/main/resources/icons/hicolor/
            setIconName("open-media-converter");
            logger.debug("Window icon set to 'open-media-converter'");
        } catch (Exception e) {
            logger.warn("Failed to set window icon", e);
        }
    }

    /**
     * Connects button signals to controller methods.
     * 
     * <p>
     * Requirements: REQ-001.1, REQ-001.2, REQ-002.1, REQ-002.2, REQ-004.2
     * </p>
     */
    private void connectSignals() {
        logger.debug("Connecting UI signals to controller");

        // Header bar buttons
        addFilesButton.onClicked(() -> handleAddFiles());
        addFolderButton.onClicked(() -> handleAddFolder());
        settingsButton.onClicked(() -> handleSettings());

        // Action bar buttons
        removeSelectedButton.onClicked(() -> handleRemoveSelected());
        clearAllButton.onClicked(() -> handleClearAll());
        convertButton.onClicked(() -> handleConvert());

        // Progress control buttons
        pauseButton.onClicked(() -> handlePause());
        resumeButton.onClicked(() -> handleResume());
        cancelButton.onClicked(() -> handleCancel());

        // Window close event
        onCloseRequest(() -> handleClose());

        logger.debug("Signal connections complete");
    }

    // ===== Context Menu Setup =====

    /**
     * Sets up right-click context menu for file list.
     * 
     * <p>
     * Requirements: REQ-3.3 - Context menu for preset application
     * Task 26: Add GestureClick for right mouse button
     * </p>
     */
    private void setupFileListContextMenu() {
        // Create GestureClick for right mouse button (button = 3)
        GestureClick gesture = new GestureClick();
        gesture.setButton(3); // Right mouse button

        // Connect to pressed signal to show context menu
        gesture.onPressed((nPress, x, y) -> {
            logger.debug("Right-click detected at ({}, {})", x, y);
            showContextMenu(x, y);
        });

        // Add gesture to the ColumnView widget
        fileListColumnView.addController(gesture);

        logger.debug("Context menu gesture controller added to file list");
    }

    /**
     * Shows the context menu at the specified coordinates.
     * 
     * <p>
     * Requirements: REQ-3.3 - Context menu with preset application options
     * Task 27: Implement "Apply Preset" submenu
     * Task 28: Implement "Clear Custom Settings" menu item
     * Task 59: Implement "Open File Location" menu item
     * </p>
     * 
     * @param x the x coordinate (relative to the parent widget)
     * @param y the y coordinate (relative to the parent widget)
     */
    private void showContextMenu(double x, double y) {
        // Get selected file IDs
        List<String> selectedIds = fileListView.getSelectedFileIds();

        if (selectedIds.isEmpty()) {
            logger.debug("No files selected, not showing context menu");
            return;
        }

        logger.debug("Showing context menu for {} selected file(s) at ({}, {})", selectedIds.size(), x, y);

        // Publish the selection for the single-registration actions and gate
        // "Open File Location" (single selection only) via the live action.
        contextMenuSelection = List.copyOf(selectedIds);
        var openLocation = lookupAction("open-file-location");
        if (openLocation instanceof org.gnome.gio.SimpleAction openAction) {
            openAction.setEnabled(selectedIds.size() == 1);
        }

        // Create menu model
        Menu menu = new Menu();

        // Add "Apply Preset" submenu (Task 27)
        buildApplyPresetSubmenu(menu, selectedIds);

        // Add "Clear Custom Settings" menu item (Task 28)
        buildClearCustomSettingsMenuItem(menu, selectedIds);

        // Add "Open File Location" menu item (Task 59) - only enabled for single
        // selection
        // Note: GTK automatically adds visual spacing between different action groups
        buildOpenFileLocationMenuItem(menu, selectedIds);

        // Reuse a single popover parented to the file list; swap in the
        // freshly built menu model for this invocation.
        if (contextMenuPopover == null) {
            contextMenuPopover = PopoverMenu.fromModel(menu);
            contextMenuPopover.setParent(fileListColumnView);
        } else {
            contextMenuPopover.setMenuModel(menu);
        }

        // Set the popover position to the click coordinates
        // Create a rectangle at the click position (1x1 pixel area)
        org.gnome.gdk.Rectangle rect = new org.gnome.gdk.Rectangle((int) x, (int) y, 1, 1);
        contextMenuPopover.setPointingTo(rect);

        // Show the popover
        contextMenuPopover.popup();

        logger.debug("Context menu displayed at position ({}, {})", x, y);
    }

    /**
     * Builds the "Apply Preset" submenu dynamically based on available presets.
     * 
     * <p>
     * Requirements: REQ-3.3 - Dynamic preset submenu
     * Task 27: Implement "Apply Preset" submenu
     * </p>
     * 
     * @param menu        the parent menu
     * @param selectedIds the list of selected file IDs
     */
    private void buildApplyPresetSubmenu(Menu menu, List<String> selectedIds) {
        Menu presetSubmenu = new Menu();

        try {
            // Get available presets for selected files
            List<SectionPreset> availablePresets = controller.getAvailablePresetsForFiles(selectedIds);

            if (availablePresets.isEmpty()) {
                // No presets available - show disabled message
                MenuItem noPresetsItem = new MenuItem(I18n.tr("No presets available"), null);
                noPresetsItem.setActionAndTarget(null, null);
                presetSubmenu.appendItem(noPresetsItem);
            } else {
                // Prune previously registered preset actions that are no
                // longer offered (deleted/renamed presets), then (re)register
                // the current set. GActionMap replaces same-named actions, so
                // re-registration only refreshes the closure's selection.
                java.util.Set<String> currentNames = new java.util.HashSet<>();
                for (SectionPreset preset : availablePresets) {
                    currentNames.add(presetActionName(preset));
                }
                registeredPresetActionNames.stream()
                        .filter(name -> !currentNames.contains(name))
                        .toList()
                        .forEach(stale -> {
                            removeAction(stale);
                            registeredPresetActionNames.remove(stale);
                        });

                // Add menu item for each available preset
                for (SectionPreset preset : availablePresets) {
                    String actionName = presetActionName(preset);

                    // Create action for this preset
                    SimpleAction action = new SimpleAction(actionName, null);
                    action.onActivate(param -> {
                        logger.debug("Applying preset '{}' to {} files", preset.name(), selectedIds.size());
                        applyPresetToSelectedFiles(selectedIds, preset);
                    });
                    addAction(action);
                    registeredPresetActionNames.add(actionName);

                    // Add menu item
                    MenuItem presetItem = new MenuItem(preset.builtIn() ? I18n.tr(preset.name()) : preset.name(), "win." + actionName);
                    presetSubmenu.appendItem(presetItem);
                }
            }
        } catch (IllegalArgumentException e) {
            // Mixed selection or invalid selection - show error message
            logger.warn("Invalid selection for preset application: {}", e.getMessage());
            MenuItem errorItem = new MenuItem(I18n.tr("Cannot apply to mixed file types"), null);
            errorItem.setActionAndTarget(null, null);
            presetSubmenu.appendItem(errorItem);
        } catch (Exception e) {
            logger.error("Error building preset submenu", e);
            MenuItem errorItem = new MenuItem(I18n.tr("Error loading presets"), null);
            errorItem.setActionAndTarget(null, null);
            presetSubmenu.appendItem(errorItem);
        }

        // Add submenu to parent menu
        menu.appendSubmenu(I18n.tr("Apply Preset"), presetSubmenu);
    }

    /**
     * Builds the "Clear Custom Settings" menu item.
     * 
     * <p>
     * Requirements: REQ-3.3 - Clear custom settings option
     * Task 28: Implement "Clear Custom Settings" menu item
     * </p>
     * 
     * @param menu        the parent menu
     * @param selectedIds the list of selected file IDs
     */
    private void buildClearCustomSettingsMenuItem(Menu menu, List<String> selectedIds) {
        // Action is registered once in setupWindowActions and reads
        // contextMenuSelection; here only the menu model entry is built.
        MenuItem clearItem = new MenuItem(I18n.tr("Clear Custom Settings"), "win.clear-preset");
        menu.appendItem(clearItem);
    }

    /**
     * Builds the "Open File Location" menu item.
     * Only enabled when a single file is selected (REQ-FL-3.1).
     * 
     * <p>
     * Requirements: REQ-FL-3.1 - Context menu with open location option
     * Task 59: Implement "Open File Location" menu item
     * </p>
     * 
     * @param menu        the parent menu
     * @param selectedIds the list of selected file IDs
     */
    private void buildOpenFileLocationMenuItem(Menu menu, List<String> selectedIds) {
        // Action is registered once in setupWindowActions (enabled state is
        // refreshed per popup in showContextMenu); here only the menu model
        // entry is built.
        MenuItem openLocationItem = new MenuItem(I18n.tr("Open File Location"), "win.open-file-location");
        menu.appendItem(openLocationItem);
    }

    /**
     * Applies a preset to the selected files.
     * FIX: Defer error dialogs to avoid modal stacking issues with context menu
     * popover.
     * 
     * @param fileIds the list of file IDs
     * @param preset  the preset to apply
     */
    private void applyPresetToSelectedFiles(List<String> fileIds, SectionPreset preset) {
        try {
            controller.applyPresetToFiles(fileIds, preset);
            refreshFileList();
            showStatus(I18n.tr("Applied preset '%s' to %d file(s)",
                    preset.builtIn() ? I18n.tr(preset.name()) : preset.name(), fileIds.size()));
            logger.info("Applied preset '{}' to {} file(s)", preset.name(), fileIds.size());
        } catch (IllegalArgumentException e) {
            logger.error("Failed to apply preset: {}", e.getMessage());
            // Defer error dialog to next idle cycle to ensure context menu dismisses first
            GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                showErrorDialog(I18n.tr("Apply Preset Error"), e.getMessage());
                return false;
            });
        } catch (Exception e) {
            logger.error("Unexpected error applying preset", e);
            // Defer error dialog to next idle cycle to ensure context menu dismisses first
            GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                showErrorDialog(I18n.tr("Apply Preset Error"), I18n.tr("An unexpected error occurred: ") + e.getMessage());
                return false;
            });
        }
    }

    /**
     * Clears custom settings from the selected files.
     * FIX: Defer error dialogs to avoid modal stacking issues with context menu
     * popover.
     * 
     * @param fileIds the list of file IDs
     */
    private void clearCustomSettingsFromFiles(List<String> fileIds) {
        try {
            controller.clearPresetFromFiles(fileIds);
            refreshFileList();
            showStatus(I18n.tr("Cleared custom settings from %d file(s)", fileIds.size()));
            logger.info("Cleared custom settings from {} file(s)", fileIds.size());
        } catch (Exception e) {
            logger.error("Error clearing custom settings", e);
            // Defer error dialog to next idle cycle to ensure context menu dismisses first
            GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                showErrorDialog(I18n.tr("Clear Settings Error"), I18n.tr("Failed to clear custom settings: ") + e.getMessage());
                return false;
            });
        }
    }

    /**
     * Refreshes the file list display.
     * Updates the file list view with current files from the controller.
     * 
     * <p>
     * Requirements: REQ-3.3 - Refresh UI after preset operations
     * </p>
     */
    private void refreshFileList() {
        updateFileList();
    }

    /**
     * Sanitizes a preset name to create a valid GTK action name.
     * Encodes UTF-8 bytes without losing distinctions between preset names.
     *
     * @param name the preset name
     * @return sanitized action name
     */
    private String sanitizeActionName(String name) {
        return java.util.HexFormat.of().formatHex(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Builds the unprefixed action name for a preset menu entry
     * (menu items reference it as {@code "win." + name}).
     */
    private String presetActionName(SectionPreset preset) {
        return "apply-preset-" + preset.category().name().toLowerCase(java.util.Locale.ROOT)
                + "-" + sanitizeActionName(preset.name());
    }

    // ===== Event Handlers =====

    /**
     * Handles Add Files button click.
     * Requirements: REQ-002.1, Task 43
     */
    private void handleAddFiles() {
        logger.debug("Add Files button clicked");
        showFileChooserDialog(selectedPaths -> addFilesAsync(selectedPaths.stream()
                .map(java.nio.file.Paths::get).toList()));
    }

    /**
     * Handles Add Folder button click.
     * Requirements: REQ-002.1, Task 44
     */
    private void handleAddFolder() {
        logger.debug("Add Folder button clicked");
        showFolderChooserDialog(selectedPath -> {
            if (selectedPath != null && !selectedPath.isBlank()) {
                admitFiles(() -> controller.addFilesFromFolder(java.nio.file.Paths.get(selectedPath), true).size());
            }
        });
    }

    /**
     * Adds files in the background, then refreshes the list on the GTK thread.
     * @param paths files selected by the user or passed on the command line
     */
    public void addFilesAsync(List<java.nio.file.Path> paths) {
        if (paths.isEmpty()) return;
        List<java.nio.file.Path> snapshot = List.copyOf(paths);
        admitFiles(() -> {
            int before = controller.getFileList().size();
            controller.addFiles(snapshot);
            return controller.getFileList().size() - before;
        });
    }

    @FunctionalInterface
    private interface FileAdmission {
        int run() throws FileOperationException;
    }

    private void admitFiles(FileAdmission admission) {
        if (shutdownInProgress) return;
        showStatus(I18n.tr("Reading files…"));
        fileAdmission.submit(() -> {
            int added = 0;
            boolean success = false;
            String failureMessage = null;
            try {
                added = admission.run();
                success = true;
            } catch (FileOperationException | IllegalArgumentException e) {
                logger.error("Failed to add files", e);
                failureMessage = e.getMessage();
            } catch (RuntimeException e) {
                logger.error("Unexpected failure adding files", e);
                failureMessage = I18n.tr("An unexpected error occurred while reading files: ") + e.getMessage();
            }
            final int addedCount = added;
            final boolean succeeded = success;
            final String errorMessage = failureMessage;
            if (errorMessage != null) {
                GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                    if (!shutdownInProgress) showErrorDialog(I18n.tr("Add Files"), errorMessage);
                    return false;
                });
            }
            // Mirror the success path's cleanup for every outcome so the
            // status bar never stays stuck on "Reading files…".
            GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                if (!shutdownInProgress) {
                    updateFileList();
                    showStatus(admissionStatusMessage(addedCount, succeeded));
                }
                return false;
            });
        });
    }

    /**
     * Terminal status bar message for a background file admission run.
     * Pure static so it is unit-testable without GTK.
     *
     * @param addedCount the number of files the admission added
     * @param success    whether the admission completed without throwing
     * @return the status message replacing the transient "Reading files…"
     */
    static String admissionStatusMessage(int addedCount, boolean success) {
        return success ? I18n.tr("%d file(s) added", addedCount) : I18n.tr("Failed to add files");
    }

    /**
     * Handles Settings button click.
     */
    private void handleSettings() {
        logger.debug("Settings button clicked");

        try {
            // Get current settings from controller
            ConversionSettings currentSettings = controller.getCurrentSettings();

            // Create and show settings dialog
            SettingsDialogJavaGi settingsDialog = new SettingsDialogJavaGi(this, currentSettings,
                    controller.getSettingsManager(), controller);
            settingsDialog.setAvailableCategories(controller.getAvailableCategories());

            // Register callback to receive updated settings when user clicks Save
            // FIX: Defer error dialog to avoid modal stacking (settings dialog still open)
            settingsDialog.setOnSaveCallback(newSettings -> {
                logger.debug("Settings dialog saved, updating controller with new settings");
                try {
                    controller.updateSettings(newSettings);
                    logger.info("Settings successfully updated in controller");

                    // Refresh the file list's cached settings snapshot so the
                    // Output Format column resolves against the new settings.
                    fileListView.refreshSettingsCache();

                    // BUG FIX: Refresh file list to update Output Format column
                    // When section output format changes, the Output Format column needs to be
                    // refreshed
                    refreshFileList();
                    logger.debug("File list refreshed after settings update");
                } catch (Exception e) {
                    logger.error("Failed to update settings in controller", e);
                    // Defer error dialog to next idle cycle to ensure settings dialog closes first
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        showErrorDialog(I18n.tr("Settings Error"), I18n.tr("Failed to save settings: ") + e.getMessage());
                        return false;
                    });
                }
            });

            settingsDialog.showDialog();

            logger.debug("Settings dialog displayed");
        } catch (Exception e) {
            logger.error("Failed to show settings dialog", e);
            showErrorDialog(I18n.tr("Settings Error"), I18n.tr("Failed to open settings dialog: ") + e.getMessage());
        }
    }

    /**
     * Handles Remove Selected button click.
     * Requirements: REQ-002.2, Task 45
     */
    private void handleRemoveSelected() {
        logger.debug("Remove Selected button clicked");
        List<String> selectedFileIds = fileListView.getSelectedFileIds();
        if (selectedFileIds.isEmpty()) {
            return;
        }

        // Check if conversions are in progress
        if (controller.isConversionInProgress()) {
            showConfirmDialog(
                    I18n.tr("Remove Files"),
                    I18n.tr("Conversions are in progress. Are you sure you want to remove selected files?"),
                    confirmed -> {
                        if (confirmed) {
                            removeFiles(selectedFileIds);
                        }
                    });
        } else {
            // No conversion in progress, confirm removal
            showConfirmDialog(
                    I18n.tr("Remove Files"),
                    I18n.tr("Remove %d selected file(s)?", selectedFileIds.size()),
                    confirmed -> {
                        if (confirmed) {
                            removeFiles(selectedFileIds);
                        }
                    });
        }
    }

    /**
     * Helper method to remove files from the list.
     * 
     * @param fileIds List of file IDs to remove
     */
    private void removeFiles(List<String> fileIds) {
        try {
            controller.handleRemoveFiles(fileIds);
            updateFileList();
            showStatus(I18n.tr("%d file(s) removed", fileIds.size()));
        } catch (FileOperationException e) {
            logger.error("Failed to remove files", e);
            showErrorDialog(I18n.tr("Remove Files Error"), e.getMessage());
        }
    }

    /**
     * Handles Clear All button click.
     */
    private void handleClearAll() {
        logger.debug("Clear All button clicked");
        controller.handleClearFiles();
        updateFileList();
    }

    /**
     * Handles Convert button click.
     */
    private void handleConvert() {
        logger.debug("Convert button clicked");

        // Re-entrancy guard (GTK thread only): a second start while a batch
        // is running must never reset the running batch's counters or UI
        // state. The controller's CAS rejects the engine side with
        // IllegalStateException; this keeps the UI side consistent.
        // This also covers the Ctrl+Return app.convert action, which routes
        // through triggerConvert() -> handleConvert().
        if (batchState.isRunning()) {
            logger.warn("Ignoring convert request: conversion already in progress");
            return;
        }

        // Show progress view and disable convert button
        showProgressView();
        convertButton.setSensitive(false);
        // Can Pause
        showPauseButton();

        try {
            List<String> fileIds = controller.handleStartConversion();
            // Use exactly the jobs admitted by the controller, excluding completed rows.
            batchState.beginBatch(fileIds);
            batchState.markStarted();
        } catch (RuntimeException e) {
            // Start rejected (validation failure or already in progress via
            // IllegalStateException) or failed (the controller wraps
            // convertBatch errors such as RejectedExecutionException in a
            // plain RuntimeException): roll back the pre-start UI changes so
            // the window stays usable instead of locking the UI.
            logger.error("Conversion start rejected: {}", e.getMessage());
            hideProgressView();
            updateFileList();
            showErrorDialog(I18n.tr("Conversion Error"), e.getMessage());
            showStatus(I18n.tr("Conversion not started: ") + e.getMessage());
            return;
        }

        // Batch counters are initialized only after a successful start, so a
        // rejected start cannot disturb any (running or previous) batch state.
        logger.info("Starting conversion batch with {} files", batchState.totalFiles());
        showStatus(I18n.tr("Conversion Started"));
    }

    /**
     * Handles Pause button click.
     */
    private void handlePause() {
        logger.debug("Pause button clicked");
        try {
            controller.handlePauseConversion();
            showResumeButton();
            showStatus(I18n.tr("Conversion Paused"));
        } catch (RuntimeException failure) {
            logger.error("Could not pause conversion", failure);
            showErrorDialog(I18n.tr("Pause Error"), failure.getMessage());
        }
    }

    /**
     * Handles Resume button click.
     */
    private void handleResume() {
        logger.debug("Resume button clicked");
        try {
            controller.handleResumeConversion();
            showPauseButton();
            showStatus(I18n.tr("Conversion Resumed"));
        } catch (RuntimeException failure) {
            logger.error("Could not resume conversion", failure);
            showErrorDialog(I18n.tr("Resume Error"), failure.getMessage());
        }
    }

    /**
     * Handles Cancel button click.
     * Requirements: REQ-004.2, Task 48
     */
    private void handleCancel() {
        logger.debug("Cancel button clicked");
        showConfirmDialog(
                I18n.tr("Cancel Conversion"),
                I18n.tr("Are you sure you want to cancel all conversions?"),
                confirmed -> {
                    if (confirmed) {
                        controller.handleCancelConversion();
                        showStatus(I18n.tr("Conversions cancelled"));
                    }
                });
    }

    /**
     * Handles window close event.
     * Returns true to prevent window from closing (when showing confirmation
     * dialog),
     * or false to allow window to close.
     * Requirements: REQ-001.2, REQ-005.1, REQ-005.2, REQ-005.3, Task 49, Task 50
     */
    private boolean handleClose() {
        if (shutdownInProgress) return false;
        if (controller.isConversionInProgress()) {
            showConfirmDialog(I18n.tr("Close Application"),
                    I18n.tr("Conversions are in progress. Exit and cancel active conversions?"),
                    confirmed -> { if (confirmed) beginShutdown(); });
        } else {
            beginShutdown();
        }
        return true;
    }

    private void beginShutdown() {
        if (shutdownInProgress) return;
        controller.updateWindowState(saveState());
        shutdownInProgress = true;
        // No further batch lifecycle events will be handled; clear the UI-side
        // running flag along with the counters.
        batchState.endBatch();
        // Drop pending coalesced UI updates; the window is going away.
        uiUpdateCoalescer.clear();
        fileAdmission.shutdownNow();
        new Thread(() -> {
            try {
                // Hashing checks interruption, so admission stops before state is saved.
                fileAdmission.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                controller.shutdown(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for file admission", e);
            } finally {
                GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> { close(); return false; });
            }
        }, "shutdown-thread").start();
    }

    // ===== Public API for Controller and Actions =====

    /**
     * Triggers the Add Files action (called from keyboard shortcut or button).
     * Requirements: REQ-102.2 - Keyboard shortcuts
     */
    public void triggerAddFiles() {
        handleAddFiles();
    }

    /**
     * Triggers the Settings action (called from keyboard shortcut or button).
     * Requirements: REQ-102.2 - Keyboard shortcuts
     */
    public void triggerSettings() {
        handleSettings();
    }

    /**
     * Triggers the Convert action (called from keyboard shortcut or button).
     * Requirements: REQ-102.2 - Keyboard shortcuts
     */
    public void triggerConvert() {
        handleConvert();
    }

    /**
     * Triggers the Remove Selected action (called from keyboard shortcut or
     * button).
     * Requirements: REQ-102.2 - Keyboard shortcuts
     */
    public void triggerRemoveSelected() {
        handleRemoveSelected();
    }

    /**
     * Triggers the Select All action (called from keyboard shortcut or button).
     * Requirements: REQ-102.2 - Keyboard shortcuts
     */
    public void triggerSelectAll() {
        if (fileListView != null) {
            fileListView.selectAll();
        }
    }

    /**
     * Updates the file list display with current files from controller.
     * Thread-safe: Can be called from background threads.
     */
    public void updateFileList() {
        if (shutdownInProgress) return;
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            if (shutdownInProgress) return false;
            List<ConversionFile> files = controller.getFileList();
            fileListView.setFiles(files);

            // Update file count and size labels
            long totalSize = files.stream().mapToLong(ConversionFile::size).sum();
            fileCountLabel.setLabel(I18n.tr(files.size() == 1 ? "%d file selected" : "%d files selected", files.size()));
            totalSizeLabel.setLabel(formatBytes(totalSize));

            // Enable/disable Convert and Clear All buttons based on file list,
            // but never while a conversion is running: they are intentionally
            // disabled for the batch and re-enabled on batch completion
            // (onBatchComplete -> hideProgressView).
            boolean hasFiles = !files.isEmpty();
            if (batchState.shouldRefreshListButtonSensitivity()) {
                convertButton.setSensitive(hasFiles);
                clearAllButton.setSensitive(hasFiles);
            }

            return false; // Don't repeat
        });
    }

    /**
     * Shows a status message in the status bar.
     * Thread-safe: Can be called from background threads.
     * 
     * @param message the message to display
     */
    public void showStatus(String message) {
        if (shutdownInProgress) return;
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            if (shutdownInProgress) return false;
            statusBarLabel.setLabel(message);
            logger.debug("Status: {}", message);
            return false;
        });
    }

    /**
     * Shows the progress view.
     */
    public void showProgressView() {
        if (shutdownInProgress) return;
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            if (shutdownInProgress) return false;
            progressView.show();

            // Disable file management buttons during conversion
            addFilesButton.setSensitive(false);
            addFolderButton.setSensitive(false);
            removeSelectedButton.setSensitive(false);
            clearAllButton.setSensitive(false);
            convertButton.setSensitive(false);

            // Enable progress control buttons during conversion
            pauseButton.setSensitive(true);
            resumeButton.setSensitive(true);
            cancelButton.setSensitive(true);

            return false;
        });
    }

    /**
     * Hides the progress view.
     */
    public void hideProgressView() {
        if (shutdownInProgress) return;
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            if (shutdownInProgress) return false;
            progressView.hide();

            // Re-enable file management buttons
            addFilesButton.setSensitive(true);
            addFolderButton.setSensitive(true);
            removeSelectedButton.setSensitive(true);

            // Only re-enable Convert and Clear All if there are files
            boolean hasFiles = !controller.getFileList().isEmpty();
            clearAllButton.setSensitive(hasFiles);
            convertButton.setSensitive(hasFiles);

            // Disable progress control buttons when conversion is not in progress
            pauseButton.setSensitive(false);
            resumeButton.setSensitive(false);
            cancelButton.setSensitive(false);

            return false;
        });
    }

    /**
     * Updates the progress for a specific file during conversion.
     * Thread-safe: Can be called from background threads.
     *
     * <p>Requirement REQ-004.2: Per-file progress updates</p>
     *
     * <p>Intermediate progress coalesces per file id: bursts of updates result
     * in a single GLib idle callback carrying the latest progress, instead of
     * one idle callback per engine event.</p>
     *
     * @param fileId   the file ID
     * @param progress the conversion progress for this file
     */
    public void updateFileProgress(String fileId, ConversionProgress progress) {
        uiUpdateCoalescer.submit("file-progress:" + fileId, () -> {
            // Update progress view with per-file progress
            progressView.updateFileProgress(fileId, progress);

            // Update file list view with updated file
            // Note: The controller has already updated the file with both progress and
            // status,
            // so we use the file as-is without further modification to preserve status
            // changes
            Optional<ConversionFile> fileOpt = controller.getFile(fileId);
            if (fileOpt.isPresent()) {
                fileListView.updateFile(fileId, fileOpt.get());
            }

            logger.debug("File progress updated: {} - {}%", fileId, progress.percentage());
        });
    }

    /**
     * Updates a file with the conversion result (success or failure).
     * Thread-safe: Can be called from background threads.
     *
     * <p>Requirement REQ-004.2: Per-file completion updates and batch completion
     * notification</p>
     *
     * <p>Completions are terminal events: they are submitted forced to the idle
     * coalescer so every result reaches the UI exactly once, flushing any
     * pending coalesced progress updates ahead of itself.</p>
     *
     * @param fileId the file ID
     * @param result the conversion result
     */
    public void updateFileResult(String fileId, ConversionResult result) {
        uiUpdateCoalescer.submitForced("file-result:" + fileId, () -> {
            // Get the updated file from controller (status should be updated by controller)
            Optional<ConversionFile> fileOpt = controller.getFile(fileId);
            if (fileOpt.isPresent()) {
                ConversionFile file = fileOpt.get();
                // Update file list view
                fileListView.updateFile(fileId, file);

                // Track completion for batch progress
                if (result.success()) {
                    batchState.recordSuccess();
                    logger.info("Conversion completed successfully: {} ({}/{} files)",
                            fileId, batchState.completedFiles(), batchState.totalFiles());
                    showStatus(I18n.tr("Conversion completed: ") + result.outputPath().map(Path::toString).orElse(""));
                } else if (result.isCancelled()) {
                    batchState.recordCancelled();
                    logger.info("Conversion cancelled: {} ({}/{} files)",
                            fileId, batchState.completedFiles(), batchState.totalFiles());
                    showStatus(I18n.tr("Conversion cancelled: ") + fileId);
                } else {
                    batchState.recordFailure();
                    logger.error("Conversion failed: {} - {} ({}/{} files)",
                            fileId, result.errorMessage().orElse("Unknown error"),
                            batchState.completedFiles(), batchState.totalFiles());
                    showStatus(I18n.tr("Conversion failed: ") + result.errorMessage().orElse(I18n.tr("Unknown error")));
                }

                // Note: Overall progress bar, speed, and time remaining are now updated
                // automatically via the batch progress listener (see updateBatchProgress
                // method)
            } else {
                // Result for a file that was removed from the list mid-batch
                // (e.g. Delete during conversion): count it as completed but
                // unclassified (neither success nor failure) so the batch can
                // still reach completion instead of stalling forever.
                logger.warn("File not found for result update: {} (counted as completed)", fileId);
                batchState.recordRemovedFileResult();
            }

            // Check if batch is complete (runs for present and removed files)
            if (batchState.isBatchComplete()) {
                batchState.markBatchCompleted();
                onBatchComplete();
            }
        });
    }

    /**
     * Updates the batch progress display (speed and time remaining).
     * Called by ApplicationWorkflowController when batch progress is updated.
     *
     * <p>Requirement REQ-004.3: Batch progress tracking with speed and ETA</p>
     *
     * <p>Batch progress updates coalesce under the single "batch" key: only the
     * latest snapshot reaches the UI per idle cycle.</p>
     *
     * @param batchProgress the current batch progress
     */
    public void updateBatchProgress(BatchProgress batchProgress) {
        uiUpdateCoalescer.submit("batch", () -> {
            progressView.updateOverallProgress(batchProgress);
        });
    }

    /**
     * Called when all files in the batch have completed conversion.
     * Shows notification, saves session state, and resets UI state.
     *
     * <p>Requirement REQ-004.2: Batch completion notification and state saving</p>
     *
     * <p>The notification prefers counts derived from the engine's per-file
     * results (queried via the controller for the batch's file IDs) over the
     * independently incremented UI counters, so the message reflects what the
     * engine actually recorded. If any per-file result is missing, the local
     * counters are used as a fallback.</p>
     */
    private void onBatchComplete() {
        logger.info("Batch conversion complete: {} successful, {} failed, {} cancelled out of {} total files",
                batchState.successfulFiles(), batchState.failedFiles(),
                batchState.cancelledFiles(), batchState.totalFiles());

        // Show completion notification, preferring engine-derived counts
        String message = composeBatchCompletionMessage(
                batchState.totalFiles(), batchState.successfulFiles(),
                batchState.failedFiles(), batchState.cancelledFiles());
        int[] engineCounts = deriveBatchCountsFromEngineResults();
        if (engineCounts != null) {
            message = composeBatchCompletionMessage(engineCounts[0], engineCounts[1], engineCounts[2],
                    engineCounts[3]);
        }

        showStatus(message);

        // Show GTK notification (application-level notification)
        // Requirement REQ-004.2: Show notification on batch completion
        showCompletionNotification(message);

        // Reset batch tracking counters and clear the UI-side running flag
        // (re-allows Convert and list button sensitivity refreshes)
        batchState.endBatch();

        // Update UI state - hide progress, enable convert button
        hideProgressView();
        convertButton.setSensitive(true);

        // Drop per-file progress tracking for the finished batch so the map
        // does not grow unboundedly across batches (ids are unique per batch).
        progressView.clearFileProgress();

        // Let controller know conversion is complete (it will save session state)
        // The controller already handles this via its completion listener
        logger.debug("Batch completion notification sent to user");
    }

    /**
     * Pure batch tracking state for the conversion lifecycle.
     *
     * <p>
     * Holds the per-batch counters, the one-shot completion latch, the batch
     * file-id snapshot and the UI-side "conversion in progress" flag. Extracted
     * from the window's fields so the semantics below are unit-testable
     * headless (GTK widgets cannot be realized without a display; the pure
     * static batch message helper follows the same pattern):
     * </p>
     * <ul>
     * <li>{@link #beginBatch} refuses to run while a batch is in progress, so
     * a double start can never corrupt the running batch's counters.</li>
     * <li>{@link #recordRemovedFileResult} counts a result for a file removed
     * mid-batch as completed but unclassified, so the batch can still reach
     * completion instead of stalling.</li>
     * <li>{@link #shouldRefreshListButtonSensitivity} blocks list-driven
     * Convert/Clear All sensitivity refreshes while a batch runs.</li>
     * </ul>
     *
     * <p>
     * All access is confined to the GTK main thread (like the fields it
     * replaces).
     * </p>
     */
    static final class BatchUiState {
        private int totalFiles;
        private int completedFiles;
        private int successfulFiles;
        private int failedFiles;
        private int cancelledFiles;
        private boolean batchCompleted;
        private boolean conversionInProgress;
        private List<String> batchFileIds = List.of();

        static BatchUiState idle() {
            return new BatchUiState();
        }

        /** Returns whether a batch started by this window is currently running. */
        boolean isRunning() {
            return conversionInProgress;
        }

        /**
         * Marks the batch as running. Only called after a successful
         * {@code controller.handleStartConversion()}.
         */
        void markStarted() {
            conversionInProgress = true;
        }

        /**
         * Initializes counters for a new batch. Refuses (without mutation)
         * while a batch is running so a second start cannot corrupt it.
         *
         * @param fileIds the file IDs making up the batch
         * @return {@code true} if the batch was initialized
         */
        boolean beginBatch(List<String> fileIds) {
            if (conversionInProgress) {
                return false;
            }
            batchFileIds = List.copyOf(fileIds);
            totalFiles = fileIds.size();
            completedFiles = 0;
            successfulFiles = 0;
            failedFiles = 0;
            cancelledFiles = 0;
            batchCompleted = false;
            return true;
        }

        /** Resets all counters, the completion latch, the id snapshot and the running flag. */
        void endBatch() {
            totalFiles = 0;
            completedFiles = 0;
            successfulFiles = 0;
            failedFiles = 0;
            cancelledFiles = 0;
            batchCompleted = false;
            conversionInProgress = false;
            batchFileIds = List.of();
        }

        void recordSuccess() {
            completedFiles++;
            successfulFiles++;
        }

        void recordFailure() {
            completedFiles++;
            failedFiles++;
        }

        void recordCancelled() {
            completedFiles++;
            cancelledFiles++;
        }

        /**
         * Records a terminal result for a file that was removed from the list
         * mid-batch: completed, but neither success nor failure.
         */
        void recordRemovedFileResult() {
            completedFiles++;
        }

        /**
         * Returns whether all files of the current batch are accounted for.
         * One-shot: latched off by {@link #markBatchCompleted()} until the
         * next {@link #beginBatch} or {@link #endBatch}.
         */
        boolean isBatchComplete() {
            return !batchCompleted && totalFiles > 0 && completedFiles >= totalFiles;
        }

        /** Latches completion so batch completion handling fires exactly once per batch. */
        void markBatchCompleted() {
            batchCompleted = true;
        }

        /**
         * Whether list updates may refresh Convert/Clear All sensitivity.
         * Blocked while a conversion runs (the buttons belong to the batch
         * lifecycle and are restored on completion).
         */
        boolean shouldRefreshListButtonSensitivity() {
            return !conversionInProgress;
        }

        int totalFiles() {
            return totalFiles;
        }

        int completedFiles() {
            return completedFiles;
        }

        int successfulFiles() {
            return successfulFiles;
        }

        int failedFiles() {
            return failedFiles;
        }

        int cancelledFiles() {
            return cancelledFiles;
        }

        boolean batchCompleted() {
            return batchCompleted;
        }

        List<String> batchFileIds() {
            return batchFileIds;
        }
    }

    /**
     * Composes the user-facing batch completion message from result counts.
     * Pure static method so the wording is unit-testable without GTK.
     *
     * <p>
     * Files removed mid-conversion complete the batch but are unclassified:
     * they count as neither successful, failed nor cancelled. Their count is
     * derived as {@code totalFiles - successful - failed - cancelled} so the
     * message accounts for them instead of claiming successes that never
     * happened (e.g. 1 success + 1 removed is not "all successful").
     * </p>
     *
     * @param totalFiles total files in the batch
     * @param successful successfully converted files
     * @param failed     failed files
     * @param cancelled  cancelled files
     * @return the notification message
     */
    static String composeBatchCompletionMessage(int totalFiles, int successful, int failed, int cancelled) {
        if (cancelled == totalFiles) {
            // All files were cancelled
            return I18n.tr("All conversions cancelled");
        }
        int removed = totalFiles - successful - failed - cancelled;
        if (removed > 0) {
            // Some files were removed mid-conversion: report what actually
            // happened to each file instead of an all-successful claim.
            StringBuilder sb = new StringBuilder(I18n.tr("%d files processed:", totalFiles));
            sb.append(I18n.tr(" %d succeeded", successful));
            if (failed > 0) {
                sb.append(I18n.tr(", %d failed", failed));
            }
            if (cancelled > 0) {
                sb.append(I18n.tr(", %d cancelled", cancelled));
            }
            sb.append(I18n.tr(", %d removed during conversion", removed));
            return sb.toString();
        } else if (failed == 0 && cancelled == 0 && successful == totalFiles) {
            return I18n.tr("All %d files converted successfully!", totalFiles);
        } else if (successful == 0) {
            return I18n.tr("All %d files failed to convert.", totalFiles);
        } else {
            // Mixed results
            StringBuilder sb = new StringBuilder(I18n.tr("Conversion complete: "));
            sb.append(I18n.tr("%d successful", successful));
            if (failed > 0) {
                sb.append(I18n.tr(", %d failed", failed));
            }
            if (cancelled > 0) {
                sb.append(I18n.tr(", %d cancelled", cancelled));
            }
            sb.append(I18n.tr(" out of %d files.", totalFiles));
            return sb.toString();
        }
    }

    /**
     * Derives batch result counts from the engine's stored per-file results.
     *
     * @return {@code {total, successful, failed, cancelled}}, or null when a
     *         result is missing for any batch file (caller falls back to its
     *         own counters)
     */
    private int[] deriveBatchCountsFromEngineResults() {
        List<String> batchFileIds = batchState.batchFileIds();
        if (batchFileIds.isEmpty()) {
            return null;
        }
        int successful = 0;
        int failed = 0;
        int cancelled = 0;
        for (String fileId : batchFileIds) {
            ConversionResult result = controller.getConversionResult(fileId);
            if (result == null) {
                // Result not stored (yet): engine counts incomplete.
                return null;
            } else if (result.success()) {
                successful++;
            } else if (result.isCancelled()) {
                cancelled++;
            } else {
                failed++;
            }
        }
        return new int[] { batchFileIds.size(), successful, failed, cancelled };
    }

    /**
     * Shows a desktop notification for batch completion.
     * 
     * @param message the notification message
     */
    private void showCompletionNotification(String message) {
        Application application = getApplication();
        if (application == null) return;
        try {
            Notification notification = new Notification("Open Media Converter");
            notification.setBody(message);
            notification.setIcon(new ThemedIcon("open-media-converter"));
            application.sendNotification("conversion-complete", notification);
            logger.debug("Completion notification submitted: {}", message);
        } catch (RuntimeException e) {
            logger.warn("Could not submit completion notification", e);
        }
    }

    /**
     * Shows the pause button and hides the resume button.
     */
    private void showPauseButton() {
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            pauseButton.setVisible(true);
            resumeButton.setVisible(false);
            return false;
        });
    }

    /**
     * Shows the resume button and hides the pause button.
     */
    private void showResumeButton() {
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            pauseButton.setVisible(false);
            resumeButton.setVisible(true);
            return false;
        });
    }

    /** Shows which conversion categories are available when tools are missing. */
    public void showAvailableCategories() {
        var categories = controller.getAvailableCategories();
        if (categories.size() < 4) {
            showStatus(categories.isEmpty() ? I18n.tr("No conversion tools found. Install FFmpeg, ImageMagick, Pandoc or LibreOffice.")
                    : I18n.tr("Available conversions: ") + categories.stream().map(category -> switch (category) {
                        case VIDEO -> I18n.tr("Video");
                        case AUDIO -> I18n.tr("Audio");
                        case IMAGE -> I18n.tr("Image");
                        case DOCUMENT -> I18n.tr("Document");
                        default -> I18n.tr("Unknown");
                    }).sorted().collect(Collectors.joining(", ")));
        }
    }

    // ===== Window State Management =====

    private void captureNormalSize() {
        // GTK maintains the normal size across user resizing, maximization and
        // fullscreen. Widget allocation excludes decorations and drifts on restore.
        var width = new io.github.jwharm.javagi.base.Out<Integer>();
        var height = new io.github.jwharm.javagi.base.Out<Integer>();
        getDefaultSize(width, height);
        if (width.get() != null && width.get() > 0) currentWidth = width.get();
        if (height.get() != null && height.get() > 0) currentHeight = height.get();
    }

    /**
     * Saves the current window state.
     * 
     * @return the captured window state
     */
    public WindowState saveState() {
        captureNormalSize();
        int width = currentWidth;
        int height = currentHeight;

        // Note: GTK 4 doesn't provide get_position() easily
        // We'll store 0,0 for now and handle positioning differently
        int x = 0;
        int y = 0;

        boolean maximized = isMaximized();
        boolean fullscreen = isFullscreen();

        logger.debug("Saving window state: {}x{} maximized={} fullscreen={}",
                width, height, maximized, fullscreen);

        return new WindowState(width, height, x, y, maximized, fullscreen);
    }

    /**
     * Restores window state from saved state.
     * 
     * @param state the window state to restore
     */
    public void restoreState(WindowState state) {
        if (state == null) {
            logger.debug("No window state to restore, using defaults");
            return;
        }

        logger.debug("Restoring window state: {}x{} maximized={} fullscreen={}",
                state.width(), state.height(),
                state.maximized(), state.fullscreen());

        // Restore window size
        if (state.width() > 0 && state.height() > 0) {
            currentWidth = state.width();
            currentHeight = state.height();
            setDefaultSize(state.width(), state.height());
        }

        // Restore maximized state
        if (state.maximized()) {
            maximize();
        }

        // Restore fullscreen state
        if (state.fullscreen()) {
            fullscreen();
        }
    }

    // ===== Utility Methods =====

    /**
     * Formats bytes into human-readable format.
     * 
     * @param bytes the number of bytes
     * @return formatted string (e.g., "1.5 MB")
     */
    private String formatBytes(long bytes) {
        return I18n.bytes(bytes);
    }

    // ===== Dialog Helper Methods =====

    /**
     * Shows a file chooser dialog for selecting multiple files to convert.
     * Uses GTK 4's FileDialog API (replaces deprecated FileChooserDialog).
     * Requirements: REQ-001, Task 36, Task 37
     * 
     * @param callback Callback to receive list of selected file paths
     */
    @SuppressWarnings("deprecation")
    private void showFileChooserDialog(java.util.function.Consumer<List<String>> callback) {
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            try {
                // Use no-arg constructor to avoid varargs FFI crash
                // The varargs constructor FileChooserDialog(title, parent, action, btn1, resp1,
                // btn2, resp2)
                // causes SIGSEGV in java-gi FFI layer due to improper varargs handling
                org.gnome.gtk.FileChooserDialog dialog = new org.gnome.gtk.FileChooserDialog();

                // Set properties after construction
                dialog.setTitle(I18n.tr("Select Files to Convert"));
                dialog.setTransientFor(this);
                dialog.setAction(org.gnome.gtk.FileChooserAction.OPEN);
                dialog.setModal(true);
                dialog.setSelectMultiple(true);

                // Add buttons separately (safer than varargs constructor)
                dialog.addButton(I18n.tr("_Cancel"), org.gnome.gtk.ResponseType.CANCEL.getValue());
                dialog.addButton(I18n.tr("_Open"), org.gnome.gtk.ResponseType.ACCEPT.getValue());

                addFileFilters(dialog);
                dialog.show();

                // A window-manager close (titlebar X) destroys the dialog
                // without emitting "response"; the close-request handler below
                // guarantees the callback still fires exactly once with the
                // cancellation value.
                java.util.concurrent.atomic.AtomicBoolean responded = new java.util.concurrent.atomic.AtomicBoolean(
                        false);

                dialog.onResponse(responseId -> {
                    if (!responded.compareAndSet(false, true)) {
                        return;
                    }
                    List<String> selectedPaths = new java.util.ArrayList<>();
                    if (responseId == org.gnome.gtk.ResponseType.ACCEPT.getValue()) {
                        var files = dialog.getFiles();
                        if (files != null) {
                            for (int i = 0; i < files.getNItems(); i++) {
                                var file = (org.gnome.gio.File) files.getItem(i);
                                if (file != null) {
                                    String path = file.getPath();
                                    if (path != null && !path.isBlank()) {
                                        selectedPaths.add(path);
                                    }
                                }
                            }
                        }
                    }
                    dialog.destroy();
                    callback.accept(selectedPaths);
                });

                dialog.onCloseRequest(() -> {
                    if (responded.compareAndSet(false, true)) {
                        callback.accept(List.of());
                    }
                    dialog.destroy();
                    return true;
                });

            } catch (Exception e) {
                logger.error("Error showing file chooser dialog", e);
                callback.accept(List.of());
            }
            return false;
        });
    }

    /**
     * Shows a folder chooser dialog for selecting an output directory.
     * Uses FileChooserDialog temporarily until FileDialog API is stable.
     * Requirements: REQ-001, Task 36, Task 37
     * 
     * @param callback Callback to receive the selected folder path (null if
     *                 cancelled)
     */
    @SuppressWarnings("deprecation")
    private void showFolderChooserDialog(java.util.function.Consumer<String> callback) {
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            try {
                // Use no-arg constructor to avoid varargs FFI crash
                org.gnome.gtk.FileChooserDialog dialog = new org.gnome.gtk.FileChooserDialog();

                // Set properties after construction
                dialog.setTitle(I18n.tr("Select Output Folder"));
                dialog.setTransientFor(this);
                dialog.setAction(org.gnome.gtk.FileChooserAction.SELECT_FOLDER);
                dialog.setModal(true);

                // Add buttons separately (safer than varargs constructor)
                dialog.addButton(I18n.tr("_Cancel"), org.gnome.gtk.ResponseType.CANCEL.getValue());
                dialog.addButton(I18n.tr("_Select"), org.gnome.gtk.ResponseType.ACCEPT.getValue());

                dialog.show();

                // A window-manager close (titlebar X) destroys the dialog
                // without emitting "response"; the close-request handler below
                // guarantees the callback still fires exactly once with the
                // cancellation value.
                java.util.concurrent.atomic.AtomicBoolean responded = new java.util.concurrent.atomic.AtomicBoolean(
                        false);

                dialog.onResponse(responseId -> {
                    if (!responded.compareAndSet(false, true)) {
                        return;
                    }
                    String selectedPath = null;
                    if (responseId == org.gnome.gtk.ResponseType.ACCEPT.getValue()) {
                        var file = dialog.getFile();
                        if (file != null) {
                            selectedPath = file.getPath();
                        }
                    }
                    dialog.destroy();
                    callback.accept(selectedPath);
                });

                dialog.onCloseRequest(() -> {
                    if (responded.compareAndSet(false, true)) {
                        callback.accept(null);
                    }
                    dialog.destroy();
                    return true;
                });

            } catch (Exception e) {
                logger.error("Error showing folder chooser dialog", e);
                callback.accept(null);
            }
            return false;
        });
    }

    /**
     * Shows a confirmation dialog with Yes/No buttons.
     * Requirements: Task 45, Task 48, Task 49, Task 50
     * 
     * @param title    Dialog title
     * @param message  Dialog message
     * @param callback Callback to receive user response (true=Yes, false=No/Cancel)
     */
    @SuppressWarnings("deprecation")
    private void showConfirmDialog(String title, String message, java.util.function.Consumer<Boolean> callback) {
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            try {
                org.gnome.gtk.MessageDialog dialog = new org.gnome.gtk.MessageDialog(
                        this,
                        org.gnome.gtk.DialogFlags.MODAL,
                        org.gnome.gtk.MessageType.QUESTION,
                        org.gnome.gtk.ButtonsType.YES_NO,
                        message);

                dialog.setTitle(title);
                dialog.show();

                // A window-manager close (titlebar X) destroys the dialog
                // without emitting "response"; the close-request handler below
                // guarantees the callback still fires exactly once with the
                // cancellation value.
                java.util.concurrent.atomic.AtomicBoolean responded = new java.util.concurrent.atomic.AtomicBoolean(
                        false);

                dialog.onResponse(responseId -> {
                    if (!responded.compareAndSet(false, true)) {
                        return;
                    }
                    boolean confirmed = (responseId == org.gnome.gtk.ResponseType.YES.getValue());
                    dialog.destroy();
                    callback.accept(confirmed);
                });

                dialog.onCloseRequest(() -> {
                    if (responded.compareAndSet(false, true)) {
                        callback.accept(false);
                    }
                    dialog.destroy();
                    return true;
                });
            } catch (Exception e) {
                logger.error("Error showing confirm dialog", e);
                callback.accept(false);
            }
            return false;
        });
    }

    /**
     * Shows an error dialog with the given title and message.
     * Uses the new ErrorDialog helper for consistent error UI.
     * Requirements: REQ-007.1
     *
     * @param title   The dialog title
     * @param message The error message
     */
    private void showErrorDialog(String title, String message) {
        ErrorDialog.showError(this, title, message);
    }

    /**
     * Shows the file details dialog for a given file ID.
     * 
     * Requirement REQ-FL-2.1: Display detailed file information and conversion
     * results
     * Task 55: Implement showFileDetailsDialog handler
     * 
     * @param fileId The file ID to show details for
     */
    private void showFileDetailsDialog(String fileId) {
        logger.debug("Opening file details dialog for file: {}", fileId);

        // Get the conversion file from the controller
        Optional<ConversionFile> fileOptional = controller.getFile(fileId);
        if (fileOptional.isEmpty()) {
            logger.warn("File not found for ID: {}", fileId);
            showErrorDialog(I18n.tr("File Not Found"), I18n.tr("Unable to find file details for the selected file."));
            return;
        }

        ConversionFile file = fileOptional.get();

        // Get the conversion result (may be null if not yet converted)
        ConversionResult result = controller.getConversionResult(fileId);

        // Create and show the dialog, passing the current settings so output
        // format resolution uses the same core helper as the file list.
        // Single instance is reused: repeated double-clicks on the same file
        // re-present the existing window instead of stacking duplicates.
        if (fileDetailsDialog == null) {
            fileDetailsDialog = new FileDetailsDialog(this, controller.getCurrentSettings());
        } else {
            fileDetailsDialog.updateSettings(controller.getCurrentSettings());
        }
        fileDetailsDialog.show(file, result);

        logger.debug("File details dialog displayed for: {}", file.path().getFileName());
    }

    /**
     * Handles the "Open File Location" context menu action.
     * Opens the system file manager and navigates to the file's location.
     * 
     * <p>
     * Requirements:
     * - REQ-FL-3.1: Context menu integration
     * - REQ-FL-3.2: Open file location in system file manager
     * - REQ-FL-3.3: Navigate to output path for completed files
     * 
     * Task 61: Implement handleOpenFileLocation handler
     * </p>
     * 
     * @param fileId The ID of the file to open in file manager
     */
    private void handleOpenFileLocation(String fileId) {
        logger.debug("Opening file location for file: {}", fileId);

        try {
            // Get the conversion file from the controller
            Optional<ConversionFile> fileOptional = controller.getFile(fileId);
            if (fileOptional.isEmpty()) {
                logger.warn("File not found for ID: {}", fileId);
                showErrorDialog(I18n.tr("File Not Found"), I18n.tr("Unable to find the selected file."));
                return;
            }

            ConversionFile file = fileOptional.get();

            // Determine which path to open based on file status
            Path pathToOpen = determinePathToOpen(file);

            // Get FileHandler from controller and open the file manager
            org.omc.service.FileHandler fileHandler = controller.getFileHandler();
            fileHandler.openInFileManager(pathToOpen);

            logger.info("Opened file location: {}", pathToOpen);
            showStatus(I18n.tr("Opened file location: ") + pathToOpen.getParent().getFileName());

        } catch (java.nio.file.AccessDeniedException e) {
            // Task 68: Handle permission denied error
            logger.error("Permission denied accessing file location", e);
            showErrorDialog(
                    I18n.tr("Permission Denied"),
                    I18n.tr("You do not have permission to access this location.\n\nPath: ") + e.getFile());
        } catch (java.io.FileNotFoundException e) {
            // Task 66: Handle file not found error
            logger.error("File not found when opening location", e);
            showErrorDialog(
                    I18n.tr("File Not Found"),
                    I18n.tr("The file could not be found. It may have been moved or deleted.\n\n") + e.getMessage());
        } catch (java.io.IOException e) {
            // Task 67: Handle file manager not available error
            logger.error("Failed to open file manager", e);
            showErrorDialog(
                    I18n.tr("Cannot Open File Manager"),
                    I18n.tr("Could not open the file manager. Please ensure xdg-open or a file manager ") +
                            I18n.tr("(Nautilus, Dolphin, Thunar, Nemo, PCManFM) is installed.\n\nError: ") + e.getMessage());
        } catch (Exception e) {
            // Catch-all for unexpected errors
            logger.error("Unexpected error opening file location", e);
            showErrorDialog(
                    I18n.tr("Error"),
                    I18n.tr("An unexpected error occurred while trying to open the file location.\n\n") + e.getMessage());
        }
    }

    /**
     * Determines which path to open in the file manager based on file status.
     * 
     * <p>
     * Logic (Task 62):
     * - PENDING, IN_PROGRESS: Open source path
     * - COMPLETED: Open output path (fallback to source if output not available)
     * - FAILED, CANCELLED: Open source path
     * </p>
     * 
     * <p>
     * Requirements:
     * - REQ-FL-3.2: Navigate to source for pending/in-progress/failed files
     * - REQ-FL-3.3: Navigate to output for completed files
     * </p>
     * 
     * @param file The conversion file
     * @return The path to open in the file manager
     * @throws java.io.FileNotFoundException if neither source nor output path
     *                                       exists
     */
    private Path determinePathToOpen(ConversionFile file) throws java.io.FileNotFoundException {
        Path pathToOpen;

        switch (file.status()) {
            case COMPLETED:
                // For completed files, prefer output path
                pathToOpen = file.outputPath().orElse(file.path());
                logger.debug("Determined path for COMPLETED file: {} (output: {})",
                        pathToOpen, file.outputPath().isPresent());
                break;

            case PENDING:
            case IN_PROGRESS:
            case FAILED:
            case CANCELLED:
            default:
                // For all other statuses, use source path
                pathToOpen = file.path();
                logger.debug("Determined path for {} file: {}", file.status(), pathToOpen);
                break;
        }

        // Verify the path exists
        if (!java.nio.file.Files.exists(pathToOpen)) {
            // If output path doesn't exist but source does, fall back to source
            if (file.status() == ConversionStatus.COMPLETED &&
                    file.outputPath().isPresent() &&
                    java.nio.file.Files.exists(file.path())) {
                logger.warn("Output path does not exist, falling back to source: {}", file.path());
                pathToOpen = file.path();
            } else {
                throw new java.io.FileNotFoundException(
                        I18n.tr("File does not exist: ") + pathToOpen);
            }
        }

        return pathToOpen;
    }

    /**
     * Adds file filters for all supported formats to a file chooser dialog.
     * Requirements: REQ-006.1, REQ-006.2, REQ-006.3, REQ-006.4
     * 
     * @param dialog The file chooser dialog
     */
    @SuppressWarnings("deprecation")
    private void addFileFilters(org.gnome.gtk.FileChooserDialog dialog) {
        // "All Supported Files" filter
        org.gnome.gtk.FileFilter allFilter = new org.gnome.gtk.FileFilter();
        allFilter.setName(I18n.tr("All Supported Files"));
        for (FileFormat format : FileFormat.values()) {
            if (format != FileFormat.UNKNOWN && format.isValidInput()) {
                for (String ext : format.getExtensions()) {
                    allFilter.addPattern("*." + ext);
                }
            }
        }
        dialog.addFilter(allFilter);

        // Category-specific filters
        addCategoryFilter(dialog, FormatCategory.VIDEO, I18n.tr("Video Files"));
        addCategoryFilter(dialog, FormatCategory.AUDIO, I18n.tr("Audio Files"));
        addCategoryFilter(dialog, FormatCategory.IMAGE, I18n.tr("Image Files"));
        addCategoryFilter(dialog, FormatCategory.DOCUMENT, I18n.tr("Document Files"));

        // "All Files" filter
        org.gnome.gtk.FileFilter allFilesFilter = new org.gnome.gtk.FileFilter();
        allFilesFilter.setName(I18n.tr("All Files"));
        allFilesFilter.addPattern("*");
        dialog.addFilter(allFilesFilter);
    }

    /**
     * Adds a file filter for a specific format category.
     * 
     * @param dialog   The file chooser dialog
     * @param category The format category
     * @param name     The filter display name
     */
    @SuppressWarnings("deprecation")
    private void addCategoryFilter(org.gnome.gtk.FileChooserDialog dialog, FormatCategory category, String name) {
        FileFormat[] formats = FileFormat.getFormatsByCategory(category);
        if (formats.length == 0) {
            return;
        }

        org.gnome.gtk.FileFilter filter = new org.gnome.gtk.FileFilter();
        filter.setName(name);
        for (FileFormat format : formats) {
            for (String ext : format.getExtensions()) {
                filter.addPattern("*." + ext);
            }
        }
        dialog.addFilter(filter);
    }

    /**
     * Restores the file list sort state from the controller's saved state.
     * This method must be called AFTER controller.initialize() to ensure
     * the application state has been loaded from disk.
     * 
     * Requirement REQ-FL-4.5: Restore sort state on application startup
     * 
     * @see ApplicationWorkflowController#initialize()
     * @see ApplicationWorkflowController#getSavedSortState()
     */
    public void restoreSortStateFromController() {
        FileListSortState savedSortState = controller.getSavedSortState();
        if (savedSortState != null && savedSortState.isSorted()) {
            logger.debug("Restoring saved sort state: {}", savedSortState);
            fileListView.restoreSortState(savedSortState);
        } else {
            logger.debug("No sort state to restore (null or unsorted)");
        }
    }
}
