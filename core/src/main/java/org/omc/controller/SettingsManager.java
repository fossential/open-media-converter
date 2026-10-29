package org.omc.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.omc.core.ConfigurationManager;
import org.omc.core.ValidationEngine;
import org.omc.exception.InvalidSettingsException;
import org.omc.model.AudioSettings;
import org.omc.model.ConversionSettings;
import org.omc.model.DocumentSettings;
import org.omc.model.FileFormat;
import org.omc.model.FormatCategory;
import org.omc.model.ImageSettings;
import org.omc.model.PresetsBySection;
import org.omc.model.SectionPreset;
import org.omc.model.SettingsPreset;
import org.omc.model.ValidationResult;
import org.omc.model.VideoSettings;
import org.omc.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Manages application settings and preset persistence and retrieval.
 * 
 * <p>
 * <b>Settings Management:</b>
 * </p>
 * <p>
 * Handles loading, saving, validation, and defaults for conversion settings.
 * Implements atomic writes with temporary files to prevent corruption and
 * provides
 * automatic backup and recovery for corrupted settings files.
 * </p>
 * 
 * <p>
 * <b>Preset Management:</b>
 * </p>
 * <p>
 * Manages section-based presets organized by format category (video, audio,
 * image,
 * document). Handles automatic migration from old preset format to new
 * {@link PresetsBySection}
 * structure with backup of old files.
 * </p>
 * 
 * <p>
 * <b>Backward Compatibility:</b>
 * </p>
 * <p>
 * Automatically migrates settings and presets from older formats:
 * <ul>
 * <li>Old global {@code outputFormat} → Section-based format settings</li>
 * <li>Old {@code List<SettingsPreset>} → {@link PresetsBySection}
 * structure</li>
 * <li>Creates timestamped backups of old files during migration</li>
 * </ul>
 * </p>
 * 
 * <p>
 * Requirements:
 * </p>
 * <ul>
 * <li>REQ-003.1: Settings management and persistence</li>
 * <li>REQ-002.6: Preset creation and management</li>
 * <li>REQ-002.7: Section-based preset organization</li>
 * <li>REQ-005.3: Atomic file operations for data integrity</li>
 * <li>REQ-5.1: Backward compatibility with old formats</li>
 * </ul>
 * 
 * @see ConversionSettings
 * @see PresetsBySection
 * @see SectionPreset
 */
public class SettingsManager {
    private static final Logger logger = LoggerFactory.getLogger(SettingsManager.class);

    private static final String BACKUP_SUFFIX = ".backup";
    private static final String TEMP_SUFFIX = ".tmp";

    private final ConfigurationManager configurationManager;
    private final ValidationEngine validationEngine;
    private final AtomicReference<ConversionSettings> currentSettings;

    /**
     * Memoized {@link #loadPresetsBySection()} result, guarded by file mtime
     * and size so external edits and deletions still invalidate. Context-menu
     * popups and settings-open call the loader on the GTK thread; without
     * caching each call re-reads and re-parses presets.json.
     */
    private final Object presetsCacheLock = new Object();
    private PresetsBySection cachedPresetsBySection;
    private long cachedPresetsModifiedNanos = Long.MIN_VALUE;
    private long cachedPresetsSize = -1;
    private boolean cachedPresetsFileMissing;

    /**
     * Creates a new SettingsManager.
     *
     * @param configurationManager Configuration manager for paths
     * @param validationEngine     Validation engine for settings validation
     */
    public SettingsManager(ConfigurationManager configurationManager, ValidationEngine validationEngine) {
        this.configurationManager = Objects.requireNonNull(configurationManager, "configurationManager cannot be null");
        this.validationEngine = Objects.requireNonNull(validationEngine, "validationEngine cannot be null");
        this.currentSettings = new AtomicReference<>();
        logger.debug("SettingsManager initialized");
    }

    /**
     * Loads settings from disk.
     * If the settings file doesn't exist or is corrupted, returns default settings.
     * Corrupted files are backed up with timestamp.
     *
     * <p>
     * <b>Corruption recovery:</b> when the file cannot be bound to
     * {@link ConversionSettings} or fails validation, valid per-section
     * subtrees (video/audio/image/document) and valid global fields are
     * salvaged from the JSON tree; only the corrupt or invalid parts are reset
     * to defaults. Files that are not parseable JSON at all are fully reset to
     * defaults (after backup).
     * </p>
     *
     * Requirement REQ-005.3: Settings persistence
     *
     * @return Loaded, salvaged or default settings
     */
    public ConversionSettings loadSettings() {
        Path settingsPath = configurationManager.getSettingsFilePath();
        logger.info("Loading settings from: {}", settingsPath);

        // If settings file doesn't exist, use defaults
        if (!Files.exists(settingsPath)) {
            logger.info("Settings file does not exist, using defaults");
            ConversionSettings defaults = createDefaultSettings();
            currentSettings.set(defaults);
            return defaults;
        }

        try {
            // Read settings from file
            JsonNode settingsTree = JsonUtils.getObjectMapper().readTree(settingsPath.toFile());
            if (settingsTree instanceof ObjectNode object) {
                // Reuse preset migration before the new model can discard the
                // legacy field. Reading never rewrites the user's original file.
                var wrapper = JsonUtils.getObjectMapper().createObjectNode();
                wrapper.set("settings", object);
                mapLegacyGlobalOutputFormats(JsonUtils.getObjectMapper().createArrayNode().add(wrapper));
            }
            ConversionSettings settings = JsonUtils.getObjectMapper().treeToValue(
                    settingsTree, ConversionSettings.class);

            // Validate loaded settings
            if (settings == null) {
                logger.warn("Settings file is empty or null, using defaults");
                return recoverCorruptedSettings(settingsPath);
            }

            settings = settings.withDefaults();

            // Structural validation only: transient filesystem state (offline
            // template, missing output directory) is not corruption
            if (!settings.isStructurallyValid()) {
                logger.warn("Loaded settings are invalid, salvaging valid sections");
                return recoverCorruptedSettings(settingsPath);
            }

            // Additional validation using ValidationEngine
            ValidationResult validationResult = validationEngine.validateSettings(settings);
            if (validationResult.isFailure()) {
                List<String> persistentErrors = validationResult.getErrors().stream()
                        .filter(error -> !isTransientFileSystemError(error))
                        .toList();
                if (persistentErrors.isEmpty()) {
                    logger.warn("Keeping settings despite transient filesystem state: {}",
                            validationResult.getErrors());
                } else {
                    logger.warn("Settings validation failed: {}", persistentErrors);
                    return recoverCorruptedSettings(settingsPath);
                }
            }

            if (validationResult.hasWarnings()) {
                logger.warn("Settings loaded with warnings: {}", validationResult.getWarnings());
            }

            currentSettings.set(settings);
            logger.info("Settings loaded successfully");
            return settings;

        } catch (IOException e) {
            logger.error("Error reading settings file: {}", settingsPath, e);
            return recoverCorruptedSettings(settingsPath);
        }
    }

    /**
     * Recovers a settings file that could not be loaded normally.
     *
     * <p>
     * The file is parsed into a JSON tree; valid section subtrees and global
     * fields are salvaged and only the corrupt parts are reset to defaults.
     * The original file is always backed up first (same behaviour as a full
     * reset). Files that are not parseable JSON keep the historical
     * full-reset behaviour.
     * </p>
     *
     * @param settingsPath path to the settings file
     * @return salvaged settings, or full defaults when nothing is recoverable
     */
    private ConversionSettings recoverCorruptedSettings(Path settingsPath) {
        JsonNode root;
        try {
            root = JsonUtils.getObjectMapper().readTree(settingsPath.toFile());
        } catch (IOException e) {
            logger.warn("Settings file is not parseable JSON; resetting to defaults", e);
            return resetSettingsToDefaults(settingsPath);
        }

        if (root == null || !root.isObject()) {
            logger.warn("Settings file is not a JSON object; resetting to defaults");
            return resetSettingsToDefaults(settingsPath);
        }

        // Keep the historical backup behaviour before using salvaged data
        backupCorruptedSettings(settingsPath);

        ConversionSettings salvaged = salvageSettings(root);
        if (salvaged == null) {
            salvaged = createDefaultSettings();
        }

        currentSettings.set(salvaged);
        logger.info("Settings recovered after corruption; invalid parts were reset to defaults");
        return salvaged;
    }

