package org.omc.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.function.Consumer;

import org.gnome.glib.GLib;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.ComboBoxText;
import org.gnome.gtk.Dialog;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileChooserAction;
import org.gnome.gtk.FileChooserNative;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.MessageDialog;
import org.gnome.gtk.MessageType;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ResponseType;
import org.gnome.gtk.Scale;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.omc.model.AspectRatio;
import org.omc.model.AudioSettings;
import org.omc.model.ConversionSettings;
import org.omc.model.DocumentSettings;
import org.omc.model.FileFormat;
import org.omc.model.FormatCategory;
import org.omc.model.ImageFlip;
import org.omc.model.ImageRotation;
import org.omc.model.ImageSettings;
import org.omc.model.PresetsBySection;
import org.omc.model.ResizeMode;
import org.omc.model.Resolution;
import org.omc.model.SectionPreset;
import org.omc.model.VideoSettings;

/**
 * Settings dialog for configuring conversion settings.
 * Loads UI from settings_dialog.ui using GTK 4 via java-gi bindings.
 * 
 * <p>
 * Requirements: REQ-003.1, REQ-003.2
 * </p>
 */
@SuppressWarnings("deprecation")
public class SettingsDialogJavaGi {

    private static final Logger logger = LoggerFactory.getLogger(SettingsDialogJavaGi.class);

    // Constants for dropdown indices
    private static final int RESOLUTION_INDEX_ORIGINAL = 0;

    // Constants for codec/resolution/preset arrays
    // Requirement REQ-VID-1.1, REQ-VID-1.2, REQ-VID-1.3: Include GPU codec options
    private static final String[] VIDEO_CODECS = { "libx264", "libx265", "libvpx-vp9", "mpeg4", "h264_nvenc",
            "hevc_nvenc", "wmv2", "flv" };
    // Requirement REQ-AUD-1.1: Include copy codec option
    private static final String[] AUDIO_CODECS = { "aac", "libmp3lame", "libopus", "libvorbis", "flac", "copy", "pcm_s16le", "alac" };
    private static final int[] AUDIO_SAMPLE_RATES = { -1, 8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 192000 };
    private static final int[] AUDIO_CHANNELS = { -1, 1, 2, 3, 6, 8 };
    private static final String[] VIDEO_PRESETS = { "ultrafast", "superfast", "veryfast", "faster", "fast", "medium",
            "slow", "slower", "veryslow" };

    /**
     * One entry per preset resolution in the video resolution dropdown. The
     * dropdown labels, the widget-to-model read path and the model-to-widget
     * load path are all derived from this single array so a displayed label
     * always round-trips to the same value.
     */
    record VideoResolutionOption(String name, Resolution resolution) {
        String label() {
            return name + " (" + resolution.getWidth() + "x" + resolution.getHeight() + ")";
        }
    }

    private static final VideoResolutionOption[] VIDEO_RESOLUTION_OPTIONS = {
            new VideoResolutionOption("8K", new Resolution(7680, 4320)),
            new VideoResolutionOption("4K", Resolution.UHD_4K),
            new VideoResolutionOption("1440p", Resolution.QHD_1440P),
            new VideoResolutionOption("1080p", Resolution.FULL_HD_1080P),
            new VideoResolutionOption("720p", Resolution.HD_720P),
            new VideoResolutionOption("480p", new Resolution(854, 480)),
            new VideoResolutionOption("360p", new Resolution(640, 360)),
    };

    // Derived from the shared options array: Original(0), presets(1..N), Custom(N+1)
    private static final int RESOLUTION_INDEX_CUSTOM = VIDEO_RESOLUTION_OPTIONS.length + 1;

    /**
     * Frame rates offered by the video frame rate dropdown, in display order.
     * Index 0 of the dropdown is "Original"; there is no custom frame rate
     * entry, so values outside this array load as "Original".
     */
    private static final int[] VIDEO_FRAME_RATE_OPTIONS = { 24, 25, 30, 50, 60, 120 };

    private FileFormat[] availableDocumentFormats = FileFormat.getFormatsByCategory(FormatCategory.DOCUMENT);

    /**
     * Returns the dropdown labels for the video resolution dropdown, derived
     * from {@link #VIDEO_RESOLUTION_OPTIONS}: "Original", one label per preset
     * option, then "Custom".
     *
     * @return the ordered dropdown labels
     */
    static String[] videoResolutionDropdownLabels() {
        String[] labels = new String[VIDEO_RESOLUTION_OPTIONS.length + 2];
        labels[0] = I18n.tr("Original");
        for (int i = 0; i < VIDEO_RESOLUTION_OPTIONS.length; i++) {
            labels[i + 1] = VIDEO_RESOLUTION_OPTIONS[i].label();
        }
        labels[labels.length - 1] = I18n.tr("Custom");
        return labels;
    }

    /**
     * Maps a video resolution dropdown index to its resolution.
     *
     * @param index the dropdown index (0 = Original, 1..N = presets, N+1 = Custom)
     * @return the preset resolution, or null for Original/Custom/invalid indices
     *         (Custom resolutions are read from the width/height entries)
     */
    static Resolution videoResolutionForDropdownIndex(int index) {
        if (index <= RESOLUTION_INDEX_ORIGINAL || index > VIDEO_RESOLUTION_OPTIONS.length) {
            return null;
        }
        return VIDEO_RESOLUTION_OPTIONS[index - 1].resolution();
    }

    /**
     * Maps a resolution to its video resolution dropdown index.
     *
     * @param resolution the resolution to select
     * @return 0 for null (Original), the matching preset index, or
     *         {@link #RESOLUTION_INDEX_CUSTOM} for non-preset resolutions
     */
    static int videoDropdownIndexForResolution(Resolution resolution) {
        if (resolution == null) {
            return RESOLUTION_INDEX_ORIGINAL;
        }
        for (int i = 0; i < VIDEO_RESOLUTION_OPTIONS.length; i++) {
            if (VIDEO_RESOLUTION_OPTIONS[i].resolution().equals(resolution)) {
                return i + 1;
            }
        }
        return RESOLUTION_INDEX_CUSTOM;
    }

    /**
     * Returns the dropdown labels for the video frame rate dropdown, derived
     * from {@link #VIDEO_FRAME_RATE_OPTIONS}: "Original" plus one "N fps" label
     * per entry.
     *
     * @return the ordered dropdown labels
     */
    static String[] videoFrameRateDropdownLabels() {
        String[] labels = new String[VIDEO_FRAME_RATE_OPTIONS.length + 1];
        labels[0] = I18n.tr("Original");
        for (int i = 0; i < VIDEO_FRAME_RATE_OPTIONS.length; i++) {
            labels[i + 1] = VIDEO_FRAME_RATE_OPTIONS[i] + I18n.tr(" fps");
        }
        return labels;
    }

    /**
     * Maps a video frame rate dropdown index to its frame rate value.
     *
     * @param index the dropdown index (0 = Original, 1..N = listed rates)
     * @return the frame rate in fps, or -1 for Original/invalid indices
     */
    static int videoFrameRateForDropdownIndex(int index) {
        if (index < 1 || index > VIDEO_FRAME_RATE_OPTIONS.length) {
            return -1;
        }
        return VIDEO_FRAME_RATE_OPTIONS[index - 1];
    }

