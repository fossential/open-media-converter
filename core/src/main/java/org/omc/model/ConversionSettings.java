// filepath: src/main/java/org/omc/model/ConversionSettings.java

package org.omc.model;

import java.nio.file.Path;
import java.util.Objects;

import org.omc.controller.SettingsManager;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Core conversion settings for output configuration.
 * 
 * <p>
 * <b>Section-Based Output Format Architecture:</b>
 * </p>
 * <p>
 * This class implements a section-based format selection model where each media
 * category (VIDEO, AUDIO, IMAGE, DOCUMENT) has its own dedicated settings
 * object
 * containing format and quality parameters. This architecture replaced the
 * older
 * global {@code outputFormat} field to support independent format choices per
 * category.
 * </p>
 * 
 * <p>
 * <b>Migration from Legacy Format:</b>
 * </p>
 * <p>
 * Prior versions used a single global {@code outputFormat} field. This has been
 * replaced by category-specific settings ({@link VideoSettings},
 * {@link AudioSettings},
 * {@link ImageSettings}, {@link DocumentSettings}). The {@link SettingsManager}
 * handles
 * automatic migration of old settings files to the new structure.
 * </p>
 * 
 * <p>
 * <b>Usage Example:</b>
 * </p>
 * 
 * <pre>{@code
 * ConversionSettings settings = ConversionSettings.builder()
 *         .outputDirectory(Paths.get("/output"))
 *         .videoSettings(VideoSettings.builder()
 *                 .outputFormat(FileFormat.MP4)
 *                 .codec("h264")
 *                 .build())
 *         .audioSettings(AudioSettings.builder()
 *                 .outputFormat(FileFormat.MP3)
 *                 .bitrate(192)
 *                 .build())
 *         .parallelConversions(4)
 *         .build();
 * }</pre>
 * 
 * <p>
 * Requirements:
 * </p>
 * <ul>
 * <li>REQ-003.2: Conversion settings with builder pattern</li>
 * <li>REQ-2.1: Per-section output format support</li>
 * <li>REQ-5.1: Backward compatibility with old settings format</li>
 * <li>REQ-GEN-1.1: Delete original file after successful conversion</li>
 * </ul>
 * 
 * @see VideoSettings
 * @see AudioSettings
 * @see ImageSettings
 * @see DocumentSettings
 * @see SettingsManager#loadSettings()
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ConversionSettings {

    private final Path outputDirectory;
    private final boolean overwriteExisting;
    private final boolean createSubdirectory;
    private final int parallelConversions;
    private final boolean deleteOriginalFile; // Requirement REQ-GEN-1.1: Delete original after conversion
    private final VideoSettings videoSettings;
    private final AudioSettings audioSettings;
    private final ImageSettings imageSettings;
    private final DocumentSettings documentSettings;
    private final String language;

    @JsonCreator
    private ConversionSettings(
            @JsonProperty("outputDirectory") Path outputDirectory,
            @JsonProperty("overwriteExisting") boolean overwriteExisting,
            @JsonProperty("createSubdirectory") boolean createSubdirectory,
            @JsonProperty("parallelConversions") int parallelConversions,
            @JsonProperty("deleteOriginalFile") boolean deleteOriginalFile,
            @JsonProperty("videoSettings") VideoSettings videoSettings,
            @JsonProperty("audioSettings") AudioSettings audioSettings,
            @JsonProperty("imageSettings") ImageSettings imageSettings,
            @JsonProperty("documentSettings") DocumentSettings documentSettings,
            @JsonProperty("language") String language) {
        this.outputDirectory = outputDirectory;
        this.overwriteExisting = overwriteExisting;
        this.createSubdirectory = createSubdirectory;
        this.parallelConversions = parallelConversions;
        this.deleteOriginalFile = deleteOriginalFile;
        this.videoSettings = videoSettings;
        this.audioSettings = audioSettings;
        this.imageSettings = imageSettings;
        this.documentSettings = documentSettings;
        this.language = language == null || language.isBlank() ? "system" : language;
    }

    /**
     * Returns the saved interface language; older settings follow the desktop locale.
     * @return language tag, or {@code system} for automatic selection
     */
    @JsonProperty("language")
    public String language() {
        return language;
    }

    @JsonProperty("outputDirectory")
    public Path outputDirectory() {
        return outputDirectory;
    }

    @JsonProperty("overwriteExisting")
    public boolean overwriteExisting() {
        return overwriteExisting;
    }

    @JsonProperty("createSubdirectory")
    public boolean createSubdirectory() {
        return createSubdirectory;
    }

    @JsonProperty("parallelConversions")
    public int parallelConversions() {
        return parallelConversions;
    }

    @JsonProperty("deleteOriginalFile")
    public boolean deleteOriginalFile() {
        return deleteOriginalFile;
    }

    @JsonProperty("videoSettings")
    public VideoSettings videoSettings() {
        return videoSettings;
    }

    @JsonProperty("audioSettings")
    public AudioSettings audioSettings() {
        return audioSettings;
    }

    @JsonProperty("imageSettings")
    public ImageSettings imageSettings() {
        return imageSettings;
    }

    @JsonProperty("documentSettings")
    public DocumentSettings documentSettings() {
        return documentSettings;
    }

    /**
     * Resolves a file's section override and supplies defaults for absent sections.
     * The returned settings are used by both validation and tool execution.
     *
     * @param file file whose settings are being resolved
     * @return complete effective conversion settings
     */
    public ConversionSettings forFile(ConversionFile file) {
        Objects.requireNonNull(file, "file cannot be null");
        if (file.settingsOverride() == null && outputFormat(file.format().getCategory()) != null) {
            return this;
        }
        ConversionSettings defaults = withDefaults();
        FileSettingsOverride override = file.settingsOverride();
        if (override == null) {
            return defaults;
        }
        return new ConversionSettings(outputDirectory, overwriteExisting, createSubdirectory,
                parallelConversions, deleteOriginalFile,
                override.videoSettings() != null ? override.videoSettings() : defaults.videoSettings,
                override.audioSettings() != null ? override.audioSettings() : defaults.audioSettings,
                override.imageSettings() != null ? override.imageSettings() : defaults.imageSettings,
                override.documentSettings() != null ? override.documentSettings() : defaults.documentSettings, language);
    }

    /**
     * Supplies safe defaults for sections missing from an older configuration.
     *
     * @return settings with all four sections initialized
     */
    public ConversionSettings withDefaults() {
        if (videoSettings != null && audioSettings != null && imageSettings != null && documentSettings != null) {
            return this;
        }
        return new ConversionSettings(outputDirectory, overwriteExisting, createSubdirectory,
                parallelConversions, deleteOriginalFile,
                videoSettings != null ? videoSettings : VideoSettings.builder().build(),
                audioSettings != null ? audioSettings : AudioSettings.builder().build(),
                imageSettings != null ? imageSettings : ImageSettings.builder().build(),
                documentSettings != null ? documentSettings : DocumentSettings.builder().build(), language);
    }

    /**
     * Returns these settings with a replacement Document section.
     * @param settings complete document settings
     * @return a copy preserving every other section and global preference
     */
    public ConversionSettings withDocumentSettings(DocumentSettings settings) {
        return new ConversionSettings(outputDirectory, overwriteExisting, createSubdirectory,
                parallelConversions, deleteOriginalFile, videoSettings, audioSettings, imageSettings,
                Objects.requireNonNull(settings, "document settings cannot be null"), language);
    }

    /**
     * Returns the output format for the specified category.
     * 
     * <p>
     * This method provides category-specific format lookup by delegating to the
     * appropriate section settings object. If no settings are configured for the
     * requested category, returns null.
     * </p>
     * 
     * <p>
     * Requirement REQ-2.1: Section-based output format retrieval
     * </p>
     * 
     * @param category the format category (VIDEO, AUDIO, IMAGE, DOCUMENT)
     * @return the output format for the category, or null if not set
     * @see #outputFormat() for retrieving the primary output format
     */
    public FileFormat outputFormat(FormatCategory category) {
        return switch (category) {
            case VIDEO -> videoSettings != null ? videoSettings.outputFormat() : null;
            case AUDIO -> audioSettings != null ? audioSettings.outputFormat() : null;
            case IMAGE -> imageSettings != null ? imageSettings.outputFormat() : null;
            case DOCUMENT -> documentSettings != null ? documentSettings.outputFormat() : null;
            default -> null;
        };
    }

    /**
     * Returns the primary output format (first non-null from settings).
     *
     * @return the output format, or null if none set
     */
    public FileFormat outputFormat() {
        if (videoSettings != null && videoSettings.outputFormat() != null)
            return videoSettings.outputFormat();
        if (audioSettings != null && audioSettings.outputFormat() != null)
            return audioSettings.outputFormat();
        if (imageSettings != null && imageSettings.outputFormat() != null)
            return imageSettings.outputFormat();
        if (documentSettings != null && documentSettings.outputFormat() != null)
            return documentSettings.outputFormat();
        return null;
    }

    /**
     * Resolves the effective output format for a format category from these
     * settings.
     *
     * <p>
     * This is the single canonical resolution used by the UI (file list output
     * column, file details dialog) instead of per-site category switches. A
     * missing section or a section without an explicit output format resolves
     * to {@code null} (callers render "Not Set"), never throws.
     * </p>
     *
     * @param fileCategory the format category (VIDEO, AUDIO, IMAGE, DOCUMENT)
     * @return the section's output format, or null when unset/unsupported
     * @throws NullPointerException if fileCategory is null
     */
    public FileFormat resolveOutputFormat(FormatCategory fileCategory) {
        return outputFormat(Objects.requireNonNull(fileCategory, "fileCategory cannot be null"));
    }

    /**
     * Resolves the effective output format for a specific file, honoring a
     * per-file settings override.
     *
     * <p>
     * If the file has a custom settings override, the override section matching
     * the file's category is consulted and its format is returned even when
     * null (an override deliberately shadows global settings; callers fall back
     * to displaying the preset name). Otherwise the global section format for
     * the file's category is returned.
     * </p>
     *
     * @param file the file whose output format should be resolved
     * @return the resolved output format, or null when unset
     * @throws NullPointerException if file is null
     */
    public FileFormat resolveOutputFormat(ConversionFile file) {
        Objects.requireNonNull(file, "file cannot be null");
        FormatCategory category = file.format().getCategory();
        if (file.hasCustomSettings()) {
            FileSettingsOverride override = file.settingsOverride();
            return switch (category) {
                case VIDEO -> override.videoSettings() != null ? override.videoSettings().outputFormat() : null;
                case AUDIO -> override.audioSettings() != null ? override.audioSettings().outputFormat() : null;
                case IMAGE -> override.imageSettings() != null ? override.imageSettings().outputFormat() : null;
                case DOCUMENT ->
                    override.documentSettings() != null ? override.documentSettings().outputFormat() : null;
                default -> null;
            };
        }
        return resolveOutputFormat(category);
    }

    /**
     * Validates these settings.
     * Checks that output directory exists and is writable, parallel conversions are
     * within bounds,
     * at least one output format is configured, and format-specific settings are
     * valid.
     * 
     * @return true if settings are valid, false otherwise
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isValid() {
        if (outputDirectory == null || !outputDirectory.toFile().exists()) {
            return false;
        }

        if (!outputDirectory.toFile().canWrite()) {
            return false;
        }

        if (parallelConversions < 1 || parallelConversions > 16) {
            return false;
        }

        // Requirement REQ-004.2: At least one output format must be configured
        if (videoSettings == null && audioSettings == null &&
                imageSettings == null && documentSettings == null) {
            return false;
        }

        // Validate format-specific settings
        if (videoSettings != null && !videoSettings.isValid()) {
            return false;
        }
        if (audioSettings != null && !audioSettings.isValid()) {
            return false;
        }
        if (imageSettings != null && !imageSettings.isValid()) {
            return false;
        }
        if (documentSettings != null && !documentSettings.isValid()) {
            return false;
        }

        return true;
    }

    /**
     * Validates these settings without touching the filesystem beyond simple
     * null checks.
     *
     * <p>
     * Checks everything {@link #isValid()} checks <em>except</em> transient
     * filesystem state: the output directory only has to be configured (not
     * currently existing/writable) and the document section is validated via
     * {@link DocumentSettings#isStructurallyValid()} (template existence is
     * not required). Output directories and templates may live on removable
     * or offline storage, so their temporary absence must not decide whether
     * persisted settings data is structurally sound.
     * </p>
     *
     * @return true if the settings are structurally valid
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isStructurallyValid() {
        if (outputDirectory == null) {
            return false;
        }

        if (parallelConversions < 1 || parallelConversions > 16) {
            return false;
        }

        if (videoSettings == null && audioSettings == null &&
                imageSettings == null && documentSettings == null) {
            return false;
        }

        if (videoSettings != null && !videoSettings.isValid()) {
            return false;
        }
        if (audioSettings != null && !audioSettings.isValid()) {
            return false;
        }
        if (imageSettings != null && !imageSettings.isValid()) {
            return false;
        }
        if (documentSettings != null && !documentSettings.isStructurallyValid()) {
            return false;
        }

        return true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path outputDirectory;
        private boolean overwriteExisting = false;
        private boolean createSubdirectory = false;
        private int parallelConversions = 4;
        private boolean deleteOriginalFile = false;
        private VideoSettings videoSettings;
        private AudioSettings audioSettings;
        private ImageSettings imageSettings;
        private DocumentSettings documentSettings;
        private String language = "system";

        /**
         * Sets the interface language without changing conversion options.
         * @param language language tag, or {@code system} to follow the desktop
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Sets the output directory.
         * 
         * @param outputDirectory the directory where converted files will be saved
         * @return this builder
         */
        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        /**
         * Sets whether to overwrite existing files.
         * 
         * @param overwriteExisting true to overwrite existing files, false otherwise
         * @return this builder
         */
        public Builder overwriteExisting(boolean overwriteExisting) {
            this.overwriteExisting = overwriteExisting;
            return this;
        }

        /**
         * Sets whether to create a subdirectory for output.
         * 
         * @param createSubdirectory true to create a subdirectory, false otherwise
         * @return this builder
         */
        public Builder createSubdirectory(boolean createSubdirectory) {
            this.createSubdirectory = createSubdirectory;
            return this;
        }

        /**
         * Sets the number of parallel conversions.
         * 
         * @param parallelConversions the number of parallel conversions (1-16)
         * @return this builder
         */
        public Builder parallelConversions(int parallelConversions) {
            this.parallelConversions = parallelConversions;
            return this;
        }

        /**
         * Sets whether to delete the original file after successful conversion.
         * 
         * @param deleteOriginalFile true to delete original files after successful
         *                           conversion, false otherwise
         * @return this builder
         */
        public Builder deleteOriginalFile(boolean deleteOriginalFile) {
            this.deleteOriginalFile = deleteOriginalFile;
            return this;
        }

        /**
         * Sets the video-specific settings.
         * 
         * @param videoSettings the video settings
         * @return this builder
         */
        public Builder videoSettings(VideoSettings videoSettings) {
            this.videoSettings = videoSettings;
            return this;
        }

        /**
         * Sets the audio-specific settings.
         * 
         * @param audioSettings the audio settings
         * @return this builder
         */
        public Builder audioSettings(AudioSettings audioSettings) {
            this.audioSettings = audioSettings;
            return this;
        }

        /**
         * Sets the image-specific settings.
         * 
         * @param imageSettings the image settings
         * @return this builder
         */
        public Builder imageSettings(ImageSettings imageSettings) {
            this.imageSettings = imageSettings;
            return this;
        }

        /**
         * Sets the document-specific settings.
         * 
         * @param documentSettings the document settings
         * @return this builder
         */
        public Builder documentSettings(DocumentSettings documentSettings) {
            this.documentSettings = documentSettings;
            return this;
        }

        /**
         * Sets the output format for the appropriate category.
         *
         * @param outputFormat the output format (must not be null or UNKNOWN)
         * @return this builder
         * @throws NullPointerException     if outputFormat is null
         * @throws IllegalArgumentException if outputFormat is UNKNOWN
         */
        public Builder outputFormat(FileFormat outputFormat) {
            java.util.Objects.requireNonNull(outputFormat, "outputFormat cannot be null");
            FormatCategory category = outputFormat.getCategory();
            switch (category) {
                case VIDEO -> {
                    if (videoSettings == null) {
                        videoSettings = VideoSettings.builder().outputFormat(outputFormat).build();
                    } else {
                        videoSettings = videoSettings.withOutputFormat(outputFormat);
                    }
                }
                case AUDIO -> {
                    if (audioSettings == null) {
                        audioSettings = AudioSettings.builder().outputFormat(outputFormat).build();
                    } else {
                        audioSettings = audioSettings.withOutputFormat(outputFormat);
                    }
                }
                case IMAGE -> {
                    if (imageSettings == null) {
                        imageSettings = ImageSettings.builder().outputFormat(outputFormat).build();
                    } else {
                        imageSettings = imageSettings.withOutputFormat(outputFormat);
                    }
                }
                case DOCUMENT -> {
                    if (documentSettings == null) {
                        documentSettings = DocumentSettings.builder().outputFormat(outputFormat).build();
                    } else {
                        documentSettings = documentSettings.withOutputFormat(outputFormat);
                    }
                }
                case UNKNOWN -> throw new IllegalArgumentException(
                        "Cannot set an UNKNOWN output format");
            }
            return this;
        }

        /**
         * Builds the ConversionSettings instance.
         * 
         * @return a new ConversionSettings instance
         */
        public ConversionSettings build() {
            return new ConversionSettings(
                    outputDirectory,
                    overwriteExisting,
                    createSubdirectory,
                    parallelConversions,
                    deleteOriginalFile,
                    videoSettings,
                    audioSettings,
                    imageSettings,
                    documentSettings,
                    language);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ConversionSettings that = (ConversionSettings) o;
        return overwriteExisting == that.overwriteExisting &&
                createSubdirectory == that.createSubdirectory &&
                parallelConversions == that.parallelConversions &&
                deleteOriginalFile == that.deleteOriginalFile &&
                Objects.equals(outputDirectory, that.outputDirectory) &&
                Objects.equals(videoSettings, that.videoSettings) &&
                Objects.equals(audioSettings, that.audioSettings) &&
                Objects.equals(imageSettings, that.imageSettings) &&
                Objects.equals(documentSettings, that.documentSettings) &&
                Objects.equals(language, that.language);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outputDirectory, overwriteExisting,
                createSubdirectory, parallelConversions, deleteOriginalFile, videoSettings,
                audioSettings, imageSettings, documentSettings, language);
    }

    @Override
    public String toString() {
        return "ConversionSettings{" +
                "outputDirectory=" + outputDirectory +
                ", overwriteExisting=" + overwriteExisting +
                ", createSubdirectory=" + createSubdirectory +
                ", parallelConversions=" + parallelConversions +
                ", deleteOriginalFile=" + deleteOriginalFile +
                ", language=" + language +
                ", videoSettings=" + videoSettings +
                ", audioSettings=" + audioSettings +
                ", imageSettings=" + imageSettings +
                ", documentSettings=" + documentSettings +
                '}';
    }
}