    /**
     * Full-reset recovery: backs the file up and returns defaults.
     */
    private ConversionSettings resetSettingsToDefaults(Path settingsPath) {
        backupCorruptedSettings(settingsPath);
        ConversionSettings defaults = createDefaultSettings();
        currentSettings.set(defaults);
        return defaults;
    }

    /**
     * Builds settings from a JSON tree, keeping every subtree that binds and
     * validates, and taking defaults for the rest.
     *
     * @param root object node of the settings file
     * @return candidate settings, or null if even the salvaged result is invalid
     */
    private ConversionSettings salvageSettings(JsonNode root) {
        ConversionSettings defaults = createDefaultSettings();
        ConversionSettings.Builder builder = ConversionSettings.builder()
                .outputDirectory(defaults.outputDirectory())
                .overwriteExisting(defaults.overwriteExisting())
                .createSubdirectory(defaults.createSubdirectory())
                .parallelConversions(defaults.parallelConversions())
                .deleteOriginalFile(defaults.deleteOriginalFile());

        // Global scalar fields, salvaged one by one
        JsonNode node = root.get("language");
        if (node != null && node.isTextual()) {
            builder.language(node.asText());
        }
        node = root.get("parallelConversions");
        if (node != null && node.isInt()
                && node.asInt() >= org.omc.core.ValidationEngine.MIN_PARALLEL_CONVERSIONS
                && node.asInt() <= org.omc.core.ValidationEngine.MAX_PARALLEL_CONVERSIONS) {
            builder.parallelConversions(node.asInt());
        }
        node = root.get("overwriteExisting");
        if (node != null && node.isBoolean()) {
            builder.overwriteExisting(node.asBoolean());
        }
        node = root.get("createSubdirectory");
        if (node != null && node.isBoolean()) {
            builder.createSubdirectory(node.asBoolean());
        }
        node = root.get("deleteOriginalFile");
        if (node != null && node.isBoolean()) {
            builder.deleteOriginalFile(node.asBoolean());
        }
        node = root.get("outputDirectory");
        if (node != null && node.isTextual()) {
            try {
                // Use the normal Path deserializer: Jackson persists paths as file: URIs,
                // while older settings may contain plain filesystem paths.
                Path directory = JsonUtils.getObjectMapper().treeToValue(node, Path.class);
                if (Files.isDirectory(directory)) {
                    builder.outputDirectory(directory);
                } else {
                    logger.warn("Discarding non-existent outputDirectory during salvage: {}", directory);
                }
            } catch (IOException | InvalidPathException e) {
                logger.warn("Discarding malformed outputDirectory during salvage: {}", node.asText());
            }
        }

        // Section subtrees, salvaged independently of each other
        builder.videoSettings(salvageSection(
                root.get("videoSettings"), VideoSettings.class, VideoSettings::isValid, "videoSettings"));
        builder.audioSettings(salvageSection(
                root.get("audioSettings"), AudioSettings.class, AudioSettings::isValid, "audioSettings"));
        builder.imageSettings(salvageSection(
                root.get("imageSettings"), ImageSettings.class, ImageSettings::isValid, "imageSettings"));
        builder.documentSettings(salvageSection(
                root.get("documentSettings"), DocumentSettings.class, DocumentSettings::isStructurallyValid,
                "documentSettings"));

        ConversionSettings candidate = builder.build().withDefaults();
        if (!candidate.isStructurallyValid()) {
            logger.warn("Salvaged settings still invalid; falling back to full defaults");
            return null;
        }
        ValidationResult candidateValidation = validationEngine.validateSettings(candidate);
        if (candidateValidation.isFailure()
                && candidateValidation.getErrors().stream().anyMatch(error -> !isTransientFileSystemError(error))) {
            logger.warn("Salvaged settings still invalid; falling back to full defaults");
            return null;
        }
        return candidate;
    }

    /**
     * Whether a ValidationEngine error reports transient filesystem state
     * (missing/unwritable output directory) rather than corrupt settings data.
     */
    private static boolean isTransientFileSystemError(String error) {
        return error != null && error.startsWith("Output directory");
    }