    /**
     * Maps a frame rate to its video frame rate dropdown index.
     *
     * @param frameRate the frame rate in fps
     * @return the matching dropdown index, or 0 (Original) when not listed
     */
    static int videoDropdownIndexForFrameRate(int frameRate) {
        for (int i = 0; i < VIDEO_FRAME_RATE_OPTIONS.length; i++) {
            if (VIDEO_FRAME_RATE_OPTIONS[i] == frameRate) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Returns the native byte length of a UI definition string as marshaled to
     * GtkBuilder.
     *
     * <p>
     * java-gi marshals Java strings to NUL-terminated UTF-8 native buffers, so
     * the length GtkBuilder expects is the UTF-8 byte count — NOT
     * {@code String.length()}, which counts UTF-16 chars and truncates the XML
     * whenever the UI definition contains multi-byte characters.
     * </p>
     *
     * @param ui the UI definition string
     * @return the length in bytes to pass to GtkBuilder
     */
    static long uiByteLength(String ui) {
        return ui.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Disables sections whose required converter is unavailable.
     * @param available categories supported by discovered tools
     */
    public void setAvailableCategories(java.util.Set<FormatCategory> available) {
        var notebook = (org.gnome.gtk.Notebook) builder.getObject("settingsNotebook");
        FormatCategory[] categories = { FormatCategory.VIDEO, FormatCategory.AUDIO, FormatCategory.IMAGE, FormatCategory.DOCUMENT };
        for (int i = 0; i < categories.length; i++) {
            var page = notebook.getNthPage(i + 1);
            boolean enabled = available.contains(categories[i]);
            page.setSensitive(enabled);
            notebook.getTabLabel(page).setTooltipText(enabled ? null : I18n.tr("Install the conversion tool for this section."));
        }
    }

    private void updateDocumentControls() {
        int index = documentFormatDropdown.getSelected();
        FileFormat[] formats = availableDocumentFormats;
        if (index < 0 || index >= formats.length) return;
        FileFormat format = formats[index];
        // Availability-aware check routed through the controller instead of a
        // static service call: when Pandoc is not installed the template
        // options are disabled instead of merely "supported on paper".
        boolean text;
        try {
            text = workflowController != null
                    && workflowController.canConvertDocuments(FileFormat.MARKDOWN, format);
        } catch (IllegalStateException e) {
            logger.warn("Controller not initialized; document template controls disabled", e);
            text = false;
        }
        boolean margins = format == FileFormat.PDF || format == FileFormat.HTML || format == FileFormat.DOCX || format == FileFormat.ODT;
        boolean toc = java.util.Set.of(FileFormat.PDF, FileFormat.HTML, FileFormat.DOCX, FileFormat.EPUB,
                FileFormat.TEX, FileFormat.LATEX).contains(format);
        embedFontsCheckbox.setSensitive(format == FileFormat.PDF);
        embedFontsCheckbox.setTooltipText(I18n.tr("Embed standard fonts in PDF output. Other formats use their own font handling."));
        if (format != FileFormat.PDF) embedFontsCheckbox.setActive(false);
        generateTocCheckbox.setSensitive(toc);
        generateTocCheckbox.setTooltipText(I18n.tr("Generate contents from headings in text documents supported by Pandoc."));
        if (!toc) generateTocCheckbox.setActive(false);
        for (var spin : java.util.List.of(marginTopSpinButton, marginBottomSpinButton, marginLeftSpinButton, marginRightSpinButton)) {
            spin.setSensitive(margins);
            spin.setTooltipText(I18n.tr("Page margins for text documents. Native spreadsheet and presentation conversions retain their source layout."));
            if (!margins) spin.setValue(25);
        }
        templateFileEntry.setSensitive(text);
        if (!text) templateFileEntry.setText("");
        preserveFormattingCheckbox.setSensitive(text);
        if (!text) preserveFormattingCheckbox.setActive(true);
    }

    private void synchronizeMediaCodec(boolean video) {
        DropDown formatWidget = video ? videoFormatDropdown : audioFormatDropdown;
        DropDown codecWidget = video ? videoCodecDropdown : audioCodecDropdown;
        FileFormat[] formats = FileFormat.getFormatsByCategory(video ? FormatCategory.VIDEO : FormatCategory.AUDIO);
        String[] codecs = video ? VIDEO_CODECS : AUDIO_CODECS;
        int selectedFormat = formatWidget.getSelected();
        int selectedCodec = codecWidget.getSelected();
        if (selectedFormat < 0 || selectedFormat >= formats.length || selectedCodec < 0 || selectedCodec >= codecs.length) return;
        String codec = video ? org.omc.model.MediaCodecPolicy.videoCodec(formats[selectedFormat], codecs[selectedCodec])
                : org.omc.model.MediaCodecPolicy.audioCodec(formats[selectedFormat], codecs[selectedCodec]);
        for (int i = 0; i < codecs.length; i++) {
            if (codecs[i].equals(codec) && i != selectedCodec) {
                codecWidget.setSelected(i);
                break;
            }
        }
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return 0;
    }

    private static int valueAt(int[] values, int index) {
        return index >= 0 && index < values.length ? values[index] : values[0];
    }

    // UI Components from Builder
    private GtkBuilder builder;

    // The dialog widget from the UI file
    private Dialog dialog;

    // Current settings being edited
    private ConversionSettings currentSettings;

    // Settings manager for preset operations (REQ-2.6)
    private org.omc.controller.SettingsManager settingsManager;

    // Workflow controller for availability-aware capability checks (document
    // template support). Optional: legacy 3-arg construction leaves it null and
    // capability-dependent controls stay disabled.
    private org.omc.controller.ApplicationWorkflowController workflowController;

    // Cached presets for performance (REQ-5.2)
    private PresetsBySection cachedPresets;

    // Track unsaved changes
    private boolean hasUnsavedChanges = false;

    // Header Bar Widgets
    private Button cancelButton;
    private Button saveButton;

    // Notebook
    private Notebook settingsNotebook;

    // General Tab Widgets
    private Entry outputDirectoryEntry;
    private Button outputDirectoryButton;
    private CheckButton overwriteExistingCheckbox;
    private CheckButton createSubdirectoryCheckbox;
    private CheckButton deleteOriginalFileCheckbox;
    private SpinButton parallelConversionsSpinButton;
    private DropDown languageDropdown;

    // Video Tab Widgets
    private DropDown videoFormatDropdown;
    private DropDown videoCodecDropdown;
    private DropDown videoResolutionDropdown;
    private Box customResolutionBox;
    private Entry videoWidthEntry;
    private Entry videoHeightEntry;
    private DropDown videoAspectRatioDropdown;
    private SpinButton videoBitrateSpinButton;
    private DropDown videoFrameRateDropdown;
    private DropDown videoPresetDropdown;
    private Scale videoCrfScale;
    private Button videoHighQualityButton;
    private Button videoBalancedButton;
    private Button videoSmallSizeButton;

    // Video Preset Management Widgets (REQ-2.6)
    private DropDown videoPresetCombo;
    private Button videoSavePresetButton;
    private Button videoDeletePresetButton;

    // Audio Tab Widgets
    private DropDown audioFormatDropdown;
    private DropDown audioCodecDropdown;
    private SpinButton audioBitrateSpinButton;
    private DropDown audioSampleRateDropdown;
    private DropDown audioChannelsDropdown;
    private Scale audioQualityScale;
    private Button audioHighQualityButton;
    private Button audioBalancedButton;
    private Button audioSmallSizeButton;
    // Audio Preset Management Widgets (REQ-2.6)
    private DropDown audioPresetCombo;
    private Button audioSavePresetButton;
    private Button audioDeletePresetButton;

    // Image Tab Widgets
    private DropDown imageFormatDropdown;
    private Scale imageQualityScale;
    private SpinButton imageWidthSpinButton;
    private SpinButton imageHeightSpinButton;
    private CheckButton maintainAspectRatioCheckbox;
    private Scale compressionLevelScale;
    private DropDown resizeModeDropdown;
    private DropDown imageRotationDropdown; // REQ-IMG-1.1: Image rotation
    private DropDown imageFlipDropdown; // REQ-IMG-2.1: Image flip
    private Button imageHighQualityButton;
    private Button imageBalancedButton;
    private Button imageSmallSizeButton;
    // Image Preset Management Widgets (REQ-2.6)
    private ComboBoxText imagePresetCombo;
    private Button imageSavePresetButton;
    private Button imageDeletePresetButton;

    // Document Tab Widgets
    private DropDown documentFormatDropdown;
    private Entry templateFileEntry;
    private Button templateFileButton;
    private Button clearTemplateButton;
    private CheckButton preserveFormattingCheckbox;
    private CheckButton embedFontsCheckbox;
    private CheckButton generateTocCheckbox;
    private SpinButton marginTopSpinButton;
    private SpinButton marginBottomSpinButton;
    private SpinButton marginLeftSpinButton;
    private SpinButton marginRightSpinButton;
    // Document Preset Management Widgets (REQ-2.6)
    private ComboBoxText docPresetCombo;
    private Button docSavePresetButton;
    private Button docDeletePresetButton;

    // Callback interface for settings save notifications
    @FunctionalInterface
    public interface SettingsSaveCallback {
        void onSettingsSaved(ConversionSettings newSettings);
    }

    // Callback to be invoked when settings are saved
    private SettingsSaveCallback saveCallback;

    /**
     * Constructor. Initializes UI from GtkBuilder and sets up event handlers.
     *
     * @param parent          Parent window (main window)
     * @param settings        Current conversion settings to edit
     * @param settingsManager Settings manager for preset operations (REQ-2.6)
     * @deprecated Use {@link #SettingsDialogJavaGi(Window, ConversionSettings,
     *             org.omc.controller.SettingsManager, org.omc.controller.ApplicationWorkflowController)}
     *             so document capability checks are availability-aware. Kept for
     *             backward compatibility; capability-dependent controls remain
     *             disabled without a controller.
     */
    @Deprecated
    public SettingsDialogJavaGi(Window parent, ConversionSettings settings,
            org.omc.controller.SettingsManager settingsManager) {
        this(parent, settings, settingsManager, null);
    }

    /**
     * Constructor. Initializes UI from GtkBuilder and sets up event handlers.
     *
     * @param parent            Parent window (main window)
     * @param settings          Current conversion settings to edit
     * @param settingsManager   Settings manager for preset operations (REQ-2.6)
     * @param workflowController Workflow controller for availability-aware
     *                          capability checks (may be null)
     */
    public SettingsDialogJavaGi(Window parent, ConversionSettings settings,
            org.omc.controller.SettingsManager settingsManager,
            org.omc.controller.ApplicationWorkflowController workflowController) {
        long startTime = System.currentTimeMillis(); // REQ-5.2: Performance tracking

        settings = settings == null ? ConversionSettings.builder().build().withDefaults() : settings.withDefaults();
        this.currentSettings = settings;
        this.settingsManager = settingsManager;
        this.workflowController = workflowController;

        logger.info("Initializing SettingsDialogJavaGi");

        loadUI();

        // Set dialog properties
        dialog.setTransientFor(parent);
        dialog.setModal(true);

        setupWidgetReferences();
        connectSignals();

        // Populate dropdowns with format options
        populateVideoFormatDropdown();
        populateAudioFormatDropdown();
        populateImageFormatDropdown();
        populateDocumentFormatDropdown();

        // Populate video/audio/image option dropdowns (Task 25, REQ-4.1-4.8)
        populateVideoCodecCombo();
        populateVideoResolutionCombo();
        populateVideoAspectRatioCombo();
        populateVideoFrameRateCombo();
        populateVideoPresetQualityCombo();
        populateAudioCodecCombo();
        populateAudioSampleRateCombo();
        populateAudioChannelsCombo();
        populateImageResizeModeCombo();
        populateImageRotationCombo();
        populateImageFlipCombo();

        documentFormatDropdown.onNotify("selected", param -> updateDocumentControls());
        videoFormatDropdown.onNotify("selected", param -> synchronizeMediaCodec(true));
        audioFormatDropdown.onNotify("selected", param -> synchronizeMediaCodec(false));
        videoCodecDropdown.onNotify("selected", param -> synchronizeMediaCodec(true));
        audioCodecDropdown.onNotify("selected", param -> synchronizeMediaCodec(false));

        // Populate preset management dropdowns (REQ-2.6)
        if (settingsManager != null) {
            // Load cache once at initialization for performance (REQ-5.2)
            refreshPresetCache();
            populateVideoPresets();
            populateAudioPresets();
            populateImagePresets();
            populateDocPresets();
        }

        // Load settings into UI (settings was normalized non-null above)
        setSettings(settings);

        long elapsedTime = System.currentTimeMillis() - startTime;
        logger.info("SettingsDialogJavaGi initialization complete in {}ms (target: <200ms, REQ-5.2)", elapsedTime);
        if (elapsedTime > 200) {
            logger.warn("Dialog initialization exceeded 200ms target: {}ms", elapsedTime);
        }
    }

    /**
     * Loads the UI definition from settings_dialog.ui using GtkBuilder.
     */
    private void loadUI() {
        try {
            logger.debug("Loading UI from settings_dialog.ui");

            // Load UI from resource
            InputStream uiStream = getClass().getResourceAsStream("/ui/settings_dialog.ui");
            if (uiStream == null) {
                throw new RuntimeException("Could not find settings_dialog.ui in resources");
            }

            // Read UI content
            String uiContent;
            try (uiStream) {
                uiContent = new String(uiStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            // Create builder and load from string
            uiContent = I18n.translateUi(uiContent);
            builder = GtkBuilder.fromString(uiContent, uiByteLength(uiContent));

            // Get the dialog from builder - use it directly instead of trying to reparent
            // widgets
            dialog = (Dialog) builder.getObject("settingsDialog");
            if (dialog == null) {
                throw new RuntimeException("Could not find settingsDialog in UI file");
            }

            logger.debug("UI loaded successfully");

        } catch (Exception e) {
            logger.error("Failed to load UI from settings_dialog.ui", e);
            throw new RuntimeException("Failed to load settings dialog UI", e);
        }
    }

    /**
     * Sets up references to all widgets from the builder.
     */
    private void setupWidgetReferences() {
        logger.debug("Setting up widget references");

        // Header bar widgets
        cancelButton = (Button) builder.getObject("cancelButton");
        saveButton = (Button) builder.getObject("saveButton");

        // Notebook
        settingsNotebook = (Notebook) builder.getObject("settingsNotebook");

        // General tab widgets
        outputDirectoryEntry = (Entry) builder.getObject("outputDirectoryEntry");
        outputDirectoryButton = (Button) builder.getObject("outputDirectoryButton");
        overwriteExistingCheckbox = (CheckButton) builder.getObject("overwriteExistingCheckbox");
        createSubdirectoryCheckbox = (CheckButton) builder.getObject("createSubdirectoryCheckbox");
        deleteOriginalFileCheckbox = (CheckButton) builder.getObject("deleteOriginalFileCheckbox");
        parallelConversionsSpinButton = (SpinButton) builder.getObject("parallelConversionsSpinButton");
        languageDropdown = (DropDown) builder.getObject("languageDropdown");
        languageDropdown.setModel(new org.gnome.gtk.StringList(I18n.languageNames()));

        // Video tab widgets
        videoFormatDropdown = (DropDown) builder.getObject("videoFormatDropdown");
        videoCodecDropdown = (DropDown) builder.getObject("videoCodecDropdown");
        videoResolutionDropdown = (DropDown) builder.getObject("videoResolutionDropdown");
        customResolutionBox = (Box) builder.getObject("customResolutionBox");
        videoWidthEntry = (Entry) builder.getObject("videoWidthEntry");
        videoHeightEntry = (Entry) builder.getObject("videoHeightEntry");
        videoAspectRatioDropdown = (DropDown) builder.getObject("videoAspectRatioDropdown");
        videoBitrateSpinButton = (SpinButton) builder.getObject("videoBitrateSpinButton");
        videoFrameRateDropdown = (DropDown) builder.getObject("videoFrameRateDropdown");
        videoPresetDropdown = (DropDown) builder.getObject("videoPresetDropdown");
        videoCrfScale = (Scale) builder.getObject("videoCrfScale");
        videoHighQualityButton = (Button) builder.getObject("videoHighQualityButton");
        videoBalancedButton = (Button) builder.getObject("videoBalancedButton");
        videoSmallSizeButton = (Button) builder.getObject("videoSmallSizeButton");

        // Video preset management widgets (REQ-2.6)
        videoPresetCombo = (DropDown) builder.getObject("videoPresetCombo");
        videoSavePresetButton = (Button) builder.getObject("videoSavePresetButton");
        videoDeletePresetButton = (Button) builder.getObject("videoDeletePresetButton");

        // Audio tab widgets
        audioFormatDropdown = (DropDown) builder.getObject("audioFormatDropdown");
        audioCodecDropdown = (DropDown) builder.getObject("audioCodecDropdown");
        audioBitrateSpinButton = (SpinButton) builder.getObject("audioBitrateSpinButton");
        audioSampleRateDropdown = (DropDown) builder.getObject("audioSampleRateDropdown");
        audioChannelsDropdown = (DropDown) builder.getObject("audioChannelsDropdown");
        audioQualityScale = (Scale) builder.getObject("audioQualityScale");
        audioHighQualityButton = (Button) builder.getObject("audioHighQualityButton");
        audioBalancedButton = (Button) builder.getObject("audioBalancedButton");
        audioSmallSizeButton = (Button) builder.getObject("audioSmallSizeButton");
        // Audio preset management widgets (REQ-2.6)
        audioPresetCombo = (DropDown) builder.getObject("audioPresetCombo");
        audioSavePresetButton = (Button) builder.getObject("audioSavePresetButton");
        audioDeletePresetButton = (Button) builder.getObject("audioDeletePresetButton");

        // Image tab widgets
        imageFormatDropdown = (DropDown) builder.getObject("imageFormatDropdown");
        imageQualityScale = (Scale) builder.getObject("imageQualityScale");
        imageQualityScale.setRange(ImageSettings.LOSSLESS_QUALITY, 100);
        imageQualityScale.setFormatValueFunc(new GtkScaleFormatter(value -> value < 0 ? I18n.tr("Lossless")
                : value == 0 ? I18n.tr("Default") : Integer.toString((int) value)));
        imageWidthSpinButton = (SpinButton) builder.getObject("imageWidthSpinButton");
        imageHeightSpinButton = (SpinButton) builder.getObject("imageHeightSpinButton");
        maintainAspectRatioCheckbox = (CheckButton) builder.getObject("maintainAspectRatioCheckbox");
        compressionLevelScale = (Scale) builder.getObject("compressionLevelScale");
        resizeModeDropdown = (DropDown) builder.getObject("resizeModeDropdown");
        imageRotationDropdown = (DropDown) builder.getObject("imageRotationDropdown"); // REQ-IMG-1.1
        imageFlipDropdown = (DropDown) builder.getObject("imageFlipDropdown"); // REQ-IMG-2.1
        imageHighQualityButton = (Button) builder.getObject("imageHighQualityButton");
        imageBalancedButton = (Button) builder.getObject("imageBalancedButton");
        imageSmallSizeButton = (Button) builder.getObject("imageSmallSizeButton");
        // Image preset management widgets (REQ-2.6)
        imagePresetCombo = (ComboBoxText) builder.getObject("imagePresetCombo");
        imageSavePresetButton = (Button) builder.getObject("imageSavePresetButton");
        imageDeletePresetButton = (Button) builder.getObject("imageDeletePresetButton");

        // Document tab widgets
        documentFormatDropdown = (DropDown) builder.getObject("documentFormatDropdown");
        templateFileEntry = (Entry) builder.getObject("templateFileEntry");
        templateFileButton = (Button) builder.getObject("templateFileButton");
        clearTemplateButton = (Button) builder.getObject("clearTemplateButton");
        preserveFormattingCheckbox = (CheckButton) builder.getObject("preserveFormattingCheckbox");
        embedFontsCheckbox = (CheckButton) builder.getObject("embedFontsCheckbox");
        generateTocCheckbox = (CheckButton) builder.getObject("generateTocCheckbox");
        marginTopSpinButton = (SpinButton) builder.getObject("marginTopSpinButton");
        marginBottomSpinButton = (SpinButton) builder.getObject("marginBottomSpinButton");
        marginLeftSpinButton = (SpinButton) builder.getObject("marginLeftSpinButton");
        marginRightSpinButton = (SpinButton) builder.getObject("marginRightSpinButton");
        // Document preset management widgets (REQ-2.6)
        docPresetCombo = (ComboBoxText) builder.getObject("docPresetCombo");
        docSavePresetButton = (Button) builder.getObject("docSavePresetButton");
        docDeletePresetButton = (Button) builder.getObject("docDeletePresetButton");

        logger.debug("Widget references set up successfully");
    }

    /**
     * Connects signal handlers for all interactive widgets.
     */
    private void connectSignals() {
        logger.debug("Connecting signal handlers");

        // Header bar button signals
        if (cancelButton != null) {
            cancelButton.onClicked(this::handleCancel);
        }

        if (saveButton != null) {
            saveButton.onClicked(this::handleSave);
        }

        // Output directory button
        if (outputDirectoryButton != null) {
            outputDirectoryButton.onClicked(this::handleOutputDirectoryBrowse);
        }

        // Template file buttons
        if (templateFileButton != null) {
            templateFileButton.onClicked(this::handleTemplateFileBrowse);
        }

        if (clearTemplateButton != null) {
            clearTemplateButton.onClicked(this::handleClearTemplate);
        }

        // Video preset buttons
        if (videoHighQualityButton != null) {
            videoHighQualityButton.onClicked(() -> applyVideoPreset("high"));
        }

        if (videoBalancedButton != null) {
            videoBalancedButton.onClicked(() -> applyVideoPreset("balanced"));
        }

        if (videoSmallSizeButton != null) {
            videoSmallSizeButton.onClicked(() -> applyVideoPreset("small"));
        }

        // Video preset management signals (REQ-2.6)
        if (videoPresetCombo != null) {
            videoPresetCombo.onNotify("selected", (param) -> onVideoPresetSelected());
        }

        if (videoSavePresetButton != null) {
            videoSavePresetButton.onClicked(this::onSaveVideoPreset);
        }

        if (videoDeletePresetButton != null) {
            videoDeletePresetButton.onClicked(this::onDeleteVideoPreset);
        }

        // Audio preset management signals (REQ-2.6)
        if (audioPresetCombo != null) {
            audioPresetCombo.onNotify("selected", (param) -> onAudioPresetSelected());
        }

        if (audioSavePresetButton != null) {
            audioSavePresetButton.onClicked(this::onSaveAudioPreset);
        }

        if (audioDeletePresetButton != null) {
            audioDeletePresetButton.onClicked(this::onDeleteAudioPreset);
        }

        // Image preset management signals (REQ-2.6)
        if (imagePresetCombo != null) {
            imagePresetCombo.onChanged(this::onImagePresetSelected);
        }

        if (imageSavePresetButton != null) {
            imageSavePresetButton.onClicked(this::onSaveImagePreset);
        }

        if (imageDeletePresetButton != null) {
            imageDeletePresetButton.onClicked(this::onDeleteImagePreset);
        }

        // Document preset management signals (REQ-2.6)
        if (docPresetCombo != null) {
            docPresetCombo.onChanged(this::onDocPresetSelected);
        }

        if (docSavePresetButton != null) {
            docSavePresetButton.onClicked(this::onSaveDocPreset);
        }

        if (docDeletePresetButton != null) {
            docDeletePresetButton.onClicked(this::onDeleteDocPreset);
        }

        // Audio preset buttons
        if (audioHighQualityButton != null) {
            audioHighQualityButton.onClicked(() -> applyAudioPreset("high"));
        }

        if (audioBalancedButton != null) {
            audioBalancedButton.onClicked(() -> applyAudioPreset("balanced"));
        }

        if (audioSmallSizeButton != null) {
            audioSmallSizeButton.onClicked(() -> applyAudioPreset("small"));
        }

        // Image preset buttons
        if (imageHighQualityButton != null) {
            imageHighQualityButton.onClicked(() -> applyImagePreset("high"));
        }

        if (imageBalancedButton != null) {
            imageBalancedButton.onClicked(() -> applyImagePreset("balanced"));
        }

        if (imageSmallSizeButton != null) {
            imageSmallSizeButton.onClicked(() -> applyImagePreset("small"));
        }

        // Connect change handlers to all widgets to track unsaved changes
        connectChangeHandlers();

        logger.debug("Signal handlers connected");
    }

    /**
     * Connects change handlers to all widgets to track unsaved changes.
     */
    private void connectChangeHandlers() {
        // Connect to all widgets to set hasUnsavedChanges

        // Settings-bearing DropDowns: track selection changes via the
        // "selected" notify signal. Programmatic setSelected() during
        // setSettings() is covered by that method's trailing flag reset.
        trackDropDownChanges(languageDropdown);
        trackDropDownChanges(videoFormatDropdown);
        trackDropDownChanges(videoCodecDropdown);
        trackDropDownChanges(videoAspectRatioDropdown);
        trackDropDownChanges(videoFrameRateDropdown);
        trackDropDownChanges(videoPresetDropdown);
        trackDropDownChanges(audioFormatDropdown);
        trackDropDownChanges(audioCodecDropdown);
        trackDropDownChanges(audioSampleRateDropdown);
        trackDropDownChanges(audioChannelsDropdown);
        trackDropDownChanges(imageFormatDropdown);
        trackDropDownChanges(resizeModeDropdown);
        trackDropDownChanges(imageRotationDropdown);
        trackDropDownChanges(imageFlipDropdown);
        trackDropDownChanges(documentFormatDropdown);

        if (overwriteExistingCheckbox != null) {
            overwriteExistingCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (createSubdirectoryCheckbox != null) {
            createSubdirectoryCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (deleteOriginalFileCheckbox != null) {
            deleteOriginalFileCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (parallelConversionsSpinButton != null) {
            parallelConversionsSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }

        // Video tab
        if (videoWidthEntry != null) {
            videoWidthEntry.onChanged(() -> hasUnsavedChanges = true);
        }
        if (videoHeightEntry != null) {
            videoHeightEntry.onChanged(() -> hasUnsavedChanges = true);
        }
        if (videoBitrateSpinButton != null) {
            videoBitrateSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (videoCrfScale != null) {
            videoCrfScale.onValueChanged(() -> hasUnsavedChanges = true);
        }

        // Audio tab
        if (audioBitrateSpinButton != null) {
            audioBitrateSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (audioQualityScale != null) {
            audioQualityScale.onValueChanged(() -> hasUnsavedChanges = true);
        }

        // Image tab
        if (imageQualityScale != null) {
            imageQualityScale.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (imageWidthSpinButton != null) {
            imageWidthSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (imageHeightSpinButton != null) {
            imageHeightSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (maintainAspectRatioCheckbox != null) {
            maintainAspectRatioCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (compressionLevelScale != null) {
            compressionLevelScale.onValueChanged(() -> hasUnsavedChanges = true);
        }

        // Document tab
        if (templateFileEntry != null) {
            templateFileEntry.onChanged(() -> hasUnsavedChanges = true);
        }
        if (preserveFormattingCheckbox != null) {
            preserveFormattingCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (embedFontsCheckbox != null) {
            embedFontsCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (generateTocCheckbox != null) {
            generateTocCheckbox.onToggled(() -> hasUnsavedChanges = true);
        }
        if (marginTopSpinButton != null) {
            marginTopSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (marginBottomSpinButton != null) {
            marginBottomSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (marginLeftSpinButton != null) {
            marginLeftSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }
        if (marginRightSpinButton != null) {
            marginRightSpinButton.onValueChanged(() -> hasUnsavedChanges = true);
        }

        logger.debug("Change handlers connected for all supported widgets");
    }

    /**
     * Marks unsaved changes when a settings-bearing DropDown's selection
     * changes. Safe to combine with existing "selected" handlers (GObject
     * supports multiple notify connections).
     */
    private void trackDropDownChanges(DropDown dropDown) {
        if (dropDown != null) {
            dropDown.onNotify("selected", param -> hasUnsavedChanges = true);
        }
    }

    /**
     * Handles the Cancel button click.
     */
    private void handleCancel() {
        logger.debug("Cancel button clicked");

        if (hasUnsavedChanges) {
            // Show confirmation dialog
            MessageDialog confirmDialog = MessageDialog.builder()
                    .setTransientFor(dialog)
                    .setModal(true)
                    .setMessageType(MessageType.QUESTION)
                    .setText(I18n.tr("Unsaved Changes"))
                    .setSecondaryText(I18n.tr("You have unsaved changes. Discard them?"))
                    .build();

            // Add buttons manually
            confirmDialog.addButton(I18n.tr("Cancel"), ResponseType.CANCEL.getValue());
            confirmDialog.addButton(I18n.tr("Discard"), ResponseType.YES.getValue());

            confirmDialog.onResponse((response) -> {
                if (response == ResponseType.YES.getValue()) {
                    // User clicked "Discard" - close both dialogs
                    // Close confirmation dialog first to avoid modal stacking issues
                    confirmDialog.destroy();

                    // Defer closing the settings dialog to avoid deadlock
                    // This ensures the confirmation dialog completes its event cycle first
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        closeDialog();
                        return false;
                    });
                } else if (response == ResponseType.CANCEL.getValue()) {
                    // User clicked "Cancel" - close only the confirmation dialog
                    // Settings dialog should remain open
                    confirmDialog.destroy();
                }
            });

            confirmDialog.show();
        } else {
            closeDialog();
        }
    }

    /**
     * Handles the Save button click.
     * Requirement REQ-003.2: Validate settings before saving.
     */
    private void handleSave() {
        logger.debug("Save button clicked");

        // Read settings from UI
        ConversionSettings newSettings = getConversionSettings();

        if (newSettings == null) {
            showValidationError(I18n.tr("Failed to read settings from dialog. Please try again."));
            return;
        }

        // Validate settings and get detailed error message
        String validationError = SettingsDialogJavaGi.validateSettings(newSettings);

        if (validationError == null) {
            // Settings are valid, save and close
            this.currentSettings = newSettings;
            this.hasUnsavedChanges = false;
            closeDialog();
            logger.info("Settings saved successfully");

            // Invoke callback to notify parent that settings have been saved
            if (saveCallback != null) {
                logger.debug("Invoking save callback with new settings");
                saveCallback.onSettingsSaved(newSettings);
            } else {
                logger.warn("No save callback registered - settings may not be persisted");
            }
        } else {
            // Show validation error with specific message
            showValidationError(validationError);
            logger.warn("Settings validation failed: {}", validationError);
        }
    }

    /**
     * Handles the output directory browse button click.
     */
    private void handleOutputDirectoryBrowse() {
        logger.debug("Output directory browse button clicked");

        // Create file chooser dialog
        FileChooserNative fileChooser = FileChooserNative.builder()
                .setTitle(I18n.tr("Select Output Directory"))
                .setAction(FileChooserAction.SELECT_FOLDER)
                .setTransientFor(dialog)
                .build();

        // Set current directory if available
        if (outputDirectoryEntry != null) {
            String currentPath = outputDirectoryEntry.getText();
            if (!currentPath.isEmpty()) {
                try {
                    org.gnome.gio.File currentFile = org.gnome.gio.File.newForPath(currentPath);
                    fileChooser.setCurrentFolder(currentFile);
                } catch (Exception e) {
                    logger.warn("Could not set current folder: {}", e.getMessage());
                }
            }
        }

        // Show dialog
        fileChooser.onResponse((response) -> {
            if (response == ResponseType.ACCEPT.getValue()) {
                org.gnome.gio.File selectedFile = fileChooser.getFile();
                if (selectedFile != null) {
                    final String path = selectedFile.getPath();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        if (outputDirectoryEntry != null) {
                            outputDirectoryEntry.setText(path);
                            hasUnsavedChanges = true;
                        }
                        return false;
                    });
                }
            }
        });

        fileChooser.show();
    }

    /**
     * Handles the template file browse button click.
     */
    private void handleTemplateFileBrowse() {
        logger.debug("Template file browse button clicked");

        // Create file chooser dialog
        FileChooserNative fileChooser = FileChooserNative.builder()
                .setTitle(I18n.tr("Select Template File"))
                .setAction(FileChooserAction.OPEN)
                .setTransientFor(dialog)
                .build();

        // Show dialog
        fileChooser.onResponse((response) -> {
            if (response == ResponseType.ACCEPT.getValue()) {
                org.gnome.gio.File selectedFile = fileChooser.getFile();
                if (selectedFile != null) {
                    final String path = selectedFile.getPath();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        if (templateFileEntry != null) {
                            templateFileEntry.setText(path);
                            hasUnsavedChanges = true;
                        }
                        return false;
                    });
                }
            }
        });

        fileChooser.show();
    }

    /**
     * Handles the clear template button click.
     */
    private void handleClearTemplate() {
        logger.debug("Clear template button clicked");

        if (templateFileEntry != null) {
            templateFileEntry.setText("");
            hasUnsavedChanges = true;
        }
    }

    /**
     * Refreshes the cached presets from disk for performance optimization.
     * Requirement REQ-5.2: Cache presets to avoid repeated disk I/O.
     */
    private void refreshPresetCache() {
        if (settingsManager == null) {
            cachedPresets = PresetsBySection.empty();
            return;
        }

        long startTime = System.nanoTime();
        try {
            cachedPresets = settingsManager.loadPresetsBySection();
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            logger.debug("Refreshed preset cache in {}ms", elapsedMs);
        } catch (IllegalStateException e) {
            logger.error("Failed to load presets into cache", e);
            cachedPresets = PresetsBySection.empty();
        }
    }

    /**
     * Generic method to populate preset dropdowns with saved presets.
     * Requirement REQ-2.6: Load presets into preset management UI.
     * Requirement REQ-5.2: Use cached presets for performance.
     */
    private void populatePresetCombo(Object combo, java.util.List<SectionPreset> presets, Button deleteButton) {
        if (combo == null || settingsManager == null) {
            return;
        }

        logger.debug("Populating preset combo with {} presets", presets.size());

        try {
            // Use cached presets for performance (REQ-5.2)
            String[] presetNames = new String[presets.size() + 1];
            presetNames[0] = I18n.tr("-- Select Preset --");

            for (int i = 0; i < presets.size(); i++) {
                presetNames[i + 1] = presets.get(i).builtIn() ? I18n.tr(presets.get(i).name()) : presets.get(i).name();
            }

            // Set model based on combo type
            org.gnome.gtk.StringList presetList = new org.gnome.gtk.StringList(presetNames);
            if (combo instanceof DropDown) {
                ((DropDown) combo).setModel(presetList);
                ((DropDown) combo).setSelected(0);
            } else if (combo instanceof ComboBoxText) {
                ((ComboBoxText) combo).removeAll();
                for (String name : presetNames) {
                    ((ComboBoxText) combo).appendText(name);
                }
                ((ComboBoxText) combo).setActive(0);
            }

            // Enable/disable delete button
            if (deleteButton != null) {
                deleteButton.setSensitive(false);
            }

        } catch (IllegalStateException e) {
            logger.error("Failed to populate preset combo", e);
        }
    }

    /**
     * Populates the video preset dropdown with saved presets.
     * Requirement REQ-2.6: Load video presets into preset management UI.
     * Requirement REQ-5.2: Use cached presets for performance.
     */
    private void populateVideoPresets() {
        if (videoPresetCombo == null || settingsManager == null) {
            return;
        }

        // Use cached presets for performance (REQ-5.2)
        if (cachedPresets == null) {
            refreshPresetCache();
        }

        populatePresetCombo(videoPresetCombo, cachedPresets.videoPresets(), videoDeletePresetButton);
    }

    /**
     * Handles video preset selection from dropdown.
     * Requirement REQ-2.6: Load selected preset settings into Video UI.
     * Requirement REQ-5.2: Use cached presets for performance.
     */
    private void onVideoPresetSelected() {
        if (videoPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = videoPresetCombo.getSelected();

        // Enable/disable delete button based on selection
        if (videoDeletePresetButton != null) {
            videoDeletePresetButton.setSensitive(selectedIndex > 0);
        }

        // Index 0 is "-- Select Preset --", so ignore it
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Video preset selected at index: {}", selectedIndex);

        try {
            // Use cached presets for performance (REQ-5.2)
            if (cachedPresets == null) {
                refreshPresetCache();
            }

            java.util.List<SectionPreset> videoPresets = cachedPresets.videoPresets();

            // Get selected preset (index - 1 because of placeholder)
            int presetIndex = selectedIndex - 1;
            if (presetIndex >= 0 && presetIndex < videoPresets.size()) {
                SectionPreset selectedPreset = videoPresets.get(presetIndex);

                // Get video settings directly from preset
                VideoSettings videoSettings = selectedPreset.videoSettings();

                if (videoSettings != null) {
                    populateVideoSettings(videoSettings);
                    hasUnsavedChanges = true;
                    logger.info("Loaded video preset: {}", selectedPreset.name());
                } else {
                    logger.warn("Failed to get video settings from preset");
                }
            }

        } catch (IllegalStateException e) {
            logger.error("Failed to load selected video preset", e);
            showError(I18n.tr("Failed to load preset: ") + e.getMessage());
        }
    }

    /**
     * Handles save video preset button click.
     * Requirement REQ-2.6: Save current video settings as a new preset.
     */
    private void onSaveVideoPreset() {
        if (settingsManager == null) {
            return;
        }

        logger.debug("Save video preset button clicked");

        showPresetNameDialog(I18n.tr("Save Video Preset"), name -> {
            try {
                // Read current video settings from UI
                VideoSettings videoSettings = readVideoSettings();
                if (videoSettings == null) {
                    showError(I18n.tr("Failed to read video settings"));
                    return;
                }

                // Create section preset using factory method
                SectionPreset newPreset = SectionPreset.forVideo(
                        name,
                        null, // description
                        videoSettings,
                        false // not built-in
                );

                // Save via settings manager
                settingsManager.addSectionPreset(newPreset);

                // Refresh cache after save (REQ-5.2)
                refreshPresetCache();

                // Refresh preset dropdown
                populateVideoPresets();

                // Show success message
                showInfo(I18n.tr("Video preset '%s' saved successfully", name));

                logger.info("Saved video preset: {}", name);

            } catch (IllegalArgumentException e) {
                showError(e.getMessage());
            } catch (IOException e) {
                logger.error("Failed to save video preset", e);
                showError(I18n.tr("Failed to save preset: ") + e.getMessage());
            }
        });
    }

    /**
     * Handles delete video preset button click.
     * Requirement REQ-2.6: Delete selected video preset.
     * Requirement REQ-5.2: Use cached presets for performance.
     */
    private void onDeleteVideoPreset() {
        if (videoPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = videoPresetCombo.getSelected();

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Delete video preset button clicked");

        try {
            // Use cached presets for performance (REQ-5.2)
            if (cachedPresets == null) {
                refreshPresetCache();
            }

            java.util.List<SectionPreset> videoPresets = cachedPresets.videoPresets();

            // Get selected preset name
            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= videoPresets.size()) {
                return;
            }

            String presetName = videoPresets.get(presetIndex).name();

            // Show confirmation dialog
            Window confirmDialog = new Window();
            confirmDialog.setTitle(I18n.tr("Delete Preset"));
            confirmDialog.setModal(true);
            confirmDialog.setTransientFor(dialog);
            confirmDialog.setDefaultSize(400, -1);
            confirmDialog.setResizable(false);

            // Create main content box
            Box mainBox = new Box(Orientation.VERTICAL, 0);

            // Content area
            Box content = new Box(Orientation.VERTICAL, 12);
            content.setMarginTop(12);
            content.setMarginBottom(12);
            content.setMarginStart(12);
            content.setMarginEnd(12);

            Label label = new Label(I18n.tr("Delete preset '%s'?", presetName));
            content.append(label);

            mainBox.append(content);

            // Button box at bottom
            Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
            buttonBox.setMarginTop(12);
            buttonBox.setMarginBottom(12);
            buttonBox.setMarginStart(12);
            buttonBox.setMarginEnd(12);
            buttonBox.setHalign(Align.END);

            Button cancelButton = Button.withLabel(I18n.tr("Cancel"));
            cancelButton.onClicked(() -> confirmDialog.close());
            buttonBox.append(cancelButton);

            Button deleteButton = Button.withLabel(I18n.tr("Delete"));
            deleteButton.addCssClass("destructive-action");
            deleteButton.onClicked(() -> {
                try {
                    settingsManager.deleteSectionPreset(presetName, FormatCategory.VIDEO);
                    logger.info("Deleted video preset: {}", presetName);

                    // Close confirm dialog first to avoid modal stacking issues
                    confirmDialog.close();

                    // Update UI and show success message after dialog closes
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        refreshPresetCache(); // Refresh cache after delete (REQ-5.2)
                        populateVideoPresets();
                        showInfo(I18n.tr("Preset deleted successfully"));
                        return false;
                    });
                } catch (Exception e) {
                    logger.error("Failed to delete preset", e);
                    confirmDialog.close();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
                        return false;
                    });
                }
            });
            buttonBox.append(deleteButton);

            mainBox.append(buttonBox);
            confirmDialog.setChild(mainBox);

            confirmDialog.present();

        } catch (IllegalStateException e) {
            logger.error("Failed to delete preset", e);
            showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
        }
    }

    // ==================== Audio Preset Management Methods ====================

    /**
     * Populates the audio preset combo box with available presets.
     * Requirement REQ-2.6: Preset management
     * Requirement REQ-5.2: Use cached presets for performance.
     */
    private void populateAudioPresets() {
        if (audioPresetCombo == null || settingsManager == null) {
            return;
        }

        // Use cached presets for performance (REQ-5.2)
        if (cachedPresets == null) {
            refreshPresetCache();
        }

        populatePresetCombo(audioPresetCombo, cachedPresets.audioPresets(), audioDeletePresetButton);
    }

    /**
     * Handles audio preset selection from combo box.
     */
    private void onAudioPresetSelected() {
        if (audioPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = audioPresetCombo.getSelected();

        // Enable/disable delete button
        if (audioDeletePresetButton != null) {
            audioDeletePresetButton.setSensitive(selectedIndex > 0);
        }

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Audio preset selected: index {}", selectedIndex);

        try {
            // Use cached presets for performance (REQ-5.2)
            if (cachedPresets == null) {
                refreshPresetCache();
            }

            java.util.List<SectionPreset> audioPresets = cachedPresets.audioPresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= audioPresets.size()) {
                return;
            }

            SectionPreset preset = audioPresets.get(presetIndex);
            AudioSettings settings = preset.audioSettings();

            populateAudioSettings(settings);
            hasUnsavedChanges = true;

            logger.info("Applied audio preset: {}", preset.name());

        } catch (IllegalStateException e) {
            logger.error("Failed to load audio preset", e);
            showError(I18n.tr("Failed to load preset: ") + e.getMessage());
        }
    }

    /**
     * Handles saving current audio settings as a preset.
     */
    private void onSaveAudioPreset() {
        if (settingsManager == null) {
            return;
        }

        logger.debug("Save audio preset button clicked");

        showPresetNameDialog(I18n.tr("Save Audio Preset"), presetName -> {
            try {
                // Read current audio settings from UI (live widgets, not the
                // dialog-open snapshot)
                AudioSettings settings = readAudioSettings();
                if (settings == null) {
                    showError(I18n.tr("Failed to read audio settings"));
                    return;
                }

                // Create preset
                SectionPreset preset = SectionPreset.forAudio(presetName, null, settings, false);

                // Save preset
                settingsManager.addSectionPreset(preset);
                refreshPresetCache(); // Refresh cache after save (REQ-5.2)
                populateAudioPresets();
                showInfo(I18n.tr("Preset saved successfully"));
                logger.info("Saved audio preset: {}", presetName);

            } catch (IOException e) {
                logger.error("Failed to save preset", e);
                showError(I18n.tr("Failed to save preset: ") + e.getMessage());
            }
        });
    }

    /**
     * Handles deleting the selected audio preset.
     */
    private void onDeleteAudioPreset() {
        if (audioPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = audioPresetCombo.getSelected();

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Delete audio preset button clicked");

        try {
            if (cachedPresets == null) {
                refreshPresetCache();
            }
            java.util.List<SectionPreset> audioPresets = cachedPresets.audioPresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= audioPresets.size()) {
                return;
            }

            String presetName = audioPresets.get(presetIndex).name();

            // Show confirmation dialog
            Window confirmDialog = new Window();
            confirmDialog.setTitle(I18n.tr("Delete Preset"));
            confirmDialog.setModal(true);
            confirmDialog.setTransientFor(dialog);
            confirmDialog.setDefaultSize(400, -1);
            confirmDialog.setResizable(false);

            // Create main content box
            Box mainBox = new Box(Orientation.VERTICAL, 0);

            // Content area
            Box content = new Box(Orientation.VERTICAL, 12);
            content.setMarginTop(12);
            content.setMarginBottom(12);
            content.setMarginStart(12);
            content.setMarginEnd(12);

            Label label = new Label(I18n.tr("Delete preset '%s'?", presetName));
            content.append(label);

            mainBox.append(content);

            // Button box at bottom
            Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
            buttonBox.setMarginTop(12);
            buttonBox.setMarginBottom(12);
            buttonBox.setMarginStart(12);
            buttonBox.setMarginEnd(12);
            buttonBox.setHalign(Align.END);

            Button cancelButton = Button.withLabel(I18n.tr("Cancel"));
            cancelButton.onClicked(() -> confirmDialog.close());
            buttonBox.append(cancelButton);

            Button deleteButton = Button.withLabel(I18n.tr("Delete"));
            deleteButton.addCssClass("destructive-action");
            deleteButton.onClicked(() -> {
                try {
                    settingsManager.deleteSectionPreset(presetName, FormatCategory.AUDIO);
                    logger.info("Deleted audio preset: {}", presetName);

                    // Close confirm dialog first to avoid modal stacking issues
                    confirmDialog.close();

                    // Update UI and show success message after dialog closes
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        refreshPresetCache(); // REQ-5.2: Refresh cache after modification
                        populateAudioPresets();
                        showInfo(I18n.tr("Preset deleted successfully"));
                        return false;
                    });
                } catch (Exception e) {
                    logger.error("Failed to delete preset", e);
                    confirmDialog.close();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
                        return false;
                    });
                }
            });
            buttonBox.append(deleteButton);

            mainBox.append(buttonBox);
            confirmDialog.setChild(mainBox);

            confirmDialog.present();

        } catch (IllegalStateException e) {
            logger.error("Failed to delete preset", e);
            showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
        }
    }

    // ========== Image Preset Management ==========

    /**
     * Populates the image preset combo box with saved presets.
     * Requirement 5.2: Preset Management - Load and display saved image presets
     */
    private void populateImagePresets() {
        if (imagePresetCombo == null || settingsManager == null) {
            return;
        }

        if (cachedPresets == null) {
            refreshPresetCache();
        }

        populatePresetCombo(imagePresetCombo, cachedPresets.imagePresets(), imageDeletePresetButton);
    }

    /**
     * Handles image preset selection from combo box.
     */
    private void onImagePresetSelected() {
        if (imagePresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = imagePresetCombo.getActive();

        // Enable/disable delete button
        if (imageDeletePresetButton != null) {
            imageDeletePresetButton.setSensitive(selectedIndex > 0);
        }

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Image preset selected: index {}", selectedIndex);

        try {
            if (cachedPresets == null) {
                refreshPresetCache();
            }
            java.util.List<SectionPreset> imagePresets = cachedPresets.imagePresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= imagePresets.size()) {
                return;
            }

            SectionPreset preset = imagePresets.get(presetIndex);
            ImageSettings settings = preset.imageSettings();

            populateImageSettings(settings);
            hasUnsavedChanges = true;

            logger.info("Applied image preset: {}", preset.name());

        } catch (IllegalStateException e) {
            logger.error("Failed to load image preset", e);
            showError(I18n.tr("Failed to load preset: ") + e.getMessage());
        }
    }

    /**
     * Handles saving current image settings as a preset.
     */
    private void onSaveImagePreset() {
        if (settingsManager == null) {
            return;
        }

        logger.debug("Save image preset button clicked");

        showPresetNameDialog(I18n.tr("Save Image Preset"), presetName -> {
            try {
                // Read current image settings from UI (live widgets, not the
                // dialog-open snapshot)
                ImageSettings settings = readImageSettings();
                if (settings == null) {
                    showError(I18n.tr("Failed to read image settings"));
                    return;
                }

                // Create preset
                SectionPreset preset = SectionPreset.forImage(presetName, null, settings, false);

                // Save preset
                settingsManager.addSectionPreset(preset);
                refreshPresetCache(); // REQ-5.2: Refresh cache after modification
                populateImagePresets();
                showInfo(I18n.tr("Preset saved successfully"));
                logger.info("Saved image preset: {}", presetName);

            } catch (IOException e) {
                logger.error("Failed to save preset", e);
                showError(I18n.tr("Failed to save preset: ") + e.getMessage());
            }
        });
    }

    /**
     * Handles deleting the selected image preset.
     */
    private void onDeleteImagePreset() {
        if (imagePresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = imagePresetCombo.getActive();

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Delete image preset button clicked");

        try {
            if (cachedPresets == null) {
                refreshPresetCache();
            }
            java.util.List<SectionPreset> imagePresets = cachedPresets.imagePresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= imagePresets.size()) {
                return;
            }

            String presetName = imagePresets.get(presetIndex).name();

            // Show confirmation dialog
            Window confirmDialog = new Window();
            confirmDialog.setTitle(I18n.tr("Delete Preset"));
            confirmDialog.setModal(true);
            confirmDialog.setTransientFor(dialog);
            confirmDialog.setDefaultSize(400, -1);
            confirmDialog.setResizable(false);

            // Create main content box
            Box mainBox = new Box(Orientation.VERTICAL, 0);

            // Content area
            Box content = new Box(Orientation.VERTICAL, 12);
            content.setMarginTop(12);
            content.setMarginBottom(12);
            content.setMarginStart(12);
            content.setMarginEnd(12);

            Label label = new Label(I18n.tr("Delete preset '%s'?", presetName));
            content.append(label);

            mainBox.append(content);

            // Button box at bottom
            Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
            buttonBox.setMarginTop(12);
            buttonBox.setMarginBottom(12);
            buttonBox.setMarginStart(12);
            buttonBox.setMarginEnd(12);
            buttonBox.setHalign(Align.END);

            Button cancelButton = Button.withLabel(I18n.tr("Cancel"));
            cancelButton.onClicked(() -> confirmDialog.close());
            buttonBox.append(cancelButton);

            Button deleteButton = Button.withLabel(I18n.tr("Delete"));
            deleteButton.addCssClass("destructive-action");
            deleteButton.onClicked(() -> {
                try {
                    settingsManager.deleteSectionPreset(presetName, FormatCategory.IMAGE);
                    logger.info("Deleted image preset: {}", presetName);

                    // Close confirm dialog first to avoid modal stacking issues
                    confirmDialog.close();

                    // Update UI and show success message after dialog closes
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        refreshPresetCache(); // REQ-5.2: Refresh cache after modification
                        populateImagePresets();
                        showInfo(I18n.tr("Preset deleted successfully"));
                        return false;
                    });
                } catch (Exception e) {
                    logger.error("Failed to delete preset", e);
                    confirmDialog.close();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
                        return false;
                    });
                }
            });
            buttonBox.append(deleteButton);

            mainBox.append(buttonBox);
            confirmDialog.setChild(mainBox);

            confirmDialog.present();

        } catch (IllegalStateException e) {
            logger.error("Failed to delete preset", e);
            showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
        }
    }

    // ========== Document Preset Management ==========

    /**
     * Populates the document preset combo box with saved presets.
     * Requirement 5.2: Preset Management - Load and display saved document presets
     */
    private void populateDocPresets() {
        if (docPresetCombo == null || settingsManager == null) {
            return;
        }

        if (cachedPresets == null) {
            refreshPresetCache();
        }

        populatePresetCombo(docPresetCombo, cachedPresets.documentPresets(), docDeletePresetButton);
    }

    /**
     * Handles document preset selection from combo box.
     */
    private void onDocPresetSelected() {
        if (docPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = docPresetCombo.getActive();

        // Enable/disable delete button
        if (docDeletePresetButton != null) {
            docDeletePresetButton.setSensitive(selectedIndex > 0);
        }

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Document preset selected: index {}", selectedIndex);

        try {
            if (cachedPresets == null) {
                refreshPresetCache();
            }
            java.util.List<SectionPreset> docPresets = cachedPresets.documentPresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= docPresets.size()) {
                return;
            }

            SectionPreset preset = docPresets.get(presetIndex);
            DocumentSettings settings = preset.documentSettings();

            populateDocumentSettings(settings);
            hasUnsavedChanges = true;

            logger.info("Applied document preset: {}", preset.name());

        } catch (IllegalStateException e) {
            logger.error("Failed to load document preset", e);
            showError(I18n.tr("Failed to load preset: ") + e.getMessage());
        }
    }

    /**
     * Handles saving current document settings as a preset.
     */
    private void onSaveDocPreset() {
        if (settingsManager == null) {
            return;
        }

        logger.debug("Save document preset button clicked");

        showPresetNameDialog(I18n.tr("Save Document Preset"), presetName -> {
            try {
                // Read current document settings from UI (live widgets, not
                // the dialog-open snapshot)
                DocumentSettings settings = readDocumentSettings();
                if (settings == null) {
                    showError(I18n.tr("Failed to read document settings"));
                    return;
                }

                // Create preset
                SectionPreset preset = SectionPreset.forDocument(presetName, null, settings, false);

                // Save preset
                settingsManager.addSectionPreset(preset);
                refreshPresetCache(); // REQ-5.2: Refresh cache after modification
                populateDocPresets();
                showInfo(I18n.tr("Preset saved successfully"));
                logger.info("Saved document preset: {}", presetName);

            } catch (IOException e) {
                logger.error("Failed to save preset", e);
                showError(I18n.tr("Failed to save preset: ") + e.getMessage());
            }
        });
    }

    /**
     * Handles deleting the selected document preset.
     */
    private void onDeleteDocPreset() {
        if (docPresetCombo == null || settingsManager == null) {
            return;
        }

        int selectedIndex = docPresetCombo.getActive();

        // Index 0 is "-- Select Preset --"
        if (selectedIndex <= 0) {
            return;
        }

        logger.debug("Delete document preset button clicked");

        try {
            if (cachedPresets == null) {
                refreshPresetCache();
            }
            java.util.List<SectionPreset> docPresets = cachedPresets.documentPresets();

            int presetIndex = selectedIndex - 1;
            if (presetIndex < 0 || presetIndex >= docPresets.size()) {
                return;
            }

            String presetName = docPresets.get(presetIndex).name();

            // Show confirmation dialog
            Window confirmDialog = new Window();
            confirmDialog.setTitle(I18n.tr("Delete Preset"));
            confirmDialog.setModal(true);
            confirmDialog.setTransientFor(dialog);
            confirmDialog.setDefaultSize(400, -1);
            confirmDialog.setResizable(false);

            // Create main content box
            Box mainBox = new Box(Orientation.VERTICAL, 0);

            // Content area
            Box content = new Box(Orientation.VERTICAL, 12);
            content.setMarginTop(12);
            content.setMarginBottom(12);
            content.setMarginStart(12);
            content.setMarginEnd(12);

            Label label = new Label(I18n.tr("Delete preset '%s'?", presetName));
            content.append(label);

            mainBox.append(content);

            // Button box at bottom
            Box buttonBox = new Box(Orientation.HORIZONTAL, 6);
            buttonBox.setMarginTop(12);
            buttonBox.setMarginBottom(12);
            buttonBox.setMarginStart(12);
            buttonBox.setMarginEnd(12);
            buttonBox.setHalign(Align.END);

            Button cancelButton = Button.withLabel(I18n.tr("Cancel"));
            cancelButton.onClicked(() -> confirmDialog.close());
            buttonBox.append(cancelButton);

            Button deleteButton = Button.withLabel(I18n.tr("Delete"));
            deleteButton.addCssClass("destructive-action");
            deleteButton.onClicked(() -> {
                try {
                    settingsManager.deleteSectionPreset(presetName, FormatCategory.DOCUMENT);
                    logger.info("Deleted document preset: {}", presetName);

                    // Close confirm dialog first to avoid modal stacking issues
                    confirmDialog.close();

                    // Update UI and show success message after dialog closes
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        refreshPresetCache(); // REQ-5.2: Refresh cache after modification
                        populateDocPresets();
                        showInfo(I18n.tr("Preset deleted successfully"));
                        return false;
                    });
                } catch (Exception e) {
                    logger.error("Failed to delete preset", e);
                    confirmDialog.close();
                    GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                        showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
                        return false;
                    });
                }
            });
            buttonBox.append(deleteButton);

            mainBox.append(buttonBox);
            confirmDialog.setChild(mainBox);

            confirmDialog.present();

        } catch (IllegalStateException e) {
            logger.error("Failed to delete preset", e);
            showError(I18n.tr("Failed to delete preset: ") + e.getMessage());
        }
    }

    /**
     * Shows an error message dialog.
     * 
     * @param message the error message
     */
    private void showError(String message) {
        MessageDialog errorDialog = MessageDialog.builder()
                .setTransientFor(dialog)
                .setModal(true)
                .setMessageType(MessageType.ERROR)
                .setText(I18n.tr("Error"))
                .setSecondaryText(message)
                .build();

        errorDialog.addButton(I18n.tr("OK"), ResponseType.OK.getValue());
        errorDialog.onResponse((response) -> {
            errorDialog.destroy();
        });
        errorDialog.show();
    }

    /**
     * Shows an info message dialog.
     * 
     * @param message the info message
     */
    private void showInfo(String message) {
        MessageDialog infoDialog = MessageDialog.builder()
                .setTransientFor(dialog)
                .setModal(true)
                .setMessageType(MessageType.INFO)
                .setText(I18n.tr("Success"))
                .setSecondaryText(message)
                .build();

        infoDialog.addButton(I18n.tr("OK"), ResponseType.OK.getValue());
        infoDialog.onResponse((response) -> {
            infoDialog.destroy();
        });
        infoDialog.show();
    }

    /**
     * Shows a dialog to enter a preset name with validation.
     * 
     * @param title  the dialog title
     * @param onSave callback invoked with the validated preset name when Save is
     *               clicked
     */
    private void showPresetNameDialog(String title, Consumer<String> onSave) {
        Window nameDialog = new Window();
        nameDialog.setTitle(title);
        nameDialog.setModal(true);
        nameDialog.setTransientFor(dialog);
        nameDialog.setDefaultSize(400, -1);
        nameDialog.setResizable(false);

        // Create main content box
        Box mainBox = new Box(Orientation.VERTICAL, 0);

        // Content area
        Box content = new Box(Orientation.VERTICAL, 8);
        content.setMarginTop(12);
        content.setMarginBottom(12);
        content.setMarginStart(12);
        content.setMarginEnd(12);

        Label label = new Label(I18n.tr("Preset name:"));
        label.setHalign(Align.START);
        content.append(label);

        Entry nameEntry = new Entry();
        nameEntry.setPlaceholderText(I18n.tr("Enter preset name..."));
        content.append(nameEntry);

        // Validation error label (initially hidden)
        Label errorLabel = new Label("");
        errorLabel.setHalign(Align.START);
        errorLabel.addCssClass("error");
        errorLabel.setVisible(false);
        content.append(errorLabel);

        mainBox.append(content);

        // Button bar at bottom
        Box buttonBar = new Box(Orientation.HORIZONTAL, 8);
        buttonBar.setMarginTop(0);
        buttonBar.setMarginBottom(12);
        buttonBar.setMarginStart(12);
        buttonBar.setMarginEnd(12);
        buttonBar.setHalign(Align.END);

        Button cancelButton = new Button();
        cancelButton.setLabel(I18n.tr("Cancel"));
        cancelButton.onClicked(() -> nameDialog.close());
        buttonBar.append(cancelButton);

        Button saveButton = new Button();
        saveButton.setLabel(I18n.tr("Save"));
        saveButton.addCssClass("suggested-action");
        buttonBar.append(saveButton);

        mainBox.append(buttonBar);
        nameDialog.setChild(mainBox);

        // Real-time validation
        nameEntry.onChanged(() -> {
            String name = nameEntry.getText().trim();
            String validationError = validatePresetName(name);
            if (validationError != null) {
                errorLabel.setText(validationError);
                errorLabel.setVisible(true);
                saveButton.setSensitive(false);
            } else {
                errorLabel.setVisible(false);
                saveButton.setSensitive(true);
            }
        });

        // Save button action
        saveButton.onClicked(() -> {
            String name = nameEntry.getText().trim();
            // Double-check validation (should already be valid due to button state)
            String validationError = validatePresetName(name);
            if (validationError == null) {
                try {
                    onSave.accept(name);
                    nameDialog.close();
                } catch (Exception e) {
                    logger.error("Failed to save preset", e);
                    showError(I18n.tr("Failed to save preset: ") + e.getMessage());
                }
            }
        });

        // Make Enter key trigger save when entry has focus
        nameEntry.onActivate(() -> {
            if (saveButton.getSensitive()) {
                String name = nameEntry.getText().trim();
                // Double-check validation (should already be valid due to button state)
                String validationError = validatePresetName(name);
                if (validationError == null) {
                    try {
                        onSave.accept(name);
                        nameDialog.close();
                    } catch (Exception e) {
                        logger.error("Failed to save preset", e);
                        showError(I18n.tr("Failed to save preset: ") + e.getMessage());
                    }
                }
            }
        });

        nameDialog.present();
        nameEntry.grabFocus();
    }

    /**
     * Applies a video quality preset.
     * 
     * @param preset the preset name ("high", "balanced", "small")
     */
    private void applyVideoPreset(String preset) {
        logger.debug("Applying video preset: {}", preset);

        String[] presets = VIDEO_PRESETS;

        if ("high".equals(preset)) {
            // High quality: high bitrate, low CRF, slower preset
            if (videoBitrateSpinButton != null) {
                videoBitrateSpinButton.setValue(8000);
            }
            if (videoCrfScale != null) {
                videoCrfScale.setValue(18);
            }
            setPresetDropdown(videoPresetDropdown, presets, "slow");
        } else if ("balanced".equals(preset)) {
            // Balanced: medium bitrate, medium CRF, medium preset
            if (videoBitrateSpinButton != null) {
                videoBitrateSpinButton.setValue(5000);
            }
            if (videoCrfScale != null) {
                videoCrfScale.setValue(23);
            }
            setPresetDropdown(videoPresetDropdown, presets, "medium");
        } else if ("small".equals(preset)) {
            // Small size: lower bitrate, higher CRF, faster preset
            if (videoBitrateSpinButton != null) {
                videoBitrateSpinButton.setValue(2000);
            }
            if (videoCrfScale != null) {
                videoCrfScale.setValue(28);
            }
            setPresetDropdown(videoPresetDropdown, presets, "fast");
        }

        hasUnsavedChanges = true;
    }

    /**
     * Helper method to set preset dropdown by finding the index of the preset
     * string.
     */
    private void setPresetDropdown(DropDown dropdown, String[] presets, String targetPreset) {
        if (dropdown != null) {
            for (int i = 0; i < presets.length; i++) {
                if (presets[i].equals(targetPreset)) {
                    dropdown.setSelected(i);
                    break;
                }
            }
        }
    }

    /**
     * Applies an audio quality preset.
     * 
     * @param preset the preset name ("high", "balanced", "small")
     */
    private void applyAudioPreset(String preset) {
        logger.debug("Applying audio preset: {}", preset);

        if ("high".equals(preset)) {
            // High quality: high bitrate, low quality value (closer to lossless)
            if (audioBitrateSpinButton != null) {
                audioBitrateSpinButton.setValue(320);
            }
            if (audioQualityScale != null) {
                audioQualityScale.setValue(0);
            }
        } else if ("balanced".equals(preset)) {
            // Balanced: medium bitrate, medium quality
            if (audioBitrateSpinButton != null) {
                audioBitrateSpinButton.setValue(192);
            }
            if (audioQualityScale != null) {
                audioQualityScale.setValue(5);
            }
        } else if ("small".equals(preset)) {
            // Small size: lower bitrate, higher quality value
            if (audioBitrateSpinButton != null) {
                audioBitrateSpinButton.setValue(128);
            }
            if (audioQualityScale != null) {
                audioQualityScale.setValue(7);
            }
        }

        hasUnsavedChanges = true;
    }

    /**
     * Applies an image quality preset.
     * 
     * @param preset the preset name ("high", "balanced", "small")
     */
    private void applyImagePreset(String preset) {
        logger.debug("Applying image preset: {}", preset);

        if ("high".equals(preset)) {
            // High quality: high quality value, low compression
            if (imageQualityScale != null) {
                imageQualityScale.setValue(95);
            }
            if (compressionLevelScale != null) {
                compressionLevelScale.setValue(3);
            }
        } else if ("balanced".equals(preset)) {
            // Balanced: medium quality, medium compression
            if (imageQualityScale != null) {
                imageQualityScale.setValue(85);
            }
            if (compressionLevelScale != null) {
                compressionLevelScale.setValue(6);
            }
        } else if ("small".equals(preset)) {
            // Small size: lower quality, higher compression
            if (imageQualityScale != null) {
                imageQualityScale.setValue(75);
            }
            if (compressionLevelScale != null) {
                compressionLevelScale.setValue(9);
            }
        }

        hasUnsavedChanges = true;
    }

    /**
     * Sets the settings to be displayed in the dialog.
     * This method will populate all UI widgets from the provided settings.
     * Requirement REQ-003.1: Load settings into UI widgets.
     * 
     * @param settings the settings to display
     */
    public void setSettings(ConversionSettings settings) {
        logger.debug("Loading settings into dialog");

        if (settings == null) {
            logger.warn("Cannot load null settings");
            return;
        }

        settings = settings.withDefaults();
        this.currentSettings = settings;

        // Populate output settings
        populateOutputSettings(settings);

        // Populate video settings
        if (settings.videoSettings() != null) {
            populateVideoSettings(settings.videoSettings());
        }

        // Populate audio settings
        if (settings.audioSettings() != null) {
            populateAudioSettings(settings.audioSettings());
        }

        // Populate image settings
        if (settings.imageSettings() != null) {
            populateImageSettings(settings.imageSettings());
        }

        // Populate document settings
        if (settings.documentSettings() != null) {
            populateDocumentSettings(settings.documentSettings());
        }

        // Reset unsaved changes flag after loading
        this.hasUnsavedChanges = false;

        logger.debug("Settings loaded successfully");
    }

    /**
     * Populates General tab widgets.
     * Requirement REQ-003.2: Populate directory and general conversion settings.
     * 
     * @param settings the conversion settings
     */
    private void populateOutputSettings(ConversionSettings settings) {
        logger.debug("Populating general tab settings");

        String languageTag = settings.language().replace('_', '-');
        int languageIndex = I18n.LANGUAGE_TAGS.indexOf(
                "system".equalsIgnoreCase(languageTag) ? "system" : java.util.Locale.forLanguageTag(languageTag).getLanguage());
        languageDropdown.setSelected(Math.max(0, languageIndex));

        // Set output directory
        if (outputDirectoryEntry != null && settings.outputDirectory() != null) {
            outputDirectoryEntry.setText(settings.outputDirectory().toString());
        }

        // Set checkboxes
        if (overwriteExistingCheckbox != null) {
            overwriteExistingCheckbox.setActive(settings.overwriteExisting());
        }

        if (createSubdirectoryCheckbox != null) {
            createSubdirectoryCheckbox.setActive(settings.createSubdirectory());
        }

        // Set delete original file checkbox (T-8.7, REQ-GEN-1.1)
        if (deleteOriginalFileCheckbox != null) {
            deleteOriginalFileCheckbox.setActive(settings.deleteOriginalFile());
        }

        // Set parallel conversions
        if (parallelConversionsSpinButton != null) {
            parallelConversionsSpinButton.setValue(settings.parallelConversions());
        }
    }

    /**
     * Populates the video format dropdown with VIDEO category formats.
     * Requirement REQ-2.2: Video tab shows output format dropdown with VIDEO
     * formats only.
     */
    private void populateVideoFormatDropdown() {
        if (videoFormatDropdown == null)
            return;

        // Get all video formats
        FileFormat[] videoFormats = FileFormat.getFormatsByCategory(FormatCategory.VIDEO);

        // Create array of display names
        String[] formatNames = new String[videoFormats.length];
        for (int i = 0; i < videoFormats.length; i++) {
            formatNames[i] = videoFormats[i].name();
        }

        // Create and set model
        org.gnome.gtk.StringList formatList = new org.gnome.gtk.StringList(formatNames);
        videoFormatDropdown.setModel(formatList);
    }

    /**
     * Populates the audio format dropdown with AUDIO category formats.
     * Requirement REQ-2.3: Audio tab shows output format dropdown with AUDIO
     * formats only.
     */
    private void populateAudioFormatDropdown() {
        if (audioFormatDropdown == null)
            return;

        // Get all audio formats
        FileFormat[] audioFormats = FileFormat.getFormatsByCategory(FormatCategory.AUDIO);

        // Create array of display names
        String[] formatNames = new String[audioFormats.length];
        for (int i = 0; i < audioFormats.length; i++) {
            formatNames[i] = audioFormats[i].name();
        }

        // Create and set model
        org.gnome.gtk.StringList formatList = new org.gnome.gtk.StringList(formatNames);
        audioFormatDropdown.setModel(formatList);
    }

    /**
     * Populates the image format dropdown with IMAGE category formats.
     * Requirement REQ-2.4: Image tab shows output format dropdown with IMAGE
     * formats only.
     */
    private void populateImageFormatDropdown() {
        if (imageFormatDropdown == null)
            return;

        // Get all image formats
        FileFormat[] imageFormats = FileFormat.getFormatsByCategory(FormatCategory.IMAGE);

        // Create array of display names
        String[] formatNames = new String[imageFormats.length];
        for (int i = 0; i < imageFormats.length; i++) {
            formatNames[i] = imageFormats[i].name();
        }

        // Create and set model
        org.gnome.gtk.StringList formatList = new org.gnome.gtk.StringList(formatNames);
        imageFormatDropdown.setModel(formatList);
    }

    /**
     * Populates the document format dropdown with DOCUMENT category formats.
     * Requirement REQ-2.5: Document tab shows output format dropdown with DOCUMENT
     * formats only.
     */
    private void populateDocumentFormatDropdown() {
        if (workflowController != null) {
            try {
                FileFormat[] supported = java.util.Arrays.stream(FileFormat.getFormatsByCategory(FormatCategory.DOCUMENT))
                        .filter(output -> java.util.Arrays.stream(FileFormat.getFormatsByCategory(FormatCategory.DOCUMENT))
                                .anyMatch(input -> workflowController.canConvertDocuments(input, output)))
                        .toArray(FileFormat[]::new);
                // If no converter exists, the entire section is disabled. Keep
                // its saved values displayable rather than erasing the section.
                if (supported.length > 0) availableDocumentFormats = supported;
            } catch (IllegalStateException e) {
                logger.debug("Document capabilities are not initialized yet", e);
            }
        }
        if (documentFormatDropdown == null)
            return;

        // Get all document formats
        FileFormat[] documentFormats = availableDocumentFormats;

        // Create array of display names
        String[] formatNames = new String[documentFormats.length];
        for (int i = 0; i < documentFormats.length; i++) {
            formatNames[i] = documentFormats[i].name();
        }

        // Create and set model
        org.gnome.gtk.StringList formatList = new org.gnome.gtk.StringList(formatNames);
        documentFormatDropdown.setModel(formatList);
    }

    /**
     * Populates video settings tab widgets.
     * Requirement REQ-006.1: Populate video codec, bitrate, resolution, etc.
     * 
     * @param videoSettings the video settings
     */
    private void populateVideoSettings(VideoSettings videoSettings) {
        logger.debug("Populating video settings");

        // Set output format dropdown (Requirement REQ-2.2)
        if (videoFormatDropdown != null && videoSettings.outputFormat() != null) {
            FileFormat[] videoFormats = FileFormat.getFormatsByCategory(FormatCategory.VIDEO);
            for (int i = 0; i < videoFormats.length; i++) {
                if (videoFormats[i] == videoSettings.outputFormat()) {
                    videoFormatDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set codec dropdown (model already populated by populateVideoCodecCombo)
        // Requirement REQ-VID-1.1, REQ-VID-1.2, REQ-VID-1.3: Load GPU codec settings
        if (videoCodecDropdown != null && videoSettings.codec() != null) {
            for (int i = 0; i < VIDEO_CODECS.length; i++) {
                if (VIDEO_CODECS[i].equals(videoSettings.codec())) {
                    videoCodecDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set bitrate
        if (videoBitrateSpinButton != null) {
            videoBitrateSpinButton.setValue(videoSettings.bitrate());
        }

        // Set resolution
        if (videoSettings.resolution() != null) {
            if (videoResolutionDropdown != null) {
                // Presets select their own label; custom resolutions select "Custom"
                videoResolutionDropdown.setSelected(videoDropdownIndexForResolution(videoSettings.resolution()));
            }

            if (videoWidthEntry != null) {
                videoWidthEntry.setText(String.valueOf(videoSettings.resolution().getWidth()));
            }

            if (videoHeightEntry != null) {
                videoHeightEntry.setText(String.valueOf(videoSettings.resolution().getHeight()));
            }
        } else {
            if (videoResolutionDropdown != null) {
                videoResolutionDropdown.setSelected(0); // "Original"
            }
        }

        // Set aspect ratio
        if (videoAspectRatioDropdown != null && videoSettings.aspectRatio() != null) {
            AspectRatio aspectRatio = videoSettings.aspectRatio();
            int aspectRatioIndex = aspectRatio.ordinal();
            videoAspectRatioDropdown.setSelected(aspectRatioIndex);
        }

        // Set frame rate
        if (videoFrameRateDropdown != null) {
            videoFrameRateDropdown.setSelected(videoDropdownIndexForFrameRate(videoSettings.frameRate()));
        }

        // Set preset
        if (videoPresetDropdown != null && videoSettings.preset() != null) {
            for (int i = 0; i < VIDEO_PRESETS.length; i++) {
                if (VIDEO_PRESETS[i].equals(videoSettings.preset())) {
                    videoPresetDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set CRF
        if (videoCrfScale != null) {
            videoCrfScale.setValue(videoSettings.crf());
        }
    }

    /**
     * Populates audio settings tab widgets.
     * Requirement REQ-006.2: Populate audio codec, bitrate, sample rate, etc.
     * 
     * @param audioSettings the audio settings
     */
    private void populateAudioSettings(AudioSettings audioSettings) {
        logger.debug("Populating audio settings");

        // Set output format dropdown (Requirement REQ-2.3)
        if (audioFormatDropdown != null && audioSettings.outputFormat() != null) {
            FileFormat[] audioFormats = FileFormat.getFormatsByCategory(FormatCategory.AUDIO);
            for (int i = 0; i < audioFormats.length; i++) {
                if (audioFormats[i] == audioSettings.outputFormat()) {
                    audioFormatDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set codec dropdown
        // Requirement REQ-AUD-1.1: Load codec and update UI controls based on copy
        // codec selection
        if (audioCodecDropdown != null && audioSettings.codec() != null) {
            for (int i = 0; i < AUDIO_CODECS.length; i++) {
                if (AUDIO_CODECS[i].equals(audioSettings.codec())) {
                    audioCodecDropdown.setSelected(i);
                    break;
                }
            }
            // Update encoding controls state after setting codec (disable if copy codec)
            updateAudioEncodingControlsState();
        }

        // Set bitrate
        if (audioBitrateSpinButton != null) {
            audioBitrateSpinButton.setValue(audioSettings.bitrate());
        }

        // Set sample rate
        if (audioSampleRateDropdown != null) {
            int sampleRateIndex = indexOf(AUDIO_SAMPLE_RATES, audioSettings.sampleRate());
            audioSampleRateDropdown.setSelected(sampleRateIndex);
        }

        // Set channels
        if (audioChannelsDropdown != null) {
            int channelsIndex = indexOf(AUDIO_CHANNELS, audioSettings.channels());
            audioChannelsDropdown.setSelected(channelsIndex);
        }

        // Set quality scale
        if (audioQualityScale != null) {
            audioQualityScale.setValue(audioSettings.quality());
        }
    }

    /**
     * Populates image settings tab widgets.
     * Requirement REQ-006.3: Populate image quality, resolution, compression, etc.
     * 
     * @param imageSettings the image settings
     */
    private void populateImageSettings(ImageSettings imageSettings) {
        logger.debug("Populating image settings");

        // Set output format dropdown (Requirement REQ-2.4)
        if (imageFormatDropdown != null && imageSettings.outputFormat() != null) {
            FileFormat[] imageFormats = FileFormat.getFormatsByCategory(FormatCategory.IMAGE);
            for (int i = 0; i < imageFormats.length; i++) {
                if (imageFormats[i] == imageSettings.outputFormat()) {
                    imageFormatDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set quality scale
        if (imageQualityScale != null) {
            imageQualityScale.setValue(imageSettings.quality());
        }

        // Clear dimensions when restoring an original-size preset.
        if (imageWidthSpinButton != null) {
            imageWidthSpinButton.setValue(imageSettings.resolution() == null ? 0 : imageSettings.resolution().getWidth());
        }
        if (imageHeightSpinButton != null) {
            imageHeightSpinButton.setValue(imageSettings.resolution() == null ? 0 : imageSettings.resolution().getHeight());
        }

        // Set maintain aspect ratio checkbox
        if (maintainAspectRatioCheckbox != null) {
            maintainAspectRatioCheckbox.setActive(imageSettings.maintainAspectRatio());
        }

        // Set compression level
        if (compressionLevelScale != null) {
            compressionLevelScale.setValue(imageSettings.compressionLevel());
        }

        // Set resize mode
        if (resizeModeDropdown != null && imageSettings.resizeMode() != null) {
            int resizeModeIndex = imageSettings.resizeMode().ordinal();
            resizeModeDropdown.setSelected(resizeModeIndex);
        }

        // Set rotation dropdown based on ImageRotation enum (T-8.5)
        if (imageRotationDropdown != null && imageSettings.rotation() != null) {
            ImageRotation rotation = imageSettings.rotation();
            for (int i = 0; i < ImageRotation.values().length; i++) {
                if (ImageRotation.values()[i] == rotation) {
                    imageRotationDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set flip dropdown based on ImageFlip enum (T-8.6)
        if (imageFlipDropdown != null && imageSettings.flip() != null) {
            ImageFlip flip = imageSettings.flip();
            for (int i = 0; i < ImageFlip.values().length; i++) {
                if (ImageFlip.values()[i] == flip) {
                    imageFlipDropdown.setSelected(i);
                    break;
                }
            }
        }
    }

    /**
     * Populates document settings tab widgets.
     * Requirement REQ-006.4: Populate document template, formatting, margins, etc.
     * 
     * @param documentSettings the document settings
     */
    private void populateDocumentSettings(DocumentSettings documentSettings) {
        logger.debug("Populating document settings");

        // Set output format dropdown (Requirement REQ-2.5)
        if (documentFormatDropdown != null && documentSettings.outputFormat() != null) {
            FileFormat[] documentFormats = availableDocumentFormats;
            for (int i = 0; i < documentFormats.length; i++) {
                if (documentFormats[i] == documentSettings.outputFormat()) {
                    documentFormatDropdown.setSelected(i);
                    break;
                }
            }
        }

        // Set template file path
        if (templateFileEntry != null) {
            String templatePath = documentSettings.templatePath() != null
                    ? documentSettings.templatePath().toString()
                    : "";
            templateFileEntry.setText(templatePath);
        }

        // Set formatting checkboxes
        if (preserveFormattingCheckbox != null) {
            preserveFormattingCheckbox.setActive(documentSettings.preserveFormatting());
        }

        if (embedFontsCheckbox != null) {
            embedFontsCheckbox.setActive(documentSettings.embedFonts());
        }

        if (generateTocCheckbox != null) {
            generateTocCheckbox.setActive(documentSettings.generateTableOfContents());
        }

        // Set margins
        if (marginTopSpinButton != null) {
            marginTopSpinButton.setValue(documentSettings.marginTop());
        }

        if (marginBottomSpinButton != null) {
            marginBottomSpinButton.setValue(documentSettings.marginBottom());
        }

        if (marginLeftSpinButton != null) {
            marginLeftSpinButton.setValue(documentSettings.marginLeft());
        }

        if (marginRightSpinButton != null) {
            marginRightSpinButton.setValue(documentSettings.marginRight());
        }
        updateDocumentControls();
    }

    /**
     * Reads video settings from the UI widgets.
     * 
     * @return VideoSettings object, or null if not applicable
     */
    private VideoSettings readVideoSettings() {
        if (videoCodecDropdown == null)
            return null;

        VideoSettings.Builder builder = VideoSettings.builder();

        // Output format (Requirement REQ-2.2)
        if (videoFormatDropdown != null) {
            int formatIndex = videoFormatDropdown.getSelected();
            if (formatIndex >= 0) {
                FileFormat[] videoFormats = FileFormat.getFormatsByCategory(FormatCategory.VIDEO);
                if (formatIndex < videoFormats.length) {
                    builder.outputFormat(videoFormats[formatIndex]);
                }
            }
        }

        // Codec
        int codecIndex = videoCodecDropdown.getSelected();
        if (codecIndex >= 0) {
            if (codecIndex < VIDEO_CODECS.length) {
                builder.codec(VIDEO_CODECS[codecIndex]);
            }
        }

        // Bitrate
        if (videoBitrateSpinButton != null) {
            builder.bitrate((int) videoBitrateSpinButton.getValue());
        }

        // Resolution
        Resolution resolution = null;
        if (videoResolutionDropdown != null) {
            int resIndex = videoResolutionDropdown.getSelected();
            if (resIndex == RESOLUTION_INDEX_CUSTOM) {
                // Custom: manual width/height entry
                if (videoWidthEntry != null && videoHeightEntry != null) {
                    try {
                        int width = Integer.parseInt(videoWidthEntry.getText());
                        int height = Integer.parseInt(videoHeightEntry.getText());
                        resolution = new Resolution(width, height);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid custom resolution: {}", e.getMessage());
                    }
                }
            } else {
                // Original or a preset, mapped via the shared options array
                resolution = videoResolutionForDropdownIndex(resIndex);
            }
        }
        if (resolution != null) {
            builder.resolution(resolution);
        }

        // Aspect ratio
        if (videoAspectRatioDropdown != null) {
            int aspectRatioIndex = videoAspectRatioDropdown.getSelected();
            if (aspectRatioIndex >= 0 && aspectRatioIndex < AspectRatio.values().length) {
                builder.aspectRatio(AspectRatio.values()[aspectRatioIndex]);
            }
        }

        // Frame rate
        if (videoFrameRateDropdown != null) {
            int frameRateIndex = videoFrameRateDropdown.getSelected();
            builder.frameRate(videoFrameRateForDropdownIndex(frameRateIndex));
        }

        // Preset
        if (videoPresetDropdown != null) {
            int presetIndex = videoPresetDropdown.getSelected();
            if (presetIndex >= 0) {
                if (presetIndex < VIDEO_PRESETS.length) {
                    builder.preset(VIDEO_PRESETS[presetIndex]);
                }
            }
        }

        // CRF
        if (videoCrfScale != null) {
            builder.crf((int) videoCrfScale.getValue());
        }

        return builder.build();
    }

    /**
     * Reads audio settings from the UI widgets.
     * 
     * @return AudioSettings object, or null if not applicable
     */
    private AudioSettings readAudioSettings() {
        if (audioCodecDropdown == null)
            return null;

        AudioSettings.Builder builder = AudioSettings.builder();

        // Output format (Requirement REQ-2.3)
        if (audioFormatDropdown != null) {
            int formatIndex = audioFormatDropdown.getSelected();
            if (formatIndex >= 0) {
                FileFormat[] audioFormats = FileFormat.getFormatsByCategory(FormatCategory.AUDIO);
                if (formatIndex < audioFormats.length) {
                    builder.outputFormat(audioFormats[formatIndex]);
                }
            }
        }

        // Codec
        int codecIndex = audioCodecDropdown.getSelected();
        if (codecIndex >= 0) {
            if (codecIndex < AUDIO_CODECS.length) {
                builder.codec(AUDIO_CODECS[codecIndex]);
            }
        }

        // Bitrate
        if (audioBitrateSpinButton != null) {
            builder.bitrate((int) audioBitrateSpinButton.getValue());
        }

        // Sample rate
        if (audioSampleRateDropdown != null) {
            int sampleRateIndex = audioSampleRateDropdown.getSelected();
            int sampleRate = valueAt(AUDIO_SAMPLE_RATES, sampleRateIndex);
            builder.sampleRate(sampleRate);
        }

        // Channels
        if (audioChannelsDropdown != null) {
            int channelsIndex = audioChannelsDropdown.getSelected();
            int channels = valueAt(AUDIO_CHANNELS, channelsIndex);
            builder.channels(channels);
        }

        // Quality
        if (audioQualityScale != null) {
            builder.quality((int) audioQualityScale.getValue());
        }

        return builder.build();
    }

    /**
     * Reads image settings from the UI widgets.
     * 
     * @return ImageSettings object, or null if not applicable
     */
    private ImageSettings readImageSettings() {
        logger.debug("Reading image settings from UI");

        if (imageQualityScale == null) {
            logger.warn("imageQualityScale is null, returning null for ImageSettings");
            return null;
        }

        ImageSettings.Builder builder = ImageSettings.builder();
        logger.debug("Created ImageSettings.Builder");

        // Output format (Requirement REQ-2.4)
        if (imageFormatDropdown != null) {
            int formatIndex = imageFormatDropdown.getSelected();
            logger.debug("Image format dropdown selected index: {}", formatIndex);

            if (formatIndex >= 0) {
                FileFormat[] imageFormats = FileFormat.getFormatsByCategory(FormatCategory.IMAGE);
                logger.debug("Available image formats count: {}", imageFormats.length);

                if (formatIndex < imageFormats.length) {
                    FileFormat selectedFormat = imageFormats[formatIndex];
                    builder.outputFormat(selectedFormat);
                    logger.debug("Set image output format to: {}", selectedFormat);
                } else {
                    logger.warn("Format index {} is out of bounds (available: {})", formatIndex, imageFormats.length);
                }
            } else {
                logger.warn("No image format selected (index is negative)");
            }
        } else {
            logger.warn("imageFormatDropdown is null");
        }

        // Quality
        builder.quality((int) imageQualityScale.getValue());

        // Resolution
        if (imageWidthSpinButton != null && imageHeightSpinButton != null) {
            int width = (int) imageWidthSpinButton.getValue();
            int height = (int) imageHeightSpinButton.getValue();
            if (width > 0 && height > 0) {
                builder.resolution(new Resolution(width, height));
            }
        }

        // Maintain aspect ratio
        if (maintainAspectRatioCheckbox != null) {
            builder.maintainAspectRatio(maintainAspectRatioCheckbox.getActive());
        }

        // Compression level
        if (compressionLevelScale != null) {
            builder.compressionLevel((int) compressionLevelScale.getValue());
        }

        // Resize mode
        if (resizeModeDropdown != null) {
            int resizeModeIndex = resizeModeDropdown.getSelected();
            ResizeMode resizeMode = resizeModeIndex >= 0 && resizeModeIndex < ResizeMode.values().length
                    ? ResizeMode.values()[resizeModeIndex] : ResizeMode.NONE;
            builder.resizeMode(resizeMode);
        }

        // Read rotation dropdown and map to ImageRotation enum (T-8.5)
        if (imageRotationDropdown != null) {
            int rotationIndex = imageRotationDropdown.getSelected();
            if (rotationIndex >= 0 && rotationIndex < ImageRotation.values().length) {
                builder.rotation(ImageRotation.values()[rotationIndex]);
            }
        }

        // Read flip dropdown and map to ImageFlip enum (T-8.6)
        if (imageFlipDropdown != null) {
            int flipIndex = imageFlipDropdown.getSelected();
            if (flipIndex >= 0 && flipIndex < ImageFlip.values().length) {
                builder.flip(ImageFlip.values()[flipIndex]);
            }
        }

        ImageSettings imageSettings = builder.build();
        logger.debug("Built ImageSettings object: {}", imageSettings);
        logger.debug("=== readImageSettings() returning non-null ImageSettings ===");
        return imageSettings;
    }

    /**
     * Reads document settings from the UI widgets.
     * 
     * @return DocumentSettings object, or null if not applicable
     */
    private DocumentSettings readDocumentSettings() {
        if (templateFileEntry == null)
            return null;

        DocumentSettings.Builder builder = DocumentSettings.builder();

        // Output format (Requirement REQ-2.5)
        if (documentFormatDropdown != null) {
            int formatIndex = documentFormatDropdown.getSelected();
            if (formatIndex >= 0) {
                FileFormat[] documentFormats = availableDocumentFormats;
                if (formatIndex < documentFormats.length) {
                    builder.outputFormat(documentFormats[formatIndex]);
                }
            }
        }

        // Template path
        String templatePath = templateFileEntry.getText();
        if (!templatePath.isEmpty()) {
            builder.templatePath(Paths.get(templatePath));
        }

        // Preserve formatting
        if (preserveFormattingCheckbox != null) {
            builder.preserveFormatting(preserveFormattingCheckbox.getActive());
        }

        // Embed fonts
        if (embedFontsCheckbox != null) {
            builder.embedFonts(embedFontsCheckbox.getActive());
        }

        // Generate TOC
        if (generateTocCheckbox != null) {
            builder.generateTableOfContents(generateTocCheckbox.getActive());
        }

        // Margins
        if (marginTopSpinButton != null) {
            builder.marginTop((int) marginTopSpinButton.getValue());
        }
        if (marginBottomSpinButton != null) {
            builder.marginBottom((int) marginBottomSpinButton.getValue());
        }
        if (marginLeftSpinButton != null) {
            builder.marginLeft((int) marginLeftSpinButton.getValue());
        }
        if (marginRightSpinButton != null) {
            builder.marginRight((int) marginRightSpinButton.getValue());
        }

        return builder.build();
    }

    /**
     * Gets the current settings from the dialog widgets.
     * This method will read all UI widgets and construct a ConversionSettings
     * object.
     * 
     * @return the settings constructed from UI widgets, or null if validation fails
     */
    public ConversionSettings getConversionSettings() {
        logger.debug("Reading settings from dialog");

        try {
            ConversionSettings.Builder builder = ConversionSettings.builder();
            int languageIndex = (int) languageDropdown.getSelected();
            builder.language(languageIndex >= 0 && languageIndex < I18n.LANGUAGE_TAGS.size()
                    ? I18n.LANGUAGE_TAGS.get(languageIndex) : "system");

            // Read general tab settings
            if (outputDirectoryEntry != null) {
                String pathStr = outputDirectoryEntry.getText();
                if (!pathStr.isEmpty()) {
                    builder.outputDirectory(Paths.get(pathStr));
                }
            }

            if (overwriteExistingCheckbox != null) {
                builder.overwriteExisting(overwriteExistingCheckbox.getActive());
            }

            if (createSubdirectoryCheckbox != null) {
                builder.createSubdirectory(createSubdirectoryCheckbox.getActive());
            }

            // Read delete original file checkbox (T-8.7, REQ-GEN-1.1)
            if (deleteOriginalFileCheckbox != null) {
                builder.deleteOriginalFile(deleteOriginalFileCheckbox.getActive());
            }

            if (parallelConversionsSpinButton != null) {
                builder.parallelConversions((int) parallelConversionsSpinButton.getValue());
            }

            // Read video settings
            VideoSettings videoSettings = readVideoSettings();
            if (videoSettings != null) {
                builder.videoSettings(videoSettings);
            }

            // Read audio settings
            AudioSettings audioSettings = readAudioSettings();
            if (audioSettings != null) {
                builder.audioSettings(audioSettings);
            }

            // Read image settings
            ImageSettings imageSettings = readImageSettings();
            logger.debug("readImageSettings() returned: {}", imageSettings);
            if (imageSettings != null) {
                builder.imageSettings(imageSettings);
                logger.debug("Added imageSettings to ConversionSettings builder");
            } else {
                logger.warn("imageSettings is null, not adding to builder");
            }

            // Read document settings
            DocumentSettings documentSettings = readDocumentSettings();
            if (documentSettings != null) {
                builder.documentSettings(documentSettings);
            }

            ConversionSettings settings = builder.build();
            logger.debug("Settings read successfully. Final imageSettings: {}", settings.imageSettings());
            return settings;

        } catch (Exception e) {
            logger.error("Failed to read settings from dialog", e);
            return null;
        }
    }

    /**
     * Checks if the dialog has unsaved changes.
     * 
     * @return true if there are unsaved changes, false otherwise
     */
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }

    /**
     * Shows the settings dialog.
     */
    public void showDialog() {
        logger.debug("Showing settings dialog");
        dialog.present();
    }

    /**
     * Sets the callback to be invoked when settings are saved.
     * 
     * @param callback the callback to invoke when Save is clicked
     */
    public void setOnSaveCallback(SettingsSaveCallback callback) {
        this.saveCallback = callback;
    }

    /**
     * Closes the settings dialog.
     */
    public void closeDialog() {
        logger.debug("Closing settings dialog");
        // Destroy the window and release the references: a new dialog instance
        // is built per Settings click, so a merely hidden dialog would leak.
        Dialog current = dialog;
        dialog = null;
        builder = null;
        if (current != null) {
            current.destroy();
        }
    }

    /**
     * Validates the settings and returns a detailed error message if invalid.
     * Requirement REQ-003.2: Validate settings with specific error messages.
     * 
     * @param settings the settings to validate
     * @return error message if invalid, null if valid
     */
    private static String validateSettings(ConversionSettings settings) {
        if (settings == null) {
            return I18n.tr("Settings object is null");
        }

        // Validate output format
        if (settings.outputFormat() == null) {
            return I18n.tr("Please select an output format");
        }

        // Validate output directory
        if (settings.outputDirectory() == null) {
            return I18n.tr("Please select an output directory");
        }

        if (!settings.outputDirectory().toFile().exists()) {
            return I18n.tr("Output directory does not exist: ") + settings.outputDirectory();
        }

        if (!settings.outputDirectory().toFile().canWrite()) {
            return I18n.tr("Output directory is not writable: ") + settings.outputDirectory();
        }

        // Validate parallel conversions
        if (settings.parallelConversions() < 1 || settings.parallelConversions() > 16) {
            return I18n.tr("Parallel conversions must be between 1 and 16");
        }

        // Validate format-specific settings: every dialog tab is editable, so
        // all non-null sections must validate regardless of the primary format
        // Validate video settings
        if (settings.videoSettings() != null) {
            VideoSettings video = settings.videoSettings();

            // Validate bitrate
            if (video.bitrate() < VideoSettings.MIN_BITRATE || video.bitrate() > VideoSettings.MAX_BITRATE) {
                return I18n.tr("Video bitrate must be between 500 and 50,000 kbps");
            }

            // Validate CRF
            if (video.crf() < VideoSettings.MIN_CRF || video.crf() > VideoSettings.MAX_CRF) {
                return I18n.tr("Video CRF must be between 0 and 51");
            }

            // Validate resolution if set
            if (video.resolution() != null) {
                if (video.resolution().getWidth() < 1 || video.resolution().getHeight() < 1) {
                    return I18n.tr("Video resolution dimensions must be positive");
                }
                if (video.resolution().getWidth() > 7680 || video.resolution().getHeight() > 4320) {
                    return I18n.tr("Video resolution exceeds maximum (8K)");
                }
            }
        }

        // Validate audio settings
        if (settings.audioSettings() != null) {
            AudioSettings audio = settings.audioSettings();

            // Validate bitrate
            if (audio.bitrate() < AudioSettings.MIN_BITRATE || audio.bitrate() > AudioSettings.MAX_BITRATE) {
                return I18n.tr("Audio bitrate must be between 64 and 320 kbps");
            }

            // Validate quality (0-10)
            if (audio.quality() < 0 || audio.quality() > 10) {
                return I18n.tr("Audio quality must be between 0 and 10");
            }
        }

        // Validate image settings
        if (settings.imageSettings() != null) {
            ImageSettings image = settings.imageSettings();

            // Validate quality (0-100, or -1 for lossless)
            if (!ImageSettings.isValidQuality(image.quality())) {
                return I18n.tr("Image quality must be -1 (lossless) or between 0 and 100");
            }

            // Validate compression level (0-9)
            if (image.compressionLevel() < 0 || image.compressionLevel() > 9) {
                return I18n.tr("Image compression level must be between 0 and 9");
            }

            // Validate resolution if set
            if (image.resolution() != null) {
                if (image.resolution().getWidth() < 1 || image.resolution().getHeight() < 1) {
                    return I18n.tr("Image resolution dimensions must be positive");
                }
                if (image.resolution().getWidth() > 65535 || image.resolution().getHeight() > 65535) {
                    return I18n.tr("Image resolution exceeds maximum (65535x65535)");
                }
            }
        }

        // Validate document settings
        if (settings.documentSettings() != null) {
            DocumentSettings document = settings.documentSettings();

            // Validate template path if set
            if (document.templatePath() != null && !document.templatePath().toFile().exists()) {
                return I18n.tr("Template file does not exist: ") + document.templatePath();
            }

            // Validate margins (0-100mm)
            if (document.marginTop() < 0 || document.marginTop() > 100) {
                return I18n.tr("Top margin must be between 0 and 100 mm");
            }
            if (document.marginBottom() < 0 || document.marginBottom() > 100) {
                return I18n.tr("Bottom margin must be between 0 and 100 mm");
            }
            if (document.marginLeft() < 0 || document.marginLeft() > 100) {
                return I18n.tr("Left margin must be between 0 and 100 mm");
            }
            if (document.marginRight() < 0 || document.marginRight() > 100) {
                return I18n.tr("Right margin must be between 0 and 100 mm");
            }
        }

        // All validations passed
        return null;
    }

    /**
     * Shows a validation error dialog with the specified message.
     * Requirement REQ-003.2: Display validation errors to user.
     * 
     * @param errorMessage the error message to display
     */
    private void showValidationError(String errorMessage) {
        logger.debug("Showing validation error: {}", errorMessage);

        MessageDialog errorDialog = MessageDialog.builder()
                .setTransientFor(dialog)
                .setModal(true)
                .setMessageType(MessageType.ERROR)
                .setText(I18n.tr("Invalid Settings"))
                .setSecondaryText(errorMessage)
                .build();

        errorDialog.addButton(I18n.tr("OK"), ResponseType.OK.getValue());
        errorDialog.onResponse((response) -> {
            // User clicked "OK" - close the error dialog
            errorDialog.destroy();
        });

        errorDialog.show();
    }

    // ==================== Task 24: Preset Name Validation Helper
    // ====================

    /**
     * Validates a preset name for save operation.
     * Requirement REQ-5.4: Input validation for preset names.
     * 
     * @param name the preset name to validate
     * @return error message if invalid, null if valid
     */
    private static String validatePresetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return I18n.tr("Preset name cannot be empty");
        }

        if (!name.equals(name.trim())) {
            return I18n.tr("Preset name cannot start or end with spaces");
        }

        name = name.trim();

        // Check length
        if (name.codePointCount(0, name.length()) > 50) {
            return I18n.tr("Preset name must be 50 characters or less");
        }

        // Check for invalid characters (allow letters, numbers, spaces, hyphens,
        // underscores)
        if (!name.matches("^[\\p{L}\\p{M}\\p{N} _-]+$")) {
            return I18n.tr("Preset name can only contain letters, numbers, spaces, hyphens, and underscores");
        }

        // Reserved names check
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.equals("default") || lowerName.equals("none") ||
                lowerName.equals("custom") || lowerName.startsWith("--")) {
            return I18n.tr("This preset name is reserved");
        }

        return null; // Valid
    }

    // ==================== Task 25: Dropdown Population Methods
    // ====================

    /**
     * Populates video codec dropdown with available codecs.
     * Requirements REQ-4.1, REQ-VID-1.1, REQ-VID-1.2, REQ-VID-1.3: Provide video
     * codec options including GPU codecs.
     */
    private void populateVideoCodecCombo() {
        if (videoCodecDropdown == null) {
            return;
        }

        // Create string list model for GTK 4 DropDown with display names
        // Array index must match VIDEO_CODECS array for proper load/save mapping
        var codecList = new org.gnome.gtk.StringList(new String[] {
                "H.264 (libx264)", // VIDEO_CODECS[0]: "libx264"
                "H.265 (libx265)", // VIDEO_CODECS[1]: "libx265"
                "VP9 (libvpx-vp9)", // VIDEO_CODECS[2]: "libvpx-vp9"
                "MPEG-4 (mpeg4)", // VIDEO_CODECS[3]: "mpeg4"
                "H.264 (NVIDIA GPU)", // VIDEO_CODECS[4]: "h264_nvenc"
                "HEVC (NVIDIA GPU)", "Windows Media Video", "Flash Video"
        });

        videoCodecDropdown.setModel(codecList);
        videoCodecDropdown.setSelected(0); // Default: H.264

        logger.debug("Populated video codec dropdown with {} codecs (including GPU codecs)", codecList.getNItems());
    }

    /**
     * Populates video resolution dropdown with preset resolutions.
     * Requirement REQ-4.2: Provide video resolution presets.
     */
    private void populateVideoResolutionCombo() {
        if (videoResolutionDropdown == null) {
            return;
        }

        var resolutionList = new org.gnome.gtk.StringList(videoResolutionDropdownLabels());

        videoResolutionDropdown.setModel(resolutionList);
        videoResolutionDropdown.setSelected(0); // Default: Original

        // Connect changed signal to enable/disable custom width/height
        videoResolutionDropdown.onNotify("selected", param -> {
            int selected = (int) videoResolutionDropdown.getSelected();
            boolean isCustom = selected == RESOLUTION_INDEX_CUSTOM;

            // Show/hide the custom resolution box
            if (customResolutionBox != null) {
                customResolutionBox.setVisible(isCustom);
            }

            // Enable/disable the individual entry widgets
            if (videoWidthEntry != null) {
                videoWidthEntry.setEditable(isCustom);
                videoWidthEntry.setSensitive(isCustom);
            }
            if (videoHeightEntry != null) {
                videoHeightEntry.setEditable(isCustom);
                videoHeightEntry.setSensitive(isCustom);
            }

            // Mark as changed when resolution is modified
            hasUnsavedChanges = true;
        });

        // Initialize custom resolution box to hidden and width/height entries to
        // disabled state
        // (since default is not "Custom")
        if (customResolutionBox != null) {
            customResolutionBox.setVisible(false);
        }
        if (videoWidthEntry != null) {
            videoWidthEntry.setEditable(false);
            videoWidthEntry.setSensitive(false);
        }
        if (videoHeightEntry != null) {
            videoHeightEntry.setEditable(false);
            videoHeightEntry.setSensitive(false);
        }

        logger.debug("Populated video resolution dropdown with {} options", VIDEO_RESOLUTION_OPTIONS.length + 2);
    }

    /**
     * Populates video frame rate dropdown with common frame rates.
     * Requirement REQ-4.3: Provide frame rate options.
     */
    private void populateVideoFrameRateCombo() {
        if (videoFrameRateDropdown == null) {
            return;
        }

        var frameRateList = new org.gnome.gtk.StringList(videoFrameRateDropdownLabels());

        videoFrameRateDropdown.setModel(frameRateList);
        videoFrameRateDropdown.setSelected(0); // Default: Original

        logger.debug("Populated video frame rate dropdown with {} options", VIDEO_FRAME_RATE_OPTIONS.length + 1);
    }

    /**
     * Populates the video preset quality dropdown with FFmpeg encoding presets.
     * Requirement REQ-4.4: Provide encoding quality/speed presets.
     */
    private void populateVideoPresetQualityCombo() {
        if (videoPresetDropdown == null) {
            return;
        }

        var presetList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr("ultrafast - Fastest encoding, largest file"),
                I18n.tr("superfast - Very fast encoding"),
                I18n.tr("veryfast - Fast encoding"),
                I18n.tr("faster - Faster than default"),
                I18n.tr("fast - Fast encoding"),
                I18n.tr("medium - Balanced (default)"),
                I18n.tr("slow - Better compression"),
                I18n.tr("slower - Much better compression"),
                I18n.tr("veryslow - Best compression, slowest")
        });

        videoPresetDropdown.setModel(presetList);
        videoPresetDropdown.setSelected(5); // Default: medium

        // Set tooltips explaining speed/quality tradeoff
        videoPresetDropdown.setTooltipText(
                I18n.tr("Encoding preset controls speed vs. compression efficiency tradeoff.\n") +
                        I18n.tr("Faster presets = quicker encoding but larger files.\n") +
                        I18n.tr("Slower presets = better compression but longer encoding time."));

        logger.debug("Populated video preset dropdown with {} options", 9);
    }

    /**
     * Populates the video aspect ratio dropdown with supported aspect ratios.
     * Requirement REQ-VID-2.1: Support aspect ratio selection for video conversion.
     */
    private void populateVideoAspectRatioCombo() {
        if (videoAspectRatioDropdown == null) {
            return;
        }

        // Populate with display names from AspectRatio enum
        AspectRatio[] ratios = AspectRatio.values();
        String[] displayNames = new String[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            displayNames[i] = I18n.tr(ratios[i].getDisplayName());
        }

        var ratioList = new org.gnome.gtk.StringList(displayNames);
        videoAspectRatioDropdown.setModel(ratioList);
        videoAspectRatioDropdown.setSelected(0); // Default: Keep Original

        videoAspectRatioDropdown.setTooltipText(
                I18n.tr("Video aspect ratio controls the width-to-height proportions.\n") +
                        I18n.tr("Changing aspect ratio may add letterboxing or pillarboxing.\n") +
                        I18n.tr("Keep Original preserves the source video's aspect ratio."));

        logger.debug("Populated video aspect ratio dropdown with {} options", ratios.length);
    }

    /**
     * Populates audio codec dropdown with available audio codecs.
     * Requirements REQ-4.5, REQ-AUD-1.1: Provide audio codec options including copy
     * codec.
     */
    private void populateAudioCodecCombo() {
        if (audioCodecDropdown == null) {
            return;
        }

        // Create string list model for GTK 4 DropDown with display names
        // Array index must match AUDIO_CODECS array for proper load/save mapping
        var codecList = new org.gnome.gtk.StringList(new String[] {
                "AAC (aac)", // AUDIO_CODECS[0]: "aac"
                "MP3 (libmp3lame)", // AUDIO_CODECS[1]: "libmp3lame"
                "Opus (libopus)", // AUDIO_CODECS[2]: "libopus"
                "Vorbis (libvorbis)", // AUDIO_CODECS[3]: "libvorbis"
                "FLAC (flac)", // AUDIO_CODECS[4]: "flac"
                I18n.tr("Copy (No Re-encode)"), "PCM (WAV)", "Apple Lossless"
        });

        audioCodecDropdown.setModel(codecList);
        audioCodecDropdown.setSelected(0); // Default: AAC

        // Requirement REQ-AUD-1.1: Add handler to enable/disable encoding controls
        // based on codec selection
        audioCodecDropdown.onNotify("selected", param -> {
            updateAudioEncodingControlsState();
        });

        logger.debug("Populated audio codec dropdown with {} codecs (including copy codec)", 6);
    }

    /**
     * Updates the enabled/disabled state of audio encoding controls based on
     * selected codec.
     * Requirement REQ-AUD-1.1: Disable encoding controls when copy codec is
     * selected.
     */
    private void updateAudioEncodingControlsState() {
        if (audioCodecDropdown == null) {
            return;
        }

        // Get selected codec
        int codecIndex = (int) audioCodecDropdown.getSelected();
        boolean isCopyCodec = (codecIndex >= 0 && codecIndex < AUDIO_CODECS.length &&
                AUDIO_CODECS[codecIndex].equals("copy"));

        // Disable encoding controls when copy codec is selected
        // Copy mode streams audio without re-encoding, so encoding parameters don't
        // apply
        if (audioBitrateSpinButton != null) {
            audioBitrateSpinButton.setSensitive(!isCopyCodec);
        }
        if (audioSampleRateDropdown != null) {
            audioSampleRateDropdown.setSensitive(!isCopyCodec);
        }
        if (audioChannelsDropdown != null) {
            audioChannelsDropdown.setSensitive(!isCopyCodec);
        }
        if (audioQualityScale != null) {
            audioQualityScale.setSensitive(!isCopyCodec);
        }

        logger.debug("Audio encoding controls {} (copy codec: {})",
                isCopyCodec ? "disabled" : "enabled", isCopyCodec);
    }

    /**
     * Populates audio sample rate dropdown with common sample rates.
     * Requirement REQ-4.6: Provide sample rate options.
     */
    private void populateAudioSampleRateCombo() {
        if (audioSampleRateDropdown == null) {
            return;
        }

        var sampleRateList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr("Original"),
                "8000 Hz",
                "11025 Hz",
                "16000 Hz",
                "22050 Hz",
                "32000 Hz",
                I18n.tr("44100 Hz (CD Quality)"),
                "48000 Hz",
                "88200 Hz",
                "96000 Hz",
                "192000 Hz"
        });

        audioSampleRateDropdown.setModel(sampleRateList);
        audioSampleRateDropdown.setSelected(0); // Default: Original

        logger.debug("Populated audio sample rate dropdown with {} options", 11);
    }

    /**
     * Populates audio channels dropdown with channel configuration options.
     * Requirement REQ-4.7: Provide audio channel options.
     */
    private void populateAudioChannelsCombo() {
        if (audioChannelsDropdown == null) {
            return;
        }

        var channelsList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr("Original"),
                I18n.tr("Mono (1)"),
                I18n.tr("Stereo (2)"),
                "2.1",
                I18n.tr("5.1 Surround"),
                I18n.tr("7.1 Surround")
        });

        audioChannelsDropdown.setModel(channelsList);
        audioChannelsDropdown.setSelected(0); // Default: Original

        logger.debug("Populated audio channels dropdown with {} options", 6);
    }

    /**
     * Populates image resize mode dropdown with available resize algorithms.
     * Requirement REQ-4.8: Provide image resize mode options.
     */
    private void populateImageResizeModeCombo() {
        if (resizeModeDropdown == null) {
            return;
        }

        var resizeModeList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr("None - Keep original size"),
                I18n.tr("Fit - Scale to fit within dimensions"),
                I18n.tr("Fill - Scale to fill dimensions"),
                I18n.tr("Stretch - Stretch to exact dimensions"),
                I18n.tr("Lanczos - High quality resampling"),
                I18n.tr("Bicubic - Good quality resampling"),
                I18n.tr("Bilinear - Fast resampling"),
                I18n.tr("Nearest Neighbor - Fastest, pixelated")
        });

        resizeModeDropdown.setModel(resizeModeList);
        resizeModeDropdown.setSelected(0); // Default: None

        // Connect changed signal to enable/disable width/height when mode != NONE
        resizeModeDropdown.onNotify("selected", param -> {
            int selected = (int) resizeModeDropdown.getSelected();
            boolean enableResize = selected > 0; // Any mode except "None"

            if (imageWidthSpinButton != null) {
                imageWidthSpinButton.setSensitive(enableResize);
            }
            if (imageHeightSpinButton != null) {
                imageHeightSpinButton.setSensitive(enableResize);
            }
            if (maintainAspectRatioCheckbox != null) {
                maintainAspectRatioCheckbox.setSensitive(enableResize);
            }
        });

        logger.debug("Populated image resize mode dropdown with {} options", 8);
    }

    /**
     * Populates image rotation dropdown with available rotation options.
     * Requirement REQ-IMG-1.1: Provide image rotation options (None, 90° CW, 180°,
     * 90° CCW).
     */
    private void populateImageRotationCombo() {
        if (imageRotationDropdown == null) {
            return;
        }

        // Create string list with ImageRotation enum display names
        // Array index must match ImageRotation enum ordinal for proper load/save
        // mapping
        var rotationList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr(ImageRotation.NONE.getDisplayName()), // 0: "None"
                I18n.tr(ImageRotation.CLOCKWISE_90.getDisplayName()), // 1: "90° Clockwise"
                I18n.tr(ImageRotation.ROTATE_180.getDisplayName()), // 2: "180°"
                I18n.tr(ImageRotation.COUNTER_CLOCKWISE_90.getDisplayName()) // 3: "90° Counter-Clockwise"
        });

        imageRotationDropdown.setModel(rotationList);
        imageRotationDropdown.setSelected(0); // Default: None

        logger.debug("Populated image rotation dropdown with {} options", ImageRotation.values().length);
    }

    /**
     * Populates image flip dropdown with available flip options.
     * Requirement REQ-IMG-2.1: Provide image flip options (None, Horizontal,
     * Vertical, Both).
     */
    private void populateImageFlipCombo() {
        if (imageFlipDropdown == null) {
            return;
        }

        // Create string list with ImageFlip enum display names
        // Array index must match ImageFlip enum ordinal for proper load/save mapping
        var flipList = new org.gnome.gtk.StringList(new String[] {
                I18n.tr(ImageFlip.NONE.getDisplayName()), // 0: "None"
                I18n.tr(ImageFlip.HORIZONTAL.getDisplayName()), // 1: "Horizontal"
                I18n.tr(ImageFlip.VERTICAL.getDisplayName()), // 2: "Vertical"
                I18n.tr(ImageFlip.BOTH.getDisplayName()) // 3: "Both"
        });

        imageFlipDropdown.setModel(flipList);
        imageFlipDropdown.setSelected(0); // Default: None

        logger.debug("Populated image flip dropdown with {} options", ImageFlip.values().length);
    }
}
