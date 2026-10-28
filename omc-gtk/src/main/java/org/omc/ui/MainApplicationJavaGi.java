package org.omc.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.omc.controller.ApplicationWorkflowController;
import org.omc.core.DependencyFactory;
import org.omc.ui.logging.LoggingConfiguration;

/**
 * Main GTK Application class for Open Media Converter.
 * Handles application lifecycle, window creation, dependency injection, and
 * command-line argument parsing.
 * 
 * Requirements:
 * - REQ-001.1: Application initialization and startup with command-line
 * argument support
 * - REQ-102.1: GTK UI framework integration
 */
public class MainApplicationJavaGi extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainApplicationJavaGi.class);
    private static final String VERSION = MainApplicationJavaGi.class.getPackage().getImplementationVersion() == null
            ? "0.1.2" : MainApplicationJavaGi.class.getPackage().getImplementationVersion();

    // Application components
    private MainWindowJavaGi mainWindow;
    private ApplicationWorkflowController controller;
    private DependencyFactory dependencyFactory;

    // Command-line argument state
    private List<String> filesToPreload = new ArrayList<>();
    private String customConfigDir = null;
    private boolean debugMode = false;
    private boolean settingsRequested;

    /**
     * Creates the main GTK application.
     * Uses a unique application ID to prevent multiple instances.
     */
    public MainApplicationJavaGi() {
        super("org.omc.OpenMediaConverter", ApplicationFlags.HANDLES_COMMAND_LINE);
        logger.debug("MainApplicationJavaGi created");

        // Connect the activate signal - this is required for GTK to trigger activation
        // Without this, the application exits immediately with the GLib-GIO warning
        this.onActivate(() -> this.activate());
        this.onCommandLine(commandLine -> {
            int status = 1;
            try {
                String[] arguments = commandLine.getArguments();
                filesToPreload.clear();
                settingsRequested = false;
                Path callerDirectory = commandLine.getCwd() == null ? Path.of("").toAbsolutePath()
                        : Path.of(commandLine.getCwd());
                parseCommandLineArguments(Arrays.copyOfRange(arguments, Math.min(1, arguments.length), arguments.length),
                        callerDirectory);
                // Emit the native signal so all activation handlers run in the
                // primary instance, including on subsequent desktop launches.
                emitActivate();
                preloadFiles();
                if (settingsRequested && mainWindow != null) mainWindow.triggerSettings();
                status = 0;
                return status;
            } catch (IllegalArgumentException e) {
                commandLine.printerrLiteral(e.getMessage() + "\n");
                return status;
            } finally {
                // Java retains the native wrapper until GC. Gio must receive
                // completion now so the invoking process can exit immediately.
                commandLine.setExitStatus(status);
                if (org.gnome.glib.GLib.checkVersion(2, 80, 0) == null) commandLine.done();
                else commandLine.runDispose(); // Older Gio completes the reply on disposal.
            }
        });

        // Register application actions
        setupActions();

        // Set application icon for desktop integration
        // Requirement REQ-102.1: Application icon
        setupApplicationIcon();
    }

    /**
     * Sets up the application icon for desktop integration.
     * This icon will be used in the desktop environment (taskbar, alt-tab, etc.)
     * 
     * Requirements: REQ-102.1 - Application icon for desktop integration
     */
    private void setupApplicationIcon() {
        try {
            // Set the default icon name for the application
            // GTK will look for this icon in the system icon theme paths
            // Our icons are packaged in src/main/resources/icons/hicolor/
            org.gnome.gtk.Window.setDefaultIconName("open-media-converter");
            logger.debug("Application default icon set to 'open-media-converter'");
        } catch (Exception e) {
            logger.warn("Failed to set application icon", e);
        }
    }

    /**
     * Sets up application-level actions (about, quit, etc.) and keyboard shortcuts.
     * 
     * Requirements:
     * - REQ-102.1: Register app.about action for About dialog
     * - REQ-102.2: Keyboard navigation with standard shortcuts
     */
    private void setupActions() {
        // About action
        org.gnome.gio.SimpleAction aboutAction = new org.gnome.gio.SimpleAction("about", null);
        aboutAction.onActivate(param -> {
            logger.debug("About action activated");
            if (mainWindow != null) {
                AboutDialogHelper.show(mainWindow);
            }
        });
        addAction(aboutAction);
        logger.debug("Registered app.about action");

        // Help action (referenced by the app menu's Help item in
        // main_window.ui). Opens the About dialog, which carries version,
        // description and link information until a dedicated help view exists.
        org.gnome.gio.SimpleAction helpAction = new org.gnome.gio.SimpleAction("help", null);
        helpAction.onActivate(param -> {
            logger.debug("Help action activated");
            if (mainWindow != null) {
                AboutDialogHelper.show(mainWindow);
            }
        });
        addAction(helpAction);
        logger.debug("Registered app.help action");

        // Quit action (Ctrl+Q)
        // Requirement REQ-102.2: Ctrl+Q keyboard shortcut
        org.gnome.gio.SimpleAction quitAction = new org.gnome.gio.SimpleAction("quit", null);
        quitAction.onActivate(param -> {
            logger.debug("Quit action activated");
            if (mainWindow != null) {
                mainWindow.close();
            }
        });
        addAction(quitAction);
        setAccelsForAction("app.quit", new String[] { "<Primary>q" });
        logger.debug("Registered app.quit action with Ctrl+Q shortcut");

        // Add Files action (Ctrl+O)
        // Requirement REQ-102.2: Ctrl+O keyboard shortcut
        org.gnome.gio.SimpleAction addFilesAction = new org.gnome.gio.SimpleAction("add-files", null);
        addFilesAction.onActivate(param -> {
            logger.debug("Add Files action activated");
            if (mainWindow != null) {
                mainWindow.triggerAddFiles();
            }
        });
        addAction(addFilesAction);
        setAccelsForAction("app.add-files", new String[] { "<Primary>o" });
        logger.debug("Registered app.add-files action with Ctrl+O shortcut");

        // Settings action (Ctrl+Comma)
        // Requirement REQ-102.2: Ctrl+, keyboard shortcut
        org.gnome.gio.SimpleAction settingsAction = new org.gnome.gio.SimpleAction("settings", null);
        settingsAction.onActivate(param -> {
            logger.debug("Settings action activated");
            if (mainWindow != null) {
                mainWindow.triggerSettings();
            }
        });
        addAction(settingsAction);
        setAccelsForAction("app.settings", new String[] { "<Primary>comma" });
        logger.debug("Registered app.settings action with Ctrl+, shortcut");

        // Convert action (Ctrl+Return)
        // Requirement REQ-102.2: Ctrl+Return keyboard shortcut
        org.gnome.gio.SimpleAction convertAction = new org.gnome.gio.SimpleAction("convert", null);
        convertAction.onActivate(param -> {
            logger.debug("Convert action activated");
            if (mainWindow != null) {
                mainWindow.triggerConvert();
            }
        });
        addAction(convertAction);
        setAccelsForAction("app.convert", new String[] { "<Primary>Return" });
        logger.debug("Registered app.convert action with Ctrl+Return shortcut");
    }

    /**
     * Called when the application is activated (started).
     * Creates the main window and initializes the application workflow.
     * 
     * Requirement REQ-001.1: Application initialization
     */
    @Override
    public void activate() {
        if (mainWindow != null) {
            mainWindow.present();
            return;
        }
        logger.info("Application activated, initializing...");

        try {
            // Create dependency factory and initialize all dependencies
            initializeDependencies();

            // Create main window
            mainWindow = new MainWindowJavaGi(this, controller);

            // Initialize controller (loads settings and state from disk)
            controller.initialize();

            // Restore sort state AFTER controller initialization
            // Requirement REQ-FL-4.5: Restore file list sort state from persisted state
            // This must happen after controller.initialize() because that's when state is
            // loaded
            mainWindow.restoreSortStateFromController();
            mainWindow.restoreState(controller.getWindowState());

            // Wire progress and completion callbacks from controller to UI
            // Requirement REQ-004.2, Task 74: Progress event flow
            controller.registerProgressCallback((fileId, progress) -> {
                mainWindow.updateFileProgress(fileId, progress);
            });

            controller.registerCompletionCallback((fileId, result) -> {
                mainWindow.updateFileResult(fileId, result);
            });

            // Wire batch progress callback from controller to UI
            // Requirement REQ-004.3: Batch progress tracking with speed and ETA
            controller.registerBatchProgressCallback(batchProgress -> {
                mainWindow.updateBatchProgress(batchProgress);
            });

            logger.debug("Progress, completion, and batch progress callbacks wired from controller to UI");

            // Preload files from command-line if any
            // Requirement REQ-001.1: Pre-load files from command-line arguments
            preloadFiles();

            // Update file list UI to reflect restored/preloaded files
            // This also sets the initial button states (Convert/Clear All disabled if no
            // files)
            mainWindow.updateFileList();
            mainWindow.showAvailableCategories();

            // Show the main window
            mainWindow.present();

            logger.info("Application initialization complete");

        } catch (Exception e) {
            logger.error("Failed to initialize application", e);
            System.err.println("Failed to initialize application: " + e.getMessage());
            System.exit(1);
        }
    }

    private void preloadFiles() {
        if (mainWindow == null || filesToPreload.isEmpty()) return;
        List<Path> paths = filesToPreload.stream().map(Paths::get).toList();
        filesToPreload.clear();
        mainWindow.addFilesAsync(paths);
    }

    /**
     * Initializes all application dependencies using DependencyFactory.
     * Replaced manual dependency injection with factory pattern (Task 72).
     */
    private void initializeDependencies() {
        logger.debug("Initializing application dependencies via DependencyFactory");

        Path customConfigPath = customConfigDir != null ? Paths.get(customConfigDir) : null;
        dependencyFactory = new DependencyFactory(customConfigPath);
        controller = dependencyFactory.createApplicationController();

        logger.debug("Dependencies initialized successfully");
    }

    /**
     * Gets the main window instance.
     * 
     * @return the main window
     */
    public MainWindowJavaGi getMainWindow() {
        return mainWindow;
    }

    /**
     * Gets the application workflow controller.
     * 
     * @return the controller
     */
    public ApplicationWorkflowController getController() {
        return controller;
    }

    /**
     * Gets the dependency factory.
     * 
     * @return the dependency factory
     */
    public DependencyFactory getDependencyFactory() {
        return dependencyFactory;
    }

    /**
     * Called when the application is shutting down.
     * Cleans up resources and persists state.
     * 
     * Requirement REQ-001.2: Application shutdown
     */
    @Override
    public void shutdown() {
        logger.info("Application shutdown initiated");

        try {
            // Shutdown controller (saves state, stops conversions)
            if (controller != null) {
                controller.shutdown();
            }

            // Shutdown dependency factory (cleans up resources)
            if (dependencyFactory != null) {
                dependencyFactory.shutdown();
            }

            logger.info("Application shutdown complete");

        } catch (Exception e) {
            logger.error("Error during application shutdown", e);
        } finally {
            // Call parent shutdown
            super.shutdown();
        }
    }

    /**
     * Main entry point for the application.
     * Parses command-line arguments and launches the GTK application.
     * 
     * Requirement REQ-001.1: Command-line argument support
     * 
     * @param args command line arguments
     */
    public static void main(String[] args) {
        int exitCode = 1;
        try {
            LoggingConfiguration.initialize();
            if (Arrays.asList(args).contains("--debug")) {
                LoggingConfiguration.enableDebugLogging();
            }
            exitCode = runApplication(args);
        } catch (RuntimeException | Error failure) {
            logger.error("Application terminated unexpectedly", failure);
        } finally {
            // Stop appenders and finish compression before System.exit. The
            // JVM hook also covers exit paths outside the normal GTK lifecycle.
            LoggingConfiguration.shutdown();
        }
        System.exit(exitCode);
    }

    private static int runApplication(String[] args) {
        logger.info("Starting Open Media Converter");

        // Parse command-line arguments before GTK initialization
        // Requirement REQ-001.1: Command-line argument parsing
        if (args.length > 0) {
            // Check for help flag first
            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsage();
                    return 0;
                }
            }

            // Check for version flag
            for (String arg : args) {
                if ("--version".equals(arg) || "-v".equals(arg)) {
                    printVersion();
                    return 0;
                }
            }
        }

        // Create and run the GTK application
        MainApplicationJavaGi app = new MainApplicationJavaGi();

        // Gio forwards arguments and the caller's working directory to the
        // existing primary instance. argv[0] is the application name.
        String[] applicationArguments = new String[args.length + 1];
        applicationArguments[0] = "open-media-converter";
        System.arraycopy(args, 0, applicationArguments, 1, args.length);
        int exitCode = app.run(applicationArguments);

        logger.info("Application exited with code: {}", exitCode);
        return exitCode;
    }

    /**
     * Parses command-line arguments and stores configuration.
     * 
     * Supported arguments:
     * - --help / -h: Show usage and exit
     * - --version / -v: Show version and exit
     * - --debug: Enable debug logging
     * - --config-dir <path>: Override config directory
     * - <file paths>: Files to preload
     * 
     * Requirement REQ-001.1: Command-line argument parsing
     * 
     * @param args command-line arguments
     */
    private void parseCommandLineArguments(String[] args) {
        parseCommandLineArguments(args, Path.of("").toAbsolutePath());
    }

    private void parseCommandLineArguments(String[] args, Path callerDirectory) {
        logger.debug("Parsing command-line arguments: {}", (Object) args);

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("--settings".equals(arg)) {
                settingsRequested = true;
                continue;
            }

            // Skip help and version (already handled in main)
            if ("--help".equals(arg) || "-h".equals(arg) ||
                    "--version".equals(arg) || "-v".equals(arg)) {
                continue;
            }

            // Debug flag
            // Requirement REQ-001.1: Enable debug logging via --debug flag
            if ("--debug".equals(arg)) {
                debugMode = true;
                enableDebugLogging();
                logger.info("Debug logging enabled via command-line flag");
                continue;
            }

            // Config directory override
            // Requirement REQ-001.1: Override config directory via --config-dir
            if ("--config-dir".equals(arg)) {
                if (i + 1 < args.length) {
                    String requestedConfig = args[++i];
                    if (mainWindow != null) {
                        logger.warn("Configuration directory cannot change while the application is running");
                        continue;
                    }
                    customConfigDir = callerDirectory.resolve(requestedConfig).normalize().toString();
                    logger.info("Custom config directory set: {}", customConfigDir);

                    // Validate config directory
                    Path configPath = Paths.get(customConfigDir);
                    if (!Files.exists(configPath)) {
                        logger.warn("Custom config directory does not exist, will be created: {}", customConfigDir);
                    } else if (!Files.isDirectory(configPath)) {
                        logger.error("Custom config path is not a directory: {}", customConfigDir);
                        System.err.println("Error: --config-dir path is not a directory: " + customConfigDir);
                        throw new IllegalArgumentException("--config-dir path is not a directory: " + customConfigDir);
                    }
                } else {
                    logger.error("--config-dir flag requires a directory path argument");
                    System.err.println("Error: --config-dir requires a directory path argument");
                    printUsage();
                    throw new IllegalArgumentException("--config-dir requires a directory path argument");
                }
                continue;
            }

            // Unknown flag starting with --
            if (arg.startsWith("--")) {
                logger.warn("Unknown command-line flag: {}", arg);
                System.err.println("Warning: Unknown flag: " + arg);
                continue;
            }

            // Treat as file path
            // Requirement REQ-001.1: Validate and preload file paths
            Path filePath = callerDirectory.resolve(arg).normalize();
            if (Files.exists(filePath)) {
                if (Files.isRegularFile(filePath) && Files.isReadable(filePath)) {
                    filesToPreload.add(filePath.toString());
                    logger.debug("Added file to preload list: {}", arg);
                } else if (Files.isDirectory(filePath)) {
                    logger.warn("Ignoring directory argument (use Add Folder in UI): {}", arg);
                    System.err.println(
                            "Warning: Directories not supported via command-line, use Add Folder in UI: " + arg);
                } else {
                    logger.warn("File not readable: {}", arg);
                    System.err.println("Warning: File not readable: " + arg);
                }
            } else {
                logger.warn("File not found: {}", arg);
                System.err.println("Warning: File not found: " + arg);
            }
        }

        logger.info("Command-line parsing complete. Debug: {}, ConfigDir: {}, FilesToPreload: {}",
                debugMode, customConfigDir, filesToPreload.size());
    }

    /**
     * Enables debug-level logging for the application.
     * Changes the root logger level to DEBUG.
     * 
     * Requirement REQ-001.1: Debug logging support
     */
    private void enableDebugLogging() {
        LoggingConfiguration.enableDebugLogging();
        logger.debug("Root logger level set to DEBUG");
    }

    /**
     * Prints application usage information to stdout.
     * 
     * Requirement REQ-001.1: --help flag support
     */
    private static void printUsage() {
        System.out.println("Open Media Converter - Media and Document Conversion Tool");
        System.out.println();
        System.out.println("Usage: open-media-converter [OPTIONS] [FILES...]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help, -h              Show this help message and exit");
        System.out.println("  --settings              Open the Settings dialog");
        System.out.println("  --version, -v           Show version information and exit");
        System.out.println("  --debug                 Enable debug logging");
        System.out.println(
                "  --config-dir <path>     Override config directory (default: ~/.config/open-media-converter)");
        System.out.println();
        System.out.println("Files:");
        System.out.println("  FILE...                 One or more file paths to preload into the application");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  open-media-converter                          # Launch with default settings");
        System.out.println("  open-media-converter video.mp4 audio.wav      # Launch with preloaded files");
        System.out.println("  open-media-converter --debug video.mp4        # Launch in debug mode with file");
        System.out.println("  open-media-converter --config-dir /tmp/config # Use custom config directory");
        System.out.println();
    }

    /**
     * Prints application version information to stdout.
     * 
     * Requirement REQ-001.1: --version flag support
     */
    private static void printVersion() {
        System.out.println("Open Media Converter " + VERSION);
        System.out.println("Built with Java " + System.getProperty("java.version"));
        System.out.println("GTK 4 via java-gi bindings");
        System.out.println();
        System.out.println("Copyright (C) 2025 Open Media Converter Contributors");
        System.out.println("This is free software; see the source for copying conditions.");
        System.out.println();
    }
}