    /**
     * Converts one section subtree to its model type, rejecting it on any
     * binding or validation problem so only that section is reset.
     *
     * @param node      the section subtree, may be null or absent
     * @param type      the section model class
     * @param isValid   validator for the bound section value
     * @param fieldName JSON field name, used for logging
     * @param <T>       section model type
     * @return the salvaged section, or null when it must be reset
     */
    private <T> T salvageSection(JsonNode node, Class<T> type, Predicate<T> isValid, String fieldName) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }

        try {
            T value = JsonUtils.getObjectMapper().treeToValue(node, type);
            if (value == null || !isValid.test(value)) {
                logger.warn("Discarding invalid '{}' section during settings salvage", fieldName);
                return null;
            }
            return value;
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Discarding corrupt '{}' section during settings salvage: {}",
                    fieldName, e.getMessage());
            return null;
        }
    }

    /**
     * Saves settings to disk using atomic write operation.
     * Writes to temporary file first, then renames to prevent corruption.
     *
     * Requirement REQ-005.3: Atomic settings persistence
     *
     * @param settings Settings to save
     * @throws InvalidSettingsException if settings are invalid
     * @throws IOException              if save operation fails
     */
    public synchronized void saveSettings(ConversionSettings settings) throws InvalidSettingsException, IOException {
        Objects.requireNonNull(settings, "settings cannot be null");
        logger.debug("Saving settings");

        // Validate settings before saving
        ValidationResult validationResult = validationEngine.validateSettings(settings);
        if (validationResult.isFailure()) {
            String errorMessage = "Cannot save invalid settings: " +
                    String.join(", ", validationResult.getErrors());
            logger.error(errorMessage);
            throw new InvalidSettingsException(errorMessage, "settings");
        }

        if (validationResult.hasWarnings()) {
            logger.warn("Saving settings with warnings: {}", validationResult.getWarnings());
        }

        Path settingsPath = configurationManager.getSettingsFilePath();
        Path tempPath = Path.of(settingsPath.toString() + TEMP_SUFFIX);

        try {
            // Write to temporary file
            JsonUtils.writeJsonFile(settings, tempPath.toFile());
            logger.debug("Settings written to temporary file: {}", tempPath);

            // Atomically rename temporary file to final location
            // (falls back to a plain move where the filesystem does not
            // support atomic moves)
            StateManager.moveWithAtomicFallback(tempPath, settingsPath);
            logger.info("Settings saved successfully to: {}", settingsPath);

            // Update current settings
            currentSettings.set(settings);

        } catch (IOException e) {
            logger.error("Error saving settings to: {}", settingsPath, e);
            throw e;
        } finally {
            // Clean up the temp file whether the move failed or never happened;
            // after a successful move this is a no-op because the file is gone.
            StateManager.deleteQuietly(tempPath);
        }
    }

    /**
     * Gets the current settings.
     * If settings have not been loaded, loads them first.
     * 
     * Requirement REQ-003.1: Settings access
     *
     * @return Current settings
     */
    public ConversionSettings getCurrentSettings() {
        ConversionSettings current = currentSettings.get();
        if (current == null) {
            current = loadSettings();
        }
        return current;
    }

    /**
     * Updates the current settings with validation.
     * Validates settings before updating and saving.
     * 
     * Requirement REQ-003.1: Settings management
     *
     * @param settings New settings
     * @throws InvalidSettingsException if settings are invalid
     * @throws IOException              if save operation fails
     */
    public void updateSettings(ConversionSettings settings) throws InvalidSettingsException, IOException {
        Objects.requireNonNull(settings, "settings cannot be null");
        logger.debug("Updating settings");

        // Save settings (includes validation)
        saveSettings(settings);

        // Update current settings reference
        currentSettings.set(settings);
        logger.info("Settings updated successfully");
    }

    /**
     * Resets settings to factory defaults.
     * Creates default settings and saves them to disk.
     * 
     * Requirement REQ-003.1: Settings reset
     *
     * @throws IOException if save operation fails
     */
    public void resetToDefaults() throws IOException {
        logger.info("Resetting settings to defaults");

        ConversionSettings defaults = createDefaultSettings();

        try {
            saveSettings(defaults);
            currentSettings.set(defaults);
            logger.info("Settings reset to defaults successfully");
        } catch (InvalidSettingsException e) {
            // This should never happen with default settings
            logger.error("Default settings are invalid - this is a bug", e);
            throw new IllegalStateException("Default settings are invalid", e);
        }
    }

    /**
     * Creates default settings with sensible values.
     * Ensures the default output directory exists.
     * 
     * Requirement REQ-003.1: Default settings
     *
     * @return Default settings
     */
    public static ConversionSettings createDefaultSettings() {
        // Use user's home directory as default output directory
        Path defaultOutputDir = Path.of(System.getProperty("user.home"), "Converted");

        try {
            Files.createDirectories(defaultOutputDir);
        } catch (IOException e) {
            // If we can't create the default directory, use a fallback in temp
            defaultOutputDir = Path.of(System.getProperty("java.io.tmpdir"), "Converted");
            try {
                Files.createDirectories(defaultOutputDir);
            } catch (IOException ex) {
                // Last resort: use home directory directly
                defaultOutputDir = Path.of(System.getProperty("user.home"));
            }
        }

        return ConversionSettings.builder()
                .outputFormat(FileFormat.MP4) // Default to MP4 for video conversions
                .outputDirectory(defaultOutputDir)
                .overwriteExisting(false)
                .createSubdirectory(false)
                .parallelConversions(4)
                .build().withDefaults();
    }

    /**
     * Backs up a corrupted settings file.
     * Renames the file with .backup suffix and timestamp.
     *
     * <p>
     * Uses the shared sub-second backup timestamp pattern from
     * {@link StateManager} so state and settings backups share one naming
     * scheme and cannot collide within the same second.
     * </p>
     *
     * Requirement REQ-005.3: Corrupted file handling
     *
     * @param settingsPath Path to corrupted settings file
     */
    private void backupCorruptedSettings(Path settingsPath) {
        try {
            if (Files.exists(settingsPath)) {
                String timestamp = LocalDateTime.now().format(StateManager.BACKUP_TIMESTAMP_FORMATTER);
                Path backupPath = Path.of(settingsPath.toString() + BACKUP_SUFFIX + "_" + timestamp);

                Files.move(settingsPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                logger.warn("Corrupted settings file backed up to: {}", backupPath);
            }
        } catch (IOException e) {
            logger.error("Failed to backup corrupted settings file: {}", settingsPath, e);
        }
    }

    /**
     * Checks if settings file exists.
     *
     * @return true if settings file exists
     */
    public boolean settingsFileExists() {
        return Files.exists(configurationManager.getSettingsFilePath());
    }

    /**
     * Gets the settings file path.
     *
     * @return Settings file path
     */
    public Path getSettingsFilePath() {
        return configurationManager.getSettingsFilePath();
    }

    // ========== Preset Management ==========
    // Requirement REQ-003.2: Format presets

    /**
     * Gets all available presets (built-in and custom).
     * Loads custom presets from presets.json and combines with built-in presets.
     *
     * @return List of all presets
     */
    public List<SettingsPreset> getPresets() {
        logger.debug("Loading presets");

        List<SettingsPreset> allPresets = new ArrayList<>();

        // Add built-in presets first
        List<SettingsPreset> builtInPresets = createBuiltInPresets();
        allPresets.addAll(builtInPresets);

        // Load custom presets from file
        Path presetsPath = configurationManager.getConfigDirectory().resolve("presets.json");
        if (Files.exists(presetsPath)) {
            try {
                PresetContainer container = JsonUtils.readJsonFile(presetsPath.toFile(), PresetContainer.class);
                if (container != null && container.presets != null) {
                    allPresets.addAll(container.presets);
                    logger.debug("Loaded {} custom presets", container.presets.size());
                }
            } catch (IOException e) {
                logger.warn("Failed to load custom presets, using built-in only", e);
            }
        }

        logger.info("Loaded {} total presets ({} built-in)", allPresets.size(), builtInPresets.size());
        return allPresets;
    }

    /**
     * Gets a preset by name.
     *
     * @param name Preset name
     * @return Preset if found
     * @throws IllegalArgumentException if preset not found
     */
    public SettingsPreset getPreset(String name) {
        Objects.requireNonNull(name, "name cannot be null");

        return getPresets().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Preset not found: " + name));
    }

    /**
     * Saves a custom preset.
     * Built-in presets cannot be overwritten.
     *
     * @param preset Preset to save
     * @throws IOException              if save operation fails
     * @throws IllegalArgumentException if trying to overwrite a built-in preset
     */
    public void savePreset(SettingsPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset cannot be null");
        logger.debug("Saving preset: {}", preset.name());

        // Validate preset
        if (!preset.isValid()) {
            throw new IllegalArgumentException("Invalid preset: settings are not valid");
        }

        // Check if trying to overwrite built-in preset
        if (preset.builtIn()) {
            throw new IllegalArgumentException("Cannot save built-in preset: " + preset.name());
        }

        // Read-merge-write cycle is guarded so concurrent saves cannot interleave
        // (consistent with the savePresetsAtomic / savePresetsBySection monitors)
        synchronized (this) {
            // Load existing custom presets, preserving unknown keys (e.g. section presets)
            Path presetsPath = configurationManager.getConfigDirectory().resolve("presets.json");
            List<SettingsPreset> customPresets = new ArrayList<>();
            ObjectNode existingRoot = null;

            if (Files.exists(presetsPath)) {
                try {
                    JsonNode tree = JsonUtils.getObjectMapper().readTree(presetsPath.toFile());
                    JsonNode presetsNode = tree != null && tree.isObject() ? tree.get("presets") : tree;
                    if (tree != null && tree.isObject()) {
                        existingRoot = (ObjectNode) tree;
                    }
                    if (presetsNode != null && presetsNode.isArray()) {
                        customPresets.addAll(readLegacyPresetList(presetsNode));
                    }
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn("Failed to load existing presets, backing up before overwrite", e);
                    if (!backupPresetsFile(presetsPath)) {
                        throw new IOException(
                                "Failed to back up existing presets file; aborting save to avoid data loss", e);
                    }
                }
            }

            // Check for built-in preset name conflict
            List<String> builtInNames = createBuiltInPresets().stream()
                    .map(SettingsPreset::name)
                    .toList();
            if (builtInNames.contains(preset.name())) {
                throw new IllegalArgumentException("Preset name conflicts with built-in preset: " + preset.name());
            }

            // Remove existing preset with same name and add new one
            customPresets.removeIf(p -> p.name().equals(preset.name()));
            customPresets.add(preset);

            // Save to file, preserving coexisting section-based presets
            if (existingRoot != null) {
                existingRoot.set("presets", JsonUtils.getObjectMapper().valueToTree(customPresets));
                savePresetsAtomic(existingRoot, presetsPath);
            } else {
                PresetContainer container = new PresetContainer(customPresets);
                savePresetsAtomic(container, presetsPath);
            }
        }

        logger.info("Preset saved successfully: {}", preset.name());
    }

    /**
     * Deletes a custom preset.
     * Built-in presets cannot be deleted.
     *
     * @param name Preset name
     * @throws IOException              if save operation fails
     * @throws IllegalArgumentException if preset is built-in or not found
     */
    public void deletePreset(String name) throws IOException {
        Objects.requireNonNull(name, "name cannot be null");
        logger.debug("Deleting preset: {}", name);

        // Check if it's a built-in preset
        List<String> builtInNames = createBuiltInPresets().stream()
                .map(SettingsPreset::name)
                .toList();
        if (builtInNames.contains(name)) {
            throw new IllegalArgumentException("Cannot delete built-in preset: " + name);
        }

        // Read-merge-write cycle is guarded so concurrent preset modifications
        // cannot interleave (consistent with savePreset)
        synchronized (this) {
            // Load existing custom presets
            Path presetsPath = configurationManager.getConfigDirectory().resolve("presets.json");
            if (!Files.exists(presetsPath)) {
                throw new IllegalArgumentException("Preset not found: " + name);
            }

            // Load existing custom presets from the raw JSON tree so
            // coexisting unknown keys (e.g. section presets) survive the
            // rewrite (same pattern as savePreset)
            List<SettingsPreset> customPresets = new ArrayList<>();
            ObjectNode existingRoot = null;
            try {
                JsonNode tree = JsonUtils.getObjectMapper().readTree(presetsPath.toFile());
                JsonNode presetsNode = tree != null && tree.isObject() ? tree.get("presets") : tree;
                if (tree != null && tree.isObject()) {
                    existingRoot = (ObjectNode) tree;
                }
                if (presetsNode != null && presetsNode.isArray()) {
                    customPresets.addAll(readLegacyPresetList(presetsNode));
                }
            } catch (IOException | IllegalArgumentException e) {
                throw new IOException("Failed to load presets file", e);
            }

            // Remove the preset
            boolean removed = customPresets.removeIf(p -> p.name().equals(name));
            if (!removed) {
                throw new IllegalArgumentException("Preset not found: " + name);
            }

            // Save updated list, preserving coexisting section-based presets
            if (existingRoot != null) {
                existingRoot.set("presets", JsonUtils.getObjectMapper().valueToTree(customPresets));
                savePresetsAtomic(existingRoot, presetsPath);
            } else {
                PresetContainer container = new PresetContainer(customPresets);
                savePresetsAtomic(container, presetsPath);
            }
        }

        logger.info("Preset deleted successfully: {}", name);
    }

    /**
     * Creates built-in presets for common use cases.
     * Requirement REQ-003.2: Built-in presets
     *
     * @return List of built-in presets
     */
    private List<SettingsPreset> createBuiltInPresets() {
        Path defaultOutputDir = Path.of(System.getProperty("user.home"), "Converted");

        List<SettingsPreset> presets = new ArrayList<>();

        // High Quality preset
        presets.add(SettingsPreset.createBuiltInPreset(
                "High Quality",
                "Best quality output with minimal compression. Larger file sizes.",
                ConversionSettings.builder()
                        .outputFormat(FileFormat.MP4)
                        .outputDirectory(defaultOutputDir)
                        .overwriteExisting(false)
                        .createSubdirectory(false)
                        .parallelConversions(2) // Lower parallelism for high quality
                        .build()));

        // Balanced preset
        presets.add(SettingsPreset.createBuiltInPreset(
                "Balanced",
                "Good balance between quality and file size. Recommended for most uses.",
                ConversionSettings.builder()
                        .outputFormat(FileFormat.MP4)
                        .outputDirectory(defaultOutputDir)
                        .overwriteExisting(false)
                        .createSubdirectory(false)
                        .parallelConversions(4)
                        .build()));

        // Small Size preset
        presets.add(SettingsPreset.createBuiltInPreset(
                "Small Size",
                "Optimized for small file sizes. Lower quality but good compression.",
                ConversionSettings.builder()
                        .outputFormat(FileFormat.MP4)
                        .outputDirectory(defaultOutputDir)
                        .overwriteExisting(false)
                        .createSubdirectory(false)
                        .parallelConversions(4)
                        .build()));

        // Web Optimized preset
        presets.add(SettingsPreset.createBuiltInPreset(
                "Web Optimized",
                "Optimized for web streaming and fast loading. H.264/AAC codec.",
                ConversionSettings.builder()
                        .outputFormat(FileFormat.MP4)
                        .outputDirectory(defaultOutputDir)
                        .overwriteExisting(false)
                        .createSubdirectory(false)
                        .parallelConversions(4)
                        .build()));

        return presets;
    }

    /**
     * Saves presets atomically using temporary file and rename.
     * Synchronized because the temporary file name is fixed.
     *
     * @param value       presets value to save (container or JSON tree)
     * @param presetsPath Target file path
     * @throws IOException if save operation fails
     */
    private synchronized void savePresetsAtomic(Object value, Path presetsPath) throws IOException {
        // Write to temporary file first
        Path tempPath = Path.of(presetsPath.toString() + TEMP_SUFFIX);

        try {
            JsonUtils.writeJsonFile(value, tempPath.toFile());

            // Atomic rename (falls back to a plain move on filesystems
            // without atomic move support)
            StateManager.moveWithAtomicFallback(tempPath, presetsPath);
            invalidatePresetsCache();

        } catch (IOException e) {
            throw e;
        } finally {
            // Clean up the temp file whether the move failed or never happened
            StateManager.deleteQuietly(tempPath);
        }
    }

    /**
     * Container class for JSON serialization of presets.
     * Tolerates unknown properties so old-API reads of new-format
     * {@link PresetsBySection} files do not fail.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PresetContainer {
        public List<SettingsPreset> presets;

        public PresetContainer() {
            this.presets = new ArrayList<>();
        }

        public PresetContainer(List<SettingsPreset> presets) {
            this.presets = new ArrayList<>(presets);
        }
    }

    // ========== Section-Based Preset Management (New Format) ==========
    // Requirement REQ-2.7: Section-based preset organization

    /**
     * Loads presets organized by section from disk.
     * 
     * <p>
     * This method loads presets in the new {@link PresetsBySection} format.
     * The file is first parsed into a JSON tree and its <em>shape</em> is
     * inspected to distinguish current-format files from legacy ones (see
     * {@link #isLegacyPresetsShape(JsonNode)}). Legacy-shaped files are
     * migrated from the old {@code List<SettingsPreset>} format; migration
     * creates a timestamped backup of the old file before converting to the
     * new structure.
     * </p>
     * 
     * <p>
     * <b>Decision Procedure:</b>
     * </p>
     * <ol>
     * <li>Read the file as a JSON tree; if it is not parseable JSON, run the
     * migration path (which backs the file up before giving up)</li>
     * <li>If the tree has the legacy shape (bare array, or an object with a
     * legacy {@code presets} key, or no recognizable current section keys),
     * run backup+migration</li>
     * <li>Otherwise bind the tree to {@link PresetsBySection} leniently
     * (unknown fields are ignored); if binding still fails (e.g. type
     * mismatches), fall back to backup+migration</li>
     * </ol>
     * 
     * <p>
     * Requirements:
     * </p>
     * <ul>
     * <li>REQ-2.7: Preset loading with automatic migration</li>
     * <li>REQ-5.1: Backward compatibility with old format</li>
     * </ul>
     * 
     * @return Presets organized by section, or empty if none exist
     * @see #migrateOldPresetsFormat() for migration implementation
     * @see #isLegacyPresetsShape(JsonNode) for legacy-shape detection
     * @see #addSectionPreset(SectionPreset) to add new presets
     * @see PresetsBySection
     */
    public PresetsBySection loadPresetsBySection() {
        Path presetsPath = configurationManager.getConfigDirectory().resolve("presets.json");

        synchronized (presetsCacheLock) {
            if (cachedPresetsBySection != null && isPresetsCacheCurrent(presetsPath)) {
                logger.debug("Serving presets by section from cache");
                return cachedPresetsBySection;
            }
        }

        PresetsBySection loaded = doLoadPresetsBySection(presetsPath);

        synchronized (presetsCacheLock) {
            cachedPresetsBySection = loaded;
            snapshotPresetsFileState(presetsPath);
        }
        return loaded;
    }

    /**
     * Whether the cached presets still match the on-disk file (missing state,
     * mtime and size). One stat syscall instead of a full read + JSON parse.
     */
    private boolean isPresetsCacheCurrent(Path presetsPath) {
        try {
            if (!Files.exists(presetsPath)) {
                return cachedPresetsFileMissing;
            }
            if (cachedPresetsFileMissing) {
                return false;
            }
            java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(presetsPath,
                    java.nio.file.attribute.BasicFileAttributes.class);
            return attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) == cachedPresetsModifiedNanos
                    && attrs.size() == cachedPresetsSize;
        } catch (IOException e) {
            return false;
        }
    }

    private void snapshotPresetsFileState(Path presetsPath) {
        try {
            if (!Files.exists(presetsPath)) {
                cachedPresetsFileMissing = true;
                cachedPresetsModifiedNanos = Long.MIN_VALUE;
                cachedPresetsSize = -1;
                return;
            }
            java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(presetsPath,
                    java.nio.file.attribute.BasicFileAttributes.class);
            cachedPresetsFileMissing = false;
            cachedPresetsModifiedNanos = attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
            cachedPresetsSize = attrs.size();
        } catch (IOException e) {
            cachedPresetsFileMissing = false;
            cachedPresetsModifiedNanos = Long.MIN_VALUE;
            cachedPresetsSize = -1;
        }
    }

    /**
     * Forces the next {@link #loadPresetsBySection()} to re-read disk. Called
     * by every presets write path so a same-millisecond, same-size write can
     * never serve stale data.
     */
    private void invalidatePresetsCache() {
        synchronized (presetsCacheLock) {
            cachedPresetsBySection = null;
            cachedPresetsFileMissing = false;
            cachedPresetsModifiedNanos = Long.MIN_VALUE;
            cachedPresetsSize = -1;
        }
    }

    private PresetsBySection doLoadPresetsBySection(Path presetsPath) {
        logger.debug("Loading presets by section from: {}", presetsPath);

        // If presets file doesn't exist, return empty
        if (!Files.exists(presetsPath)) {
            logger.info("Presets file does not exist, returning empty");
            return PresetsBySection.empty();
        }

        JsonNode root;
        try {
            root = JsonUtils.getObjectMapper().readTree(presetsPath.toFile());
        } catch (IOException e) {
            // Not parseable JSON: existing failure path (backup, then empty)
            logger.info("Presets file is not parseable JSON, attempting migration: {}", e.getMessage());
            return migrateOldPresetsFormat();
        }

        if (isLegacyPresetsShape(root)) {
            logger.info("Presets file has a legacy or unrecognized shape, attempting migration");
            return migrateOldPresetsFormat();
        }

        try {
            // Current shape: bind leniently (unknown fields are ignored)
            PresetsBySection presets = JsonUtils.getObjectMapper().treeToValue(root, PresetsBySection.class);

            if (presets != null) {
                // Drop presets whose section settings are invalid (e.g. a
                // document preset whose output format was recategorized to
                // another category by a newer version); see
                // withoutInvalidSectionPresets
                PresetsBySection validPresets = withoutInvalidSectionPresets(presets);
                logger.info("Successfully loaded presets by section");
                return validPresets;
            } else {
                logger.warn("Presets file returned null, attempting migration");
                return migrateOldPresetsFormat();
            }

        } catch (IOException e) {
            // Shape looked current but binding failed (e.g. type mismatch):
            // fall back to the migration path rather than failing hard
            logger.info("Failed to load as PresetsBySection format, attempting migration: {}", e.getMessage());
            return migrateOldPresetsFormat();
        }
    }

    /**
     * Drops section presets whose section settings are structurally invalid.
     *
     * <p>
     * A preset can survive in presets.json with settings that no longer
     * validate (for example a document preset whose output format was
     * recategorized to IMAGE by a newer version). Such presets would
     * otherwise load and display harmlessly forever, so they are dropped
     * from the in-memory result with a warning at load time. The check is
     * structural only (see
     * {@link #sectionSettingsStructurallyValid(SectionPreset)}): transient
     * filesystem state, such as a document template on offline storage,
     * must not drop a preset.
     * </p>
     *
     * <p>
     * <b>Conservative in-memory filtering only:</b> the file is left
     * untouched; the next save naturally drops these presets because it
     * serializes the in-memory sections.
     * </p>
     *
     * @param presets the bound presets
     * @return presets with invalid-section presets removed
     */
    private PresetsBySection withoutInvalidSectionPresets(PresetsBySection presets) {
        return new PresetsBySection(
                dropPresetsWithInvalidSettings(presets.videoPresets(), "video"),
                dropPresetsWithInvalidSettings(presets.audioPresets(), "audio"),
                dropPresetsWithInvalidSettings(presets.imagePresets(), "image"),
                dropPresetsWithInvalidSettings(presets.documentPresets(), "document"));
    }

    /**
     * Filters one section's preset list to presets that are structurally
     * valid and whose section settings are structurally valid.
     *
     * @param sectionPresets the section's presets as bound from disk
     * @param section        section name, used for logging
     * @return a new list without invalid presets
     */
    private List<SectionPreset> dropPresetsWithInvalidSettings(List<SectionPreset> sectionPresets, String section) {
        List<SectionPreset> valid = new ArrayList<>(sectionPresets.size());
        for (SectionPreset preset : sectionPresets) {
            if (preset != null && preset.isValid() && sectionSettingsStructurallyValid(preset)) {
                valid.add(preset);
            } else {
                String name = preset != null ? preset.name() : "<unreadable>";
                logger.warn("Dropping {} preset '{}' with invalid section settings during load", section, name);
            }
        }
        return valid;
    }

    /**
     * Checks whether the preset's section settings are structurally valid
     * for its category, without consulting the filesystem.
     *
     * <p>
     * Load-time preset retention must not hinge on transient filesystem
     * state: a document preset whose template lives on removable or offline
     * storage would otherwise be silently dropped at load and permanently
     * deleted from presets.json by the next unrelated save. For that reason
     * the DOCUMENT case uses {@link DocumentSettings#isStructurallyValid()},
     * which excludes the template-path existence check that the runtime
     * {@link DocumentSettings#isValid()} keeps. The video, audio and image
     * section validators perform no filesystem checks at all, so their
     * regular {@code isValid()} is already structural.
     * </p>
     *
     * @param preset the preset to check (structurally valid)
     * @return true when the section settings for the preset's category are
     *         structurally valid
     */
    static boolean sectionSettingsStructurallyValid(SectionPreset preset) {
        return switch (preset.category()) {
            case VIDEO -> preset.videoSettings() != null && preset.videoSettings().isValid();
            case AUDIO -> preset.audioSettings() != null && preset.audioSettings().isValid();
            case IMAGE -> preset.imageSettings() != null && preset.imageSettings().isValid();
            case DOCUMENT -> preset.documentSettings() != null && preset.documentSettings().isStructurallyValid();
            case UNKNOWN -> false;
        };
    }

    /**
     * Top-level JSON field names of the current {@link PresetsBySection}
     * schema, used as current-format shape markers.
     */
    private static final List<String> CURRENT_SECTION_KEYS = List.of(
            "videoPresets", "audioPresets", "imagePresets", "documentPresets");

    /**
     * Top-level JSON field name holding the legacy flat preset list.
     */
    private static final String LEGACY_PRESETS_KEY = "presets";

    /**
     * Decides from the JSON tree shape whether a presets file must take the
     * legacy backup+migration path instead of a normal lenient load.
     *
     * <p>
     * <b>Markers</b> (derived from the historical schemas):
     * </p>
     * <ul>
     * <li><b>Legacy:</b> a bare top-level JSON array (ancient
     * {@code List<SettingsPreset>} files), or a top-level object containing
     * the legacy {@code presets} key (the old {@code {"presets":[...]}}
     * container, including mixed files that also carry section keys — the
     * migration path preserves both parts)</li>
     * <li><b>Current:</b> a top-level object with no {@code presets} key and
     * at least one of the section keys ({@code videoPresets},
     * {@code audioPresets}, {@code imagePresets}, {@code documentPresets})</li>
     * <li><b>Ambiguous:</b> anything else (empty object, objects with only
     * unrecognized keys, non-object/non-array JSON, or a null tree from an
     * empty file)</li>
     * </ul>
     *
     * <p>
     * <b>Conservative rule:</b> ambiguous or unknown shapes are treated as
     * legacy, i.e. they prefer the backup+migration path over silently
     * accepting the file as current. Rationale: a lenient bind of a
     * non-current file would produce an "empty current" result and silently
     * skip migration (data loss), while the migration path is fail-safe — it
     * backs the original up and only rewrites recognized content. Data safety
     * wins over convenience.
     * </p>
     *
     * @param root the parsed presets file tree, may be null (empty/unreadable)
     * @return true if the file must go through backup+migration
     */
    private boolean isLegacyPresetsShape(JsonNode root) {
        if (root == null || root.isNull() || (!root.isObject() && !root.isArray())) {
            // Empty file, JSON null/scalar: ambiguous → safe path
            return true;
        }
        if (root.isArray()) {
            // Ancient bare List<SettingsPreset> files
            return true;
        }
        if (root.has(LEGACY_PRESETS_KEY)) {
            // Legacy {"presets":[...]} container, possibly mixed with sections
            return true;
        }
        // Current only when at least one section key is present; an object
        // with no recognizable marker at all is ambiguous → safe path
        return CURRENT_SECTION_KEYS.stream().noneMatch(root::has);
    }

    /**
     * Migrates presets from old {@code List<SettingsPreset>} format to new
     * {@link PresetsBySection} format.
     * 
     * <p>
     * This method handles the conversion of legacy preset files to the new
     * section-based structure. It accepts both legacy shapes: bare
     * {@code List<SettingsPreset>} arrays and {@code {"presets":[...]}}
     * containers. Section presets coexisting in the same file are preserved.
     * Each old preset is categorized by its output format's category and
     * converted to corresponding {@link SectionPreset} instances. Invalid or
     * malformed presets are skipped with warnings logged.
     * </p>
     * 
     * <p>
     * <b>Migration Steps:</b>
     * </p>
     * <ol>
     * <li>Create backup of the old file with timestamp suffix
     * (.old.TIMESTAMP.bak)</li>
     * <li>Parse defensively: bare array or legacy container</li>
     * <li>Validate each preset and extract output format category</li>
     * <li>Create {@link SectionPreset} for each valid preset based on category</li>
     * <li>Save new {@link PresetsBySection} structure atomically</li>
     * </ol>
     * 
     * <p>
     * <b>Error Handling:</b>
     * </p>
     * <ul>
     * <li>Invalid presets are skipped with warnings</li>
     * <li>Presets without output format or category are skipped</li>
     * <li>If the backup attempt fails, migration is aborted before any
     * destructive rewrite (fail-closed)</li>
     * <li>Returns empty {@link PresetsBySection} if migration fails completely</li>
     * </ul>
     * 
     * <p>
     * Requirements:
     * </p>
     * <ul>
     * <li>REQ-2.7: Preset format migration</li>
     * <li>REQ-5.1: Backward compatibility with old format</li>
     * </ul>
     * 
     * @return Migrated presets organized by section, or empty on failure
     * @see #loadPresetsBySection() for the main entry point
     * @see #savePresetsBySection(PresetsBySection) for atomic save operation
     */
    private PresetsBySection migrateOldPresetsFormat() {
        Path presetsPath = configurationManager.getConfigDirectory().resolve("presets.json");

        logger.info("Starting migration from old preset format at: {}", presetsPath);

        // Backup before parsing so unreadable content is never lost.
        // Fail closed: never rewrite the file when the backup attempt failed.
        if (!backupPresetsFile(presetsPath)) {
            logger.error("Aborting preset migration: backup failed for {}", presetsPath);
            return PresetsBySection.empty();
        }

        try {
            // Parse defensively: accept bare [...] arrays and {"presets":[...]} containers
            JsonNode root = JsonUtils.getObjectMapper().readTree(presetsPath.toFile());
            JsonNode legacyNode = root != null && root.isObject() ? root.get("presets") : root;

            List<SettingsPreset> oldPresets = List.of();
            if (legacyNode != null && legacyNode.isArray()) {
                mapLegacyGlobalOutputFormats(legacyNode);
                oldPresets = readLegacyPresetList(legacyNode);
            }

            // Preserve section presets coexisting with legacy data (mixed files)
            PresetsBySection existingSections = root != null && root.isObject()
                    ? readExistingSections(root)
                    : PresetsBySection.empty();

            if (oldPresets.isEmpty() && existingSections.totalPresetCount() == 0) {
                logger.warn("No recognizable presets found to migrate");
                return PresetsBySection.empty();
            }

            logger.info("Loaded {} old presets for migration", oldPresets.size());

            // Categorize presets by output format category
            List<SectionPreset> videoPresets = new ArrayList<>(existingSections.videoPresets());
            List<SectionPreset> audioPresets = new ArrayList<>(existingSections.audioPresets());
            List<SectionPreset> imagePresets = new ArrayList<>(existingSections.imagePresets());
            List<SectionPreset> documentPresets = new ArrayList<>(existingSections.documentPresets());

            for (SettingsPreset oldPreset : oldPresets) {
                if (!oldPreset.isValid()) {
                    logger.warn("Skipping invalid old preset: {}", oldPreset.name());
                    continue;
                }

                ConversionSettings settings = oldPreset.settings();
                FileFormat outputFormat = settings.outputFormat();

                if (outputFormat == null) {
                    logger.warn("Skipping preset '{}' - no output format", oldPreset.name());
                    continue;
                }

                FormatCategory category = outputFormat.getCategory();

                // Create SectionPreset based on category
                try {
                    SectionPreset sectionPreset = switch (category) {
                        case VIDEO -> {
                            VideoSettings videoSettings = settings.videoSettings();
                            if (videoSettings == null) {
                                logger.warn("Skipping preset '{}' - VIDEO category but no videoSettings",
                                        oldPreset.name());
                                yield null;
                            }
                            yield SectionPreset.forVideo(
                                    oldPreset.name(),
                                    oldPreset.description(),
                                    videoSettings,
                                    oldPreset.builtIn());
                        }
                        case AUDIO -> {
                            AudioSettings audioSettings = settings.audioSettings();
                            if (audioSettings == null) {
                                logger.warn("Skipping preset '{}' - AUDIO category but no audioSettings",
                                        oldPreset.name());
                                yield null;
                            }
                            yield SectionPreset.forAudio(
                                    oldPreset.name(),
                                    oldPreset.description(),
                                    audioSettings,
                                    oldPreset.builtIn());
                        }
                        case IMAGE -> {
                            ImageSettings imageSettings = settings.imageSettings();
                            if (imageSettings == null) {
                                logger.warn("Skipping preset '{}' - IMAGE category but no imageSettings",
                                        oldPreset.name());
                                yield null;
                            }
                            yield SectionPreset.forImage(
                                    oldPreset.name(),
                                    oldPreset.description(),
                                    imageSettings,
                                    oldPreset.builtIn());
                        }
                        case DOCUMENT -> {
                            DocumentSettings documentSettings = settings.documentSettings();
                            if (documentSettings == null) {
                                logger.warn("Skipping preset '{}' - DOCUMENT category but no documentSettings",
                                        oldPreset.name());
                                yield null;
                            }
                            yield SectionPreset.forDocument(
                                    oldPreset.name(),
                                    oldPreset.description(),
                                    documentSettings,
                                    oldPreset.builtIn());
                        }
                        case UNKNOWN -> {
                            logger.warn("Skipping preset '{}' - UNKNOWN category", oldPreset.name());
                            yield null;
                        }
                    };

                    if (sectionPreset != null) {
                        switch (category) {
                            case VIDEO -> addMigratedPresetIfAbsent(videoPresets, sectionPreset, oldPreset.name());
                            case AUDIO -> addMigratedPresetIfAbsent(audioPresets, sectionPreset, oldPreset.name());
                            case IMAGE -> addMigratedPresetIfAbsent(imagePresets, sectionPreset, oldPreset.name());
                            case DOCUMENT -> addMigratedPresetIfAbsent(documentPresets, sectionPreset, oldPreset.name());
                            default -> {
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    logger.error("Error migrating preset '{}': {}", oldPreset.name(), e.getMessage(), e);
                }
            }

            // Create new PresetsBySection
            PresetsBySection newPresets = new PresetsBySection(
                    videoPresets,
                    audioPresets,
                    imagePresets,
                    documentPresets);

            // Save in new format, consuming the legacy "presets" key so the
            // rewritten file is no longer legacy-shaped and subsequent loads
            // take the normal path instead of re-migrating (and re-appending)
            // the same legacy presets on every load
            savePresetsBySection(newPresets, true);

            logger.info("Successfully migrated {} presets: {} video, {} audio, {} image, {} document",
                    oldPresets.size(), videoPresets.size(), audioPresets.size(),
                    imagePresets.size(), documentPresets.size());

            return newPresets;

        } catch (IOException | IllegalArgumentException e) {
            logger.error("Failed to migrate old presets format", e);
            return PresetsBySection.empty();
        }
    }

    /**
     * Maps the legacy global {@code outputFormat} field onto the matching
     * section settings before binding, so presets written by versions prior to
     * the section-based architecture survive migration.
     *
     * <p>
     * For each legacy preset whose {@code settings} node carries a textual
     * {@code outputFormat} but lacks the corresponding section settings, a
     * default-valid section settings object with that format is injected.
     * Existing section settings are never overwritten. Presets with unknown
     * formats are left untouched (they are skipped later by validation).
     * </p>
     *
     * @param legacyPresets array node of legacy {@link SettingsPreset} objects
     */
    private void mapLegacyGlobalOutputFormats(JsonNode legacyPresets) {
        for (JsonNode presetNode : legacyPresets) {
            JsonNode settingsNode = presetNode.get("settings");
            if (!(settingsNode instanceof ObjectNode settingsObject)) {
                continue;
            }

            JsonNode formatNode = settingsObject.get("outputFormat");
            if (formatNode == null || !formatNode.isTextual()) {
                continue;
            }

            FileFormat legacyFormat;
            try {
                legacyFormat = FileFormat.valueOf(formatNode.asText());
            } catch (IllegalArgumentException e) {
                logger.warn("Cannot map legacy global outputFormat '{}': unknown format",
                        formatNode.asText());
                continue;
            }

            JsonNode sectionSettings;
            switch (legacyFormat.getCategory()) {
                case VIDEO -> sectionSettings = JsonUtils.getObjectMapper().valueToTree(
                        VideoSettings.builder().outputFormat(legacyFormat).build());
                case AUDIO -> sectionSettings = JsonUtils.getObjectMapper().valueToTree(
                        AudioSettings.builder().outputFormat(legacyFormat).build());
                case IMAGE -> sectionSettings = JsonUtils.getObjectMapper().valueToTree(
                        ImageSettings.builder().outputFormat(legacyFormat).build());
                case DOCUMENT -> sectionSettings = JsonUtils.getObjectMapper().valueToTree(
                        DocumentSettings.builder().outputFormat(legacyFormat).build());
                default -> {
                    continue;
                }
            }

            String sectionField = sectionFieldName(legacyFormat.getCategory());
            JsonNode existingSection = settingsObject.get(sectionField);
            if (existingSection == null || existingSection.isNull()) {
                settingsObject.set(sectionField, sectionSettings);
                logger.debug("Mapped legacy global outputFormat '{}' to {} for migration",
                        legacyFormat, sectionField);
            } else if (existingSection instanceof ObjectNode section && !section.hasNonNull("outputFormat")) {
                section.put("outputFormat", legacyFormat.name());
            }
        }
    }

    /**
     * Gets the JSON field name of the section settings for a category.
     *
     * @param category the format category
     * @return the settings field name (e.g. {@code videoSettings})
     */
    private String sectionFieldName(FormatCategory category) {
        return switch (category) {
            case VIDEO -> "videoSettings";
            case AUDIO -> "audioSettings";
            case IMAGE -> "imageSettings";
            case DOCUMENT -> "documentSettings";
            default -> "settings";
        };
    }

    /**
     * Backs up a presets file with a timestamped .old.TIMESTAMP.bak suffix.
     *
     * <p>
     * <b>Backup de-duplication:</b> before creating a backup, the current
     * file bytes are compared against the most recent existing backup; when
     * they are identical, no new backup is created. Without this, a
     * degenerate file (unreadable, or with no recognizable presets) that
     * migration never rewrites would create a new timestamped backup on
     * every load, littering the config directory across launches.
     * </p>
     *
     * @param presetsPath path to the presets file
     * @return true if a backup was created (or skipped as duplicate, or
     *         there is nothing to back up), false if the backup attempt
     *         failed
     */
    private boolean backupPresetsFile(Path presetsPath) {
        if (!Files.exists(presetsPath)) {
            return true;
        }
        try {
            if (isAlreadyBackedUpUnchanged(presetsPath)) {
                logger.info("Skipping presets backup for {}: identical backup already exists", presetsPath);
                return true;
            }
            String timestamp = LocalDateTime.now().format(StateManager.BACKUP_TIMESTAMP_FORMATTER);
            Path backupPath = Path.of(presetsPath.toString() + ".old." + timestamp + ".bak");
            Files.copy(presetsPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Backed up old presets to: {}", backupPath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to backup old presets file: {}", presetsPath, e);
            return false;
        }
    }

    /**
     * Decides whether the presets file already has a byte-identical backup,
     * so unchanged content is not backed up again.
     *
     * @param presetsPath path to the presets file
     * @return true when the most recent existing backup matches the current
     *         file content byte-for-byte
     */
    private boolean isAlreadyBackedUpUnchanged(Path presetsPath) {
        Path mostRecentBackup = mostRecentPresetsBackup(presetsPath);
        if (mostRecentBackup == null) {
            return false;
        }
        try {
            return Files.mismatch(presetsPath, mostRecentBackup) == -1;
        } catch (IOException e) {
            // If either file cannot be read for comparison, assume no
            // identical backup so the (fail-closed) backup attempt proceeds
            return false;
        }
    }

    /**
     * Finds the most recent backup of the presets file, or null when none
     * exists.
     *
     * <p>
     * Backup names embed a fixed-width timestamp
     * ({@link StateManager#BACKUP_TIMESTAMP_PATTERN}), so plain
     * lexicographic filename ordering is chronological.
     * </p>
     *
     * @param presetsPath path to the presets file
     * @return the newest matching backup path, or null
     */
    private Path mostRecentPresetsBackup(Path presetsPath) {
        Path directory = presetsPath.getParent();
        if (directory == null) {
            return null;
        }
        String prefix = presetsPath.getFileName().toString() + ".old.";
        try (var files = Files.list(directory)) {
            return files
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .max(String::compareTo)
                    .map(name -> directory.resolve(name))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts per-category section presets from a JSON object node.
     * Unreadable sections are skipped with warnings.
     *
     * @param root object node possibly containing section preset arrays
     * @return presets organized by section, empty where absent or unreadable
     */
    private PresetsBySection readExistingSections(JsonNode root) {
        return new PresetsBySection(
                readSectionPresetList(root.get("videoPresets")),
                readSectionPresetList(root.get("audioPresets")),
                readSectionPresetList(root.get("imagePresets")),
                readSectionPresetList(root.get("documentPresets")));
    }

    /**
     * Converts a JSON array node into a list of section presets.
     *
     * @param node array node, or null/non-array for an empty list
     * @return converted list, or empty list if the node is unreadable
     */
    private List<SectionPreset> readSectionPresetList(JsonNode node) {
        List<SectionPreset> presets = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return presets;
        }
        // Per-entry salvage: one malformed entry must not drop the whole
        // section's presets (mirrors the pendingFiles per-entry salvage in
        // StateManager)
        for (JsonNode entry : node) {
            try {
                SectionPreset preset = JsonUtils.getObjectMapper().treeToValue(entry, SectionPreset.class);
                if (preset != null) {
                    presets.add(preset);
                } else {
                    logger.warn("Skipping null section preset entry during migration");
                }
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Skipping unreadable section preset entry during migration: {}", e.getMessage());
            }
        }
        return presets;
    }

    /**
     * Converts a legacy presets array node into a list of
     * {@link SettingsPreset}, defensively dropping null elements so later
     * name-based processing (duplicate removal, migration) can never fail
     * with a {@link NullPointerException}.
     *
     * @param presetsNode array node of legacy presets
     * @return converted list without null elements
     */
    private List<SettingsPreset> readLegacyPresetList(JsonNode presetsNode) {
        List<SettingsPreset> presets = JsonUtils.getObjectMapper().convertValue(
                presetsNode, new TypeReference<List<SettingsPreset>>() {
                });
        return presets.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Appends a migrated legacy preset to its section list unless a preset
     * with the same name (case-insensitive, consistent with
     * {@link #addSectionPreset(SectionPreset)}) already exists there.
     *
     * <p>
     * Protects mixed files that already carry duplicates from accumulating
     * more copies if their legacy data is ever migrated.
     * </p>
     *
     * @param sectionPresets target section list, modified in place
     * @param preset         the migrated preset to append
     * @param name           the legacy preset name used for duplicate matching
     */
    private void addMigratedPresetIfAbsent(List<SectionPreset> sectionPresets, SectionPreset preset, String name) {
        boolean duplicateExists = sectionPresets.stream()
                .anyMatch(existing -> existing.name().equalsIgnoreCase(name));
        if (duplicateExists) {
            logger.info("Skipping legacy preset '{}': name already exists in the section", name);
            return;
        }
        sectionPresets.add(preset);
    }

    /**
     * Saves presets by section to disk.
     * 
     * <p>
     * Uses atomic write operation with temp file and rename to prevent corruption.
     * Pretty-prints JSON for human readability.
     * </p>
     * 
     * <p>
     * When an existing presets file is readable, the section fields are merged
     * into its JSON root so coexisting keys (e.g. a legacy {@code presets}
     * array) are preserved instead of being dropped by the rewrite.
     * </p>
     * 
     * <p>
     * Requirement REQ-2.7: Preset storage and persistence
     * </p>
     * <p>
     * Requirement REQ-5.3: Atomic write for data integrity
     * </p>
     * 
     * @param presets The presets to save
     * @throws IOException if write operation fails
     */
    private synchronized void savePresetsBySection(PresetsBySection presets) throws IOException {
        savePresetsBySection(presets, false);
    }

    /**
     * Saves presets by section to disk, optionally consuming the legacy
     * {@code presets} key from the existing file root.
     *
     * <p>
     * <b>Legacy key consumption:</b> the migration path passes
     * {@code true} so the rewritten file no longer carries the legacy
     * {@code presets} array. Without this, every load would re-classify the
     * file as legacy-shaped, re-run migration and append another duplicate
     * copy of each legacy preset (plus one timestamped backup per load).
     * Normal saves (add/delete section preset) pass {@code false} and keep
     * the preservation semantics for coexisting keys.
     * </p>
     *
     * @param presets                The presets to save
     * @param consumeLegacyPresetsKey whether to remove the legacy
     *                               {@code presets} key from the merged root
     * @throws IOException if write operation fails
     */
    private synchronized void savePresetsBySection(PresetsBySection presets, boolean consumeLegacyPresetsKey)
            throws IOException {
        Path configDir = configurationManager.getConfigDirectory();
        Path presetsPath = configDir.resolve("presets.json");
        Path tempPath = configDir.resolve("presets.json.tmp");

        logger.debug("Saving presets to: {}", presetsPath);

        // Merge the section fields into the existing file root when possible
        // so coexisting keys (e.g. a legacy "presets" array) survive the save
        Object valueToWrite = presets;
        if (Files.exists(presetsPath)) {
            try {
                JsonNode existingRoot = JsonUtils.getObjectMapper().readTree(presetsPath.toFile());
                if (existingRoot instanceof ObjectNode existingObject) {
                    JsonNode sectionFields = JsonUtils.getObjectMapper().valueToTree(presets);
                    for (String sectionKey : CURRENT_SECTION_KEYS) {
                        existingObject.set(sectionKey, sectionFields.get(sectionKey));
                    }
                    if (consumeLegacyPresetsKey) {
                        existingObject.remove(LEGACY_PRESETS_KEY);
                    }
                    valueToWrite = existingObject;
                }
            } catch (IOException e) {
                logger.warn("Failed to read existing presets file, writing fresh sections only", e);
            }
        }

        try {
            // Write to temporary file first
            JsonUtils.writeJsonFile(valueToWrite, tempPath.toFile());

            // Atomic move to final location (falls back to a plain move on
            // filesystems without atomic move support)
            StateManager.moveWithAtomicFallback(tempPath, presetsPath);
            invalidatePresetsCache();

            logger.info("Successfully saved presets by section to: {}", presetsPath);

        } catch (IOException e) {
            logger.error("Failed to save presets by section to: {}", presetsPath, e);
            throw e;
        } finally {
            // Clean up the temp file whether the move failed or never happened
            StateManager.deleteQuietly(tempPath);
        }
    }

    /**
     * Adds a new section preset to the preset collection.
     * 
     * <p>
     * Validates that no preset with the same name (case-insensitive) already exists
     * in the same category. The updated preset collection is saved atomically to
     * disk.
     * </p>
     * 
     * <p>
     * <b>Thread Safety:</b> The read-merge-write cycle is synchronized on the
     * SettingsManager instance (consistent with {@link #savePreset}), so
     * concurrent preset modifications cannot interleave.
     * </p>
     *
     * <p>
     * Requirement 2.6: Preset creation and management
     * </p>
     * 
     * @param preset the section preset to add
     * @throws IllegalArgumentException if a preset with the same name already
     *                                  exists in the category,
     *                                  or if the preset category is UNKNOWN
     * @throws IOException              if saving the updated presets fails
     * @see #deleteSectionPreset(String, FormatCategory) to remove presets
     * @see #loadPresetsBySection() to retrieve all presets
     */
    public void addSectionPreset(SectionPreset preset) throws IOException {
        Objects.requireNonNull(preset, "preset cannot be null");

        // Validate category is not UNKNOWN
        if (preset.category() == FormatCategory.UNKNOWN) {
            throw new IllegalArgumentException("Cannot add preset with UNKNOWN category");
        }

        logger.debug("Adding section preset: {} (category: {})", preset.name(), preset.category());

        // Read-merge-write cycle is guarded so concurrent preset modifications
        // cannot interleave (consistent with savePreset)
        synchronized (this) {
            // Load current presets
            PresetsBySection currentPresets = loadPresetsBySection();

            // Get list for the preset's category
            List<SectionPreset> categoryPresets = new ArrayList<>(
                    currentPresets.getPresetsForCategory(preset.category()));

            // Check for duplicate name (case-insensitive)
            boolean duplicateExists = categoryPresets.stream()
                    .anyMatch(existing -> existing.name().equalsIgnoreCase(preset.name()));

            if (duplicateExists) {
                logger.warn("Preset with name '{}' already exists in category {}",
                        preset.name(), preset.category());
                throw new IllegalArgumentException(
                        String.format("A preset named '%s' already exists in the %s category",
                                preset.name(), preset.category().name().toLowerCase()));
            }

            // Add preset to list
            categoryPresets.add(preset);

            // Create updated PresetsBySection
            PresetsBySection updatedPresets = replacePresetsForCategory(
                    currentPresets, preset.category(), categoryPresets);

            // Save to disk
            savePresetsBySection(updatedPresets);
        }

        logger.info("Successfully added preset '{}' to {} category",
                preset.name(), preset.category());
    }

    /**
     * Deletes a section preset from the preset collection.
     * 
     * <p>
     * Removes the preset with the specified name (case-insensitive match) from
     * the specified category. If the preset is not found, this method logs a
     * warning
     * and returns gracefully without throwing an exception. The updated collection
     * is
     * saved atomically to disk.
     * </p>
     * 
     * <p>
     * <b>Thread Safety:</b> The read-merge-write cycle is synchronized on the
     * SettingsManager instance (consistent with {@link #savePreset}), so
     * concurrent preset modifications cannot interleave.
     * </p>
     *
     * <p>
     * Requirement 2.6: Preset deletion
     * </p>
     * 
     * @param name     the name of the preset to delete (case-insensitive)
     * @param category the category containing the preset
     * @throws IllegalArgumentException if category is UNKNOWN
     * @throws IOException              if saving the updated presets fails
     * @see #addSectionPreset(SectionPreset) to add presets
     * @see #loadPresetsBySection() to retrieve all presets
     */
    public void deleteSectionPreset(String name, FormatCategory category) throws IOException {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(category, "category cannot be null");

        // Validate category is not UNKNOWN
        if (category == FormatCategory.UNKNOWN) {
            throw new IllegalArgumentException("Cannot delete preset from UNKNOWN category");
        }

        logger.debug("Deleting section preset: {} from category: {}", name, category);

        // Read-merge-write cycle is guarded so concurrent preset modifications
        // cannot interleave (consistent with savePreset)
        synchronized (this) {
            // Load current presets
            PresetsBySection currentPresets = loadPresetsBySection();

            // Get list for the category
            List<SectionPreset> categoryPresets = new ArrayList<>(
                    currentPresets.getPresetsForCategory(category));

            // Filter out the preset to delete (case-insensitive)
            List<SectionPreset> filteredPresets = categoryPresets.stream()
                    .filter(preset -> !preset.name().equalsIgnoreCase(name))
                    .toList();

            // Check if any preset was actually removed
            if (filteredPresets.size() == categoryPresets.size()) {
                logger.warn("Preset '{}' not found in category {}", name, category);
                // Don't throw exception, just log warning and return
                return;
            }

            // Create updated PresetsBySection
            PresetsBySection updatedPresets = replacePresetsForCategory(
                    currentPresets, category, filteredPresets);

            // Save to disk
            savePresetsBySection(updatedPresets);
        }

        logger.info("Successfully deleted preset '{}' from {} category", name, category);
    }

    /**
     * Creates a new PresetsBySection with the specified category's preset list
     * replaced.
     * All other category lists remain unchanged.
     * 
     * @param presets    the current PresetsBySection
     * @param category   the category to replace
     * @param newPresets the new list of presets for the category
     * @return a new PresetsBySection with the updated category list
     */
    private PresetsBySection replacePresetsForCategory(
            PresetsBySection presets,
            FormatCategory category,
            List<SectionPreset> newPresets) {

        return switch (category) {
            case VIDEO -> new PresetsBySection(
                    newPresets,
                    presets.audioPresets(),
                    presets.imagePresets(),
                    presets.documentPresets());
            case AUDIO -> new PresetsBySection(
                    presets.videoPresets(),
                    newPresets,
                    presets.imagePresets(),
                    presets.documentPresets());
            case IMAGE -> new PresetsBySection(
                    presets.videoPresets(),
                    presets.audioPresets(),
                    newPresets,
                    presets.documentPresets());
            case DOCUMENT -> new PresetsBySection(
                    presets.videoPresets(),
                    presets.audioPresets(),
                    presets.imagePresets(),
                    newPresets);
            case UNKNOWN -> {
                logger.warn("Cannot replace presets for UNKNOWN category");
                yield presets; // Return unchanged
            }
        };
    }
}
