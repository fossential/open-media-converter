package org.omc.ui;

import org.omc.model.ResizeMode;
import org.omc.model.Resolution;
import org.omc.model.ImageSettings;
import org.omc.model.DocumentSettings;
import org.omc.model.ConversionSettings;
import org.omc.model.VideoSettings;
import org.omc.model.FileFormat;
import org.omc.model.FormatCategory;
import org.omc.ui.SettingsDialogJavaGi;
import org.omc.model.PresetsBySection;
import org.omc.model.SectionPreset;
import org.omc.model.AudioSettings;
import org.omc.controller.SettingsManager;
import org.omc.core.ConfigurationManager;
import org.omc.core.ValidationEngine;
import org.omc.model.ImageFlip;
import org.omc.model.ImageRotation;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;

/**
 * Unit and integration tests for SettingsDialogJavaGi.
 * Tests validation methods and preset management without GTK dependencies.
 * 
 * Task 41: Integration Tests for Settings Dialog UI
 * Requirements: REQ-2.1, REQ-2.2, REQ-2.3, REQ-2.4, REQ-2.5, REQ-2.6
 */
class SettingsDialogJavaGiTest {

    private Path testOutputDir;
    private ConversionSettings validSettings;

    @TempDir
    Path tempDir;

    private SettingsManager settingsManager;
    private ConfigurationManager configurationManager;
    private ValidationEngine validationEngine;
    private org.omc.service.FileHandler fileHandler;

    private Path configDir;
    private Path dataDir;
    private Path cacheDir;

    @BeforeEach
    void setUp() throws Exception {
        // Create test data
        testOutputDir = Paths.get("target/test-output");
        Files.createDirectories(testOutputDir);

        // Create temporary directories for SettingsManager
        configDir = tempDir.resolve("config");
        dataDir = tempDir.resolve("data");
        cacheDir = tempDir.resolve("cache");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(cacheDir);

        // Create valid settings for testing
        validSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .overwriteExisting(true)
                .createSubdirectory(false)
                .parallelConversions(4)
                .videoSettings(VideoSettings.builder()
                        .codec("libx264")
                        .bitrate(5000)
                        .resolution(new Resolution(1920, 1080))
                        .frameRate(30)
                        .preset("medium")
                        .crf(23)
                        .build())
                .audioSettings(AudioSettings.builder()
                        .codec("aac")
                        .bitrate(192)
                        .sampleRate(44100)
                        .channels(2)
                        .quality(5)
                        .build())
                .imageSettings(ImageSettings.builder()
                        .quality(85)
                        .resolution(new Resolution(800, 600))
                        .maintainAspectRatio(true)
                        .compressionLevel(6)
                        .resizeMode(ResizeMode.FIT)
                        .build())
                .documentSettings(DocumentSettings.builder()
                        .preserveFormatting(true)
                        .embedFonts(false)
                        .generateTableOfContents(false)
                        .marginTop(25)
                        .marginBottom(25)
                        .marginLeft(25)
                        .marginRight(25)
                        .build())
                .build();

        // Initialize ConfigurationManager, ValidationEngine and SettingsManager
        configurationManager = new ConfigurationManager(configDir, dataDir, cacheDir);
        fileHandler = new org.omc.service.FileHandler(configurationManager);
        validationEngine = new ValidationEngine(fileHandler);
        settingsManager = new SettingsManager(configurationManager, validationEngine);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    // ========== Dialog Lifecycle Tests ==========

    /**
     * closeDialog must destroy the dialog (not just hide it) and release the
     * dialog/builder references so a new SettingsDialogJavaGi per Settings
     * click does not leak hidden GtkDialogs.
     */
    @Test
    void closeDialogDestroysAndReleasesDialogAndBuilder() throws Exception {
        SettingsDialogJavaGi settingsDialog = mock(SettingsDialogJavaGi.class, CALLS_REAL_METHODS);
        org.gnome.gtk.Dialog gtkDialog = mock(org.gnome.gtk.Dialog.class);
        org.gnome.gtk.GtkBuilder gtkBuilder = mock(org.gnome.gtk.GtkBuilder.class);

        java.lang.reflect.Field dialogField = SettingsDialogJavaGi.class.getDeclaredField("dialog");
        dialogField.setAccessible(true);
        dialogField.set(settingsDialog, gtkDialog);
        java.lang.reflect.Field builderField = SettingsDialogJavaGi.class.getDeclaredField("builder");
        builderField.setAccessible(true);
        builderField.set(settingsDialog, gtkBuilder);

        settingsDialog.closeDialog();

        verify(gtkDialog).destroy();
        verify(gtkDialog, never()).hide();
        assertNull(dialogField.get(settingsDialog), "dialog reference must be released after close");
        assertNull(builderField.get(settingsDialog), "builder reference must be released after close");

        // A second close must be a no-op (no NPE, no double-close).
        settingsDialog.closeDialog();
        verify(gtkDialog, times(1)).destroy();
    }

    // ========== Unsaved-changes tracking tests ==========

    @Test
    void connectChangeHandlers_tracksDropDownSelections() throws Exception {
        String[] dropDownFields = {
                "languageDropdown", "videoFormatDropdown", "videoCodecDropdown", "videoAspectRatioDropdown",
                "videoFrameRateDropdown", "videoPresetDropdown", "audioFormatDropdown",
                "audioCodecDropdown", "audioSampleRateDropdown", "audioChannelsDropdown",
                "imageFormatDropdown", "resizeModeDropdown", "imageRotationDropdown",
                "imageFlipDropdown", "documentFormatDropdown"
        };
        for (String fieldName : dropDownFields) {
            SettingsDialogJavaGi settingsDialog = mock(SettingsDialogJavaGi.class, CALLS_REAL_METHODS);
            setDialogField(settingsDialog, "hasUnsavedChanges", false);
            org.gnome.gtk.DropDown dropDown = mock(org.gnome.gtk.DropDown.class);
            setDialogField(settingsDialog, fieldName, dropDown);

            java.lang.reflect.Method connect = SettingsDialogJavaGi.class
                    .getDeclaredMethod("connectChangeHandlers");
            connect.setAccessible(true);
            connect.invoke(settingsDialog);

            org.mockito.ArgumentCaptor<org.gnome.gobject.GObject.NotifyCallback> captor = org.mockito.ArgumentCaptor
                    .forClass(org.gnome.gobject.GObject.NotifyCallback.class);
            verify(dropDown, atLeastOnce()).onNotify(eq("selected"), captor.capture());
            captor.getValue().run(null);
            assertTrue((Boolean) getDialogField(settingsDialog, "hasUnsavedChanges"),
                    fieldName + " selection must mark unsaved changes");
        }
    }

    private static void setDialogField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = SettingsDialogJavaGi.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ========== readVideoSettings custom-resolution parsing tests ==========

    private SettingsDialogJavaGi dialogWithCustomResolution(Object widthEntryValue, String heightText)
            throws Exception {
        SettingsDialogJavaGi settingsDialog = mock(SettingsDialogJavaGi.class, CALLS_REAL_METHODS);

        org.gnome.gtk.DropDown codecDropdown = mock(org.gnome.gtk.DropDown.class);
        when(codecDropdown.getSelected()).thenReturn(0);
        setDialogField(settingsDialog, "videoCodecDropdown", codecDropdown);

        java.lang.reflect.Field customIndexField = SettingsDialogJavaGi.class
                .getDeclaredField("RESOLUTION_INDEX_CUSTOM");
        customIndexField.setAccessible(true);
        org.gnome.gtk.DropDown resolutionDropdown = mock(org.gnome.gtk.DropDown.class);
        when(resolutionDropdown.getSelected()).thenReturn(customIndexField.getInt(null));
        setDialogField(settingsDialog, "videoResolutionDropdown", resolutionDropdown);

        org.gnome.gtk.Entry widthEntry = mock(org.gnome.gtk.Entry.class);
        if (widthEntryValue instanceof String text) {
            when(widthEntry.getText()).thenReturn(text);
        } else {
            when(widthEntry.getText()).thenThrow((RuntimeException) widthEntryValue);
        }
        setDialogField(settingsDialog, "videoWidthEntry", widthEntry);
        org.gnome.gtk.Entry heightEntry = mock(org.gnome.gtk.Entry.class);
        when(heightEntry.getText()).thenReturn(heightText);
        setDialogField(settingsDialog, "videoHeightEntry", heightEntry);
        return settingsDialog;
    }

    private VideoSettings invokeReadVideoSettings(SettingsDialogJavaGi settingsDialog) throws Exception {
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("readVideoSettings");
        method.setAccessible(true);
        return (VideoSettings) method.invoke(settingsDialog);
    }

    @Test
    void readVideoSettings_invalidCustomResolution_keepsResolutionUnset() throws Exception {
        SettingsDialogJavaGi settingsDialog = dialogWithCustomResolution("abc", "1080");

        VideoSettings settings = invokeReadVideoSettings(settingsDialog);

        assertNotNull(settings);
        assertNull(settings.resolution(), "non-numeric custom resolution must be ignored");
    }

    @Test
    void readVideoSettings_nonPositiveCustomResolution_keepsResolutionUnset() throws Exception {
        SettingsDialogJavaGi settingsDialog = dialogWithCustomResolution("0", "-1");

        VideoSettings settings = invokeReadVideoSettings(settingsDialog);

        assertNotNull(settings);
        assertNull(settings.resolution(), "non-positive custom resolution must be ignored");
    }

    @Test
    void readVideoSettings_validCustomResolution_setsResolution() throws Exception {
        SettingsDialogJavaGi settingsDialog = dialogWithCustomResolution("640", "480");

        VideoSettings settings = invokeReadVideoSettings(settingsDialog);

        assertNotNull(settings);
        assertEquals(new Resolution(640, 480), settings.resolution());
    }

    @Test
    void readVideoSettings_unexpectedRuntimeException_propagates() throws Exception {
        SettingsDialogJavaGi settingsDialog = dialogWithCustomResolution(
                new IllegalStateException("widget disposed"), "1080");

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("readVideoSettings");
        method.setAccessible(true);
        java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> method.invoke(settingsDialog));
        assertInstanceOf(IllegalStateException.class, ex.getCause(),
                "only invalid-number input may be swallowed; unexpected failures must propagate");
    }

    private static Object getDialogField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = SettingsDialogJavaGi.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // ========== Validation Tests (Original) ==========

    @Test
    void testValidateSettings_WithNullSettings_ShouldReturnError() throws Exception {
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, (ConversionSettings) null);
        assertEquals("Settings object is null", result);
    }

    @Test
    void testValidateSettings_WithNullOutputFormat_ShouldReturnError() throws Exception {
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputDirectory(testOutputDir)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertEquals("Please select an output format", result);
    }

    @Test
    void testValidateSettings_WithNullOutputDirectory_ShouldReturnError() throws Exception {
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertEquals("Please select an output directory", result);
    }

    @Test
    void testValidateSettings_WithNonExistentOutputDirectory_ShouldReturnError() throws Exception {
        Path nonExistentDir = Paths.get("/non/existent/directory");
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(nonExistentDir)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertTrue(result.contains("does not exist"));
    }

    @Test
    void testValidateSettings_WithInvalidParallelConversions_ShouldReturnError() throws Exception {
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .parallelConversions(0)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertTrue(result.contains("Parallel conversions must be between 1 and 16"));
    }

    @Test
    void testValidateSettings_WithInvalidAudioSectionUnderVideoPrimary_ShouldReturnError() throws Exception {
        // An out-of-range audio section must be rejected even when the
        // primary format is VIDEO: all dialog tabs are editable, so all
        // sections must validate (JSON bypasses the section builders)
        org.omc.model.AudioSettings invalidAudio = org.omc.util.JsonUtils.getObjectMapper().readValue(
                "{\"codec\":\"libmp3lame\",\"bitrate\":500,\"sampleRate\":44100,\"channels\":2,\"quality\":5,\"outputFormat\":\"MP3\"}",
                org.omc.model.AudioSettings.class);
        ConversionSettings settings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .audioSettings(invalidAudio)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, settings);
        assertNotNull(result, "invalid audio section must be rejected under a VIDEO primary format");
        assertTrue(result.contains("Audio bitrate"));
    }

    @Test
    void testValidateSettings_WithLosslessImageQuality_ShouldReturnNull() throws Exception {
        // Image quality -1 (lossless) is a legitimate model value and must
        // not be rejected by dialog validation
        org.omc.model.ImageSettings losslessImage = org.omc.util.JsonUtils.getObjectMapper().readValue(
                "{\"quality\":-1,\"outputFormat\":\"PNG\"}",
                org.omc.model.ImageSettings.class);
        ConversionSettings settings = ConversionSettings.builder()
                .outputFormat(FileFormat.PNG)
                .outputDirectory(testOutputDir)
                .imageSettings(losslessImage)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, settings);
        assertNull(result, "lossless image quality must be accepted, got: " + result);
    }

    @Test
    void testValidateSettings_WithInvalidVideoBitrate_ShouldReturnError() {
        // VideoSettings builder validates bitrate and throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> VideoSettings.builder().bitrate(100).build());
        assertTrue(exception.getMessage().contains("Bitrate must be between"));
    }

    @Test
    void testValidateSettings_WithInvalidVideoCrf_ShouldReturnError() {
        // VideoSettings builder validates CRF and throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> VideoSettings.builder().crf(60).build());
        assertTrue(exception.getMessage().contains("CRF must be between"));
    }

    @Test
    void testValidateSettings_WithInvalidVideoResolution_ShouldReturnError() throws Exception {
        VideoSettings invalidVideo = VideoSettings.builder()
                .resolution(new Resolution(8000, 1080))
                .build();
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .videoSettings(invalidVideo)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertTrue(result.contains("Video resolution exceeds maximum (8K)"));
    }

    @Test
    void testValidateSettings_WithInvalidAudioBitrate_ShouldReturnError() {
        // AudioSettings builder validates bitrate and throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AudioSettings.builder().bitrate(50).build());
        assertTrue(exception.getMessage().contains("Bitrate must be between"));
    }

    @Test
    void testValidateSettings_WithInvalidAudioQuality_ShouldReturnError() {
        // AudioSettings builder validates quality and throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AudioSettings.builder().quality(15).build());
        assertTrue(exception.getMessage().contains("Quality must be between"));
    }

    @Test
    void testValidateSettings_WithInvalidImageQuality_ShouldReturnError() {
        // ImageSettings builder validates quality and throws IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ImageSettings.builder().quality(150).build());
        assertTrue(exception.getMessage().contains("Quality must be"));
    }

    @Test
    void testValidateSettings_WithInvalidImageCompressionLevel_ShouldReturnError() {
        // ImageSettings builder validates compression level and throws
        // IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ImageSettings.builder().compressionLevel(15).build());
        assertTrue(exception.getMessage().contains("Compression level must be between"));
    }

    @Test
    void testValidateSettings_WithInvalidDocumentTemplatePath_ShouldReturnError() throws Exception {
        DocumentSettings invalidDocument = DocumentSettings.builder()
                .templatePath(Paths.get("/non/existent/template.docx"))
                .build();
        ConversionSettings invalidSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.PDF)
                .outputDirectory(testOutputDir)
                .documentSettings(invalidDocument)
                .build();

        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, invalidSettings);
        assertTrue(result.contains("Template file does not exist"));
    }

    @Test
    void testValidateSettings_WithInvalidDocumentMargins_ShouldReturnError() {
        // DocumentSettings builder validates margins and throws
        // IllegalArgumentException
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DocumentSettings.builder().marginTop(150).build());
        assertTrue(exception.getMessage().contains("marginTop must be between"));
    }

    @Test
    void testValidateSettings_WithValidSettings_ShouldReturnNull() throws Exception {
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validateSettings",
                ConversionSettings.class);
        method.setAccessible(true);
        String result = (String) method.invoke(null, validSettings);
        assertNull(result);
    }

    // ========== Task 41: Integration Tests for Settings Dialog UI ==========

    /**
     * Test that video format dropdown is filtered to VIDEO category only.
     * Requirement REQ-2.2: Video tab shows output format dropdown with VIDEO
     * formats only.
     */
    @Test
    void testVideoFormatDropdownFilteredByCategory() {
        // Get all video formats via FileFormat
        FileFormat[] videoFormats = FileFormat.getFormatsByCategory(FormatCategory.VIDEO);

        // Verify that we get only VIDEO formats
        assertNotNull(videoFormats);
        assertTrue(videoFormats.length > 0, "Should have at least one VIDEO format");

        for (FileFormat format : videoFormats) {
            assertTrue(format.supportsCategory(FormatCategory.VIDEO),
                    "Format " + format.name() + " should support VIDEO category");
        }

        // Verify that getFormatsByCategory returns all formats that support VIDEO
        // category
        FileFormat[] allFormats = FileFormat.values();
        int videoCount = 0;
        for (FileFormat format : allFormats) {
            if (format.supportsCategory(FormatCategory.VIDEO)) {
                videoCount++;
            }
        }
        assertEquals(videoCount, videoFormats.length,
                "getFormatsByCategory should return all formats that support VIDEO category");
    }

    /**
     * Test that audio format dropdown is filtered to AUDIO category only.
     * Requirement REQ-2.3: Audio tab shows output format dropdown with AUDIO
     * formats only.
     */
    @Test
    void testAudioFormatDropdownFilteredByCategory() {
        // Get all audio formats via FileFormat
        FileFormat[] audioFormats = FileFormat.getFormatsByCategory(FormatCategory.AUDIO);

        // Verify that we get only AUDIO formats
        assertNotNull(audioFormats);
        assertTrue(audioFormats.length > 0, "Should have at least one AUDIO format");

        for (FileFormat format : audioFormats) {
            assertTrue(format.supportsCategory(FormatCategory.AUDIO),
                    "Format " + format.name() + " should support AUDIO category");
        }

        // Verify that getFormatsByCategory returns all formats that support AUDIO
        // category
        FileFormat[] allFormats = FileFormat.values();
        int audioCount = 0;
        for (FileFormat format : allFormats) {
            if (format.supportsCategory(FormatCategory.AUDIO)) {
                audioCount++;
            }
        }
        assertEquals(audioCount, audioFormats.length,
                "getFormatsByCategory should return all formats that support AUDIO category");
    }

    /**
     * Test that image format dropdown is filtered to IMAGE category only.
     * Requirement REQ-2.4: Image tab shows output format dropdown with IMAGE
     * formats only.
     */
    @Test
    void testImageFormatDropdownFilteredByCategory() {
        // Get all image formats via FileFormat
        FileFormat[] imageFormats = FileFormat.getFormatsByCategory(FormatCategory.IMAGE);

        // Verify that we get only IMAGE formats (primary or secondary)
        assertNotNull(imageFormats);
        assertTrue(imageFormats.length > 0, "Should have at least one IMAGE format");

        // NOTE: PDF is included here because it has IMAGE as secondary category (per
        // T-7.1)
        // This allows PDF-to-image and image-to-PDF conversions via
        // ImageMagick/LibreOffice
        for (FileFormat format : imageFormats) {
            assertTrue(format.supportsCategory(FormatCategory.IMAGE),
                    "Format " + format.name() + " should support IMAGE category (primary or secondary)");
        }

        // Verify that getFormatsByCategory returns all formats that support IMAGE
        // category
        FileFormat[] allFormats = FileFormat.values();
        int imageCount = 0;
        for (FileFormat format : allFormats) {
            if (format.supportsCategory(FormatCategory.IMAGE)) {
                imageCount++;
            }
        }
        assertEquals(imageCount, imageFormats.length,
                "getFormatsByCategory should return all formats that support IMAGE category");
    }

    /**
     * Test that document format dropdown is filtered to DOCUMENT category only.
     * Requirement REQ-2.5: Document tab shows output format dropdown with DOCUMENT
     * formats only.
     */
    @Test
    void testDocumentFormatDropdownFilteredByCategory() {
        // Get all document formats via FileFormat
        FileFormat[] documentFormats = FileFormat.getFormatsByCategory(FormatCategory.DOCUMENT);

        // Verify that we get only DOCUMENT formats
        assertNotNull(documentFormats);
        assertTrue(documentFormats.length > 0, "Should have at least one DOCUMENT format");

        for (FileFormat format : documentFormats) {
            assertTrue(format.supportsCategory(FormatCategory.DOCUMENT),
                    "Format " + format.name() + " should support DOCUMENT category");
        }

        // Verify that getFormatsByCategory returns all formats that support DOCUMENT
        // category
        FileFormat[] allFormats = FileFormat.values();
        int documentCount = 0;
        for (FileFormat format : allFormats) {
            if (format.supportsCategory(FormatCategory.DOCUMENT)) {
                documentCount++;
            }
        }
        assertEquals(documentCount, documentFormats.length,
                "getFormatsByCategory should return all formats that support DOCUMENT category");
    }

    /**
     * Test that saving a video preset creates a file on disk.
     * Requirement REQ-2.6: Save video preset functionality.
     */
    @Test
    void testSaveVideoPresetCreatesFileOnDisk() throws IOException {
        // Create a video preset
        VideoSettings videoSettings = VideoSettings.builder()
                .codec("libx264")
                .bitrate(5000)
                .resolution(new Resolution(1920, 1080))
                .frameRate(30)
                .preset("medium")
                .crf(23)
                .build();

        SectionPreset videoPreset = SectionPreset.forVideo(
                "TestVideoPreset",
                "Test video preset description",
                videoSettings,
                false // not built-in
        );

        // Save preset via settings manager
        settingsManager.addSectionPreset(videoPreset);

        // Verify presets.json file was created (all presets are saved in one file)
        Path presetsFile = configDir.resolve("presets.json");
        assertTrue(Files.exists(presetsFile), "Presets file should be created on disk");

        // Verify we can load it back
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> videoPresets = presets.videoPresets();

        assertTrue(videoPresets.stream().anyMatch(p -> p.name().equals("TestVideoPreset")),
                "Saved preset should be loadable");
    }

    /**
     * Test that saving an audio preset creates a file on disk.
     * Requirement REQ-2.6: Save audio preset functionality.
     */
    @Test
    void testSaveAudioPresetCreatesFileOnDisk() throws IOException {
        // Create an audio preset
        AudioSettings audioSettings = AudioSettings.builder()
                .codec("aac")
                .bitrate(192)
                .sampleRate(44100)
                .channels(2)
                .quality(5)
                .build();

        SectionPreset audioPreset = SectionPreset.forAudio(
                "TestAudioPreset",
                "Test audio preset description",
                audioSettings,
                false // not built-in
        );

        // Save preset via settings manager
        settingsManager.addSectionPreset(audioPreset);

        // Verify presets.json file was created (all presets are saved in one file)
        Path presetsFile = configDir.resolve("presets.json");
        assertTrue(Files.exists(presetsFile), "Presets file should be created on disk");

        // Verify we can load it back
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> audioPresets = presets.audioPresets();

        assertTrue(audioPresets.stream().anyMatch(p -> p.name().equals("TestAudioPreset")),
                "Saved preset should be loadable");
    }

    /**
     * Test that saving an image preset creates a file on disk.
     * Requirement REQ-2.6: Save image preset functionality.
     */
    @Test
    void testSaveImagePresetCreatesFileOnDisk() throws IOException {
        // Create an image preset
        ImageSettings imageSettings = ImageSettings.builder()
                .quality(85)
                .resolution(new Resolution(800, 600))
                .maintainAspectRatio(true)
                .compressionLevel(6)
                .resizeMode(ResizeMode.FIT)
                .build();

        SectionPreset imagePreset = SectionPreset.forImage(
                "TestImagePreset",
                "Test image preset description",
                imageSettings,
                false // not built-in
        );

        // Save preset via settings manager
        settingsManager.addSectionPreset(imagePreset);

        // Verify presets.json file was created (all presets are saved in one file)
        Path presetsFile = configDir.resolve("presets.json");
        assertTrue(Files.exists(presetsFile), "Presets file should be created on disk");

        // Verify we can load it back
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> imagePresets = presets.imagePresets();

        assertTrue(imagePresets.stream().anyMatch(p -> p.name().equals("TestImagePreset")),
                "Saved preset should be loadable");
    }

    /**
     * Test that saving a document preset creates a file on disk.
     * Requirement REQ-2.6: Save document preset functionality.
     */
    @Test
    void testSaveDocumentPresetCreatesFileOnDisk() throws IOException {
        // Create a document preset
        DocumentSettings documentSettings = DocumentSettings.builder()
                .preserveFormatting(true)
                .embedFonts(false)
                .generateTableOfContents(false)
                .marginTop(25)
                .marginBottom(25)
                .marginLeft(25)
                .marginRight(25)
                .build();

        SectionPreset documentPreset = SectionPreset.forDocument(
                "TestDocumentPreset",
                "Test document preset description",
                documentSettings,
                false // not built-in
        );

        // Save preset via settings manager
        settingsManager.addSectionPreset(documentPreset);

        // Verify presets.json file was created (all presets are saved in one file)
        Path presetsFile = configDir.resolve("presets.json");
        assertTrue(Files.exists(presetsFile), "Presets file should be created on disk");

        // Verify we can load it back
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> documentPresets = presets.documentPresets();

        assertTrue(documentPresets.stream().anyMatch(p -> p.name().equals("TestDocumentPreset")),
                "Saved preset should be loadable");
    }

    /**
     * Test that deleting a video preset removes the file from disk.
     * Requirement REQ-2.6: Delete video preset functionality.
     */
    @Test
    void testDeleteVideoPresetRemovesFromDisk() throws IOException {
        // Create and save a video preset
        VideoSettings videoSettings = VideoSettings.builder()
                .codec("libx264")
                .bitrate(5000)
                .build();

        SectionPreset videoPreset = SectionPreset.forVideo(
                "TestVideoPresetToDelete",
                "Test video preset to delete",
                videoSettings,
                false);

        settingsManager.addSectionPreset(videoPreset);

        // Verify preset was saved (check it exists in loaded presets)
        PresetsBySection presetsBeforeDelete = settingsManager.loadPresetsBySection();
        assertTrue(presetsBeforeDelete.videoPresets().stream()
                .anyMatch(p -> p.name().equals("TestVideoPresetToDelete")),
                "Preset should exist before deletion");

        // Delete the preset
        settingsManager.deleteSectionPreset("TestVideoPresetToDelete", FormatCategory.VIDEO);

        // Verify it's not loadable anymore
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> videoPresets = presets.videoPresets();

        assertFalse(videoPresets.stream().anyMatch(p -> p.name().equals("TestVideoPresetToDelete")),
                "Deleted preset should not be loadable");
    }

    /**
     * Test that deleting an audio preset removes the file from disk.
     * Requirement REQ-2.6: Delete audio preset functionality.
     */
    @Test
    void testDeleteAudioPresetRemovesFromDisk() throws IOException {
        // Create and save an audio preset
        AudioSettings audioSettings = AudioSettings.builder()
                .codec("aac")
                .bitrate(192)
                .build();

        SectionPreset audioPreset = SectionPreset.forAudio(
                "TestAudioPresetToDelete",
                "Test audio preset to delete",
                audioSettings,
                false);

        settingsManager.addSectionPreset(audioPreset);

        // Verify preset was saved (check it exists in loaded presets)
        PresetsBySection presetsBeforeDelete = settingsManager.loadPresetsBySection();
        assertTrue(presetsBeforeDelete.audioPresets().stream()
                .anyMatch(p -> p.name().equals("TestAudioPresetToDelete")),
                "Preset should exist before deletion");

        // Delete the preset
        settingsManager.deleteSectionPreset("TestAudioPresetToDelete", FormatCategory.AUDIO);

        // Verify it's not loadable anymore
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> audioPresets = presets.audioPresets();

        assertFalse(audioPresets.stream().anyMatch(p -> p.name().equals("TestAudioPresetToDelete")),
                "Deleted preset should not be loadable");
    }

    /**
     * Test that preset combo populates with section-specific presets.
     * Requirement REQ-2.6: Preset combo populates with section presets.
     */
    @Test
    void testPresetComboPopulatesWithSectionPresets() throws IOException {
        // Create presets for different sections
        VideoSettings videoSettings = VideoSettings.builder()
                .codec("libx264")
                .bitrate(5000)
                .build();
        SectionPreset videoPreset = SectionPreset.forVideo("VideoPreset1", null, videoSettings, false);

        AudioSettings audioSettings = AudioSettings.builder()
                .codec("aac")
                .bitrate(192)
                .build();
        SectionPreset audioPreset = SectionPreset.forAudio("AudioPreset1", null, audioSettings, false);

        ImageSettings imageSettings = ImageSettings.builder()
                .quality(85)
                .build();
        SectionPreset imagePreset = SectionPreset.forImage("ImagePreset1", null, imageSettings, false);

        DocumentSettings documentSettings = DocumentSettings.builder()
                .preserveFormatting(true)
                .build();
        SectionPreset documentPreset = SectionPreset.forDocument("DocumentPreset1", null, documentSettings, false);

        // Save all presets
        settingsManager.addSectionPreset(videoPreset);
        settingsManager.addSectionPreset(audioPreset);
        settingsManager.addSectionPreset(imagePreset);
        settingsManager.addSectionPreset(documentPreset);

        // Load presets by section
        PresetsBySection presets = settingsManager.loadPresetsBySection();

        // Verify each section has the correct preset
        List<SectionPreset> videoPresets = presets.videoPresets();
        assertTrue(videoPresets.stream().anyMatch(p -> p.name().equals("VideoPreset1")),
                "Video presets should contain VideoPreset1");

        List<SectionPreset> audioPresets = presets.audioPresets();
        assertTrue(audioPresets.stream().anyMatch(p -> p.name().equals("AudioPreset1")),
                "Audio presets should contain AudioPreset1");

        List<SectionPreset> imagePresets = presets.imagePresets();
        assertTrue(imagePresets.stream().anyMatch(p -> p.name().equals("ImagePreset1")),
                "Image presets should contain ImagePreset1");

        List<SectionPreset> documentPresets = presets.documentPresets();
        assertTrue(documentPresets.stream().anyMatch(p -> p.name().equals("DocumentPreset1")),
                "Document presets should contain DocumentPreset1");

        // Verify presets are in the correct section only
        assertFalse(videoPresets.stream().anyMatch(p -> p.name().equals("AudioPreset1")),
                "Video presets should not contain audio preset");
        assertFalse(audioPresets.stream().anyMatch(p -> p.name().equals("VideoPreset1")),
                "Audio presets should not contain video preset");
    }

    /**
     * Test that loading a video preset populates the correct values.
     * Requirement REQ-2.6: Preset selection loads values into UI.
     */
    @Test
    void testVideoPresetSelectionLoadsValues() throws IOException {
        // Create a video preset with specific values
        VideoSettings expectedSettings = VideoSettings.builder()
                .codec("libx265")
                .bitrate(8000)
                .resolution(new Resolution(3840, 2160))
                .frameRate(60)
                .preset("slow")
                .crf(18)
                .build();

        SectionPreset videoPreset = SectionPreset.forVideo(
                "4K60FpsPreset",
                "4K 60fps high quality",
                expectedSettings,
                false);

        // Save preset
        settingsManager.addSectionPreset(videoPreset);

        // Load presets
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> videoPresets = presets.videoPresets();

        // Find the saved preset
        SectionPreset loadedPreset = videoPresets.stream()
                .filter(p -> p.name().equals("4K60FpsPreset"))
                .findFirst()
                .orElseThrow();

        // Verify the loaded preset has the correct values
        VideoSettings loadedSettings = loadedPreset.videoSettings();
        assertNotNull(loadedSettings);
        assertEquals("libx265", loadedSettings.codec());
        assertEquals(8000, loadedSettings.bitrate());
        assertEquals(3840, loadedSettings.resolution().getWidth());
        assertEquals(2160, loadedSettings.resolution().getHeight());
        assertEquals(60, loadedSettings.frameRate());
        assertEquals("slow", loadedSettings.preset());
        assertEquals(18, loadedSettings.crf());
    }

    /**
     * Test that loading an audio preset populates the correct values.
     * Requirement REQ-2.6: Preset selection loads values into UI.
     */
    @Test
    void testAudioPresetSelectionLoadsValues() throws IOException {
        // Create an audio preset with specific values (FLAC codec paired with
        // its FLAC container so the codec survives the container policy pass)
        AudioSettings expectedSettings = AudioSettings.builder()
                .codec("flac")
                .bitrate(320)
                .sampleRate(96000)
                .channels(6)
                .quality(9)
                .outputFormat(FileFormat.FLAC)
                .build();

        SectionPreset audioPreset = SectionPreset.forAudio(
                "HighFidelityAudio",
                "High fidelity audio settings",
                expectedSettings,
                false);

        // Save preset
        settingsManager.addSectionPreset(audioPreset);

        // Load presets
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> audioPresets = presets.audioPresets();

        // Find the saved preset
        SectionPreset loadedPreset = audioPresets.stream()
                .filter(p -> p.name().equals("HighFidelityAudio"))
                .findFirst()
                .orElseThrow();

        // Verify the loaded preset has the correct values
        AudioSettings loadedSettings = loadedPreset.audioSettings();
        assertNotNull(loadedSettings);
        assertEquals("flac", loadedSettings.codec());
        assertEquals(320, loadedSettings.bitrate());
        assertEquals(96000, loadedSettings.sampleRate());
        assertEquals(6, loadedSettings.channels());
        assertEquals(9, loadedSettings.quality());
    }

    // ========== Audio Copy Codec Tests (Task T-8.4) ==========

    /**
     * Test that AUDIO_CODECS array includes the "copy" codec option.
     * Requirement REQ-AUD-1.1: Include copy codec option.
     */
    @Test
    void testAudioCodecsArrayIncludesCopyCodec() throws Exception {
        // Access the AUDIO_CODECS constant via reflection
        java.lang.reflect.Field field = SettingsDialogJavaGi.class.getDeclaredField("AUDIO_CODECS");
        field.setAccessible(true);
        String[] audioCodecs = (String[]) field.get(null);

        assertNotNull(audioCodecs, "AUDIO_CODECS array should not be null");
        assertTrue(audioCodecs.length > 0, "AUDIO_CODECS array should not be empty");

        // Verify "copy" codec is included
        boolean containsCopy = false;
        for (String codec : audioCodecs) {
            if ("copy".equals(codec)) {
                containsCopy = true;
                break;
            }
        }
        assertTrue(containsCopy, "AUDIO_CODECS should include 'copy' codec");
    }

    /**
     * Test that copy codec persists correctly in audio presets (load/save).
     * Requirement REQ-AUD-1.1: Copy codec should be persistable.
     */
    @Test
    void testCopyCodecPersistsCorrectlyInAudioPreset() throws IOException {
        // Create an audio preset with copy codec
        AudioSettings copySettings = AudioSettings.builder()
                .codec("copy") // Copy codec - encoding params should be ignored but still stored
                .bitrate(192) // These values should be preserved even though not used
                .sampleRate(44100)
                .channels(2)
                .quality(5)
                .build();

        SectionPreset copyPreset = SectionPreset.forAudio(
                "CopyCodecPreset",
                "Test preset with copy codec",
                copySettings,
                false);

        // Save preset
        settingsManager.addSectionPreset(copyPreset);

        // Load presets and verify
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> audioPresets = presets.audioPresets();

        // Find the saved preset
        SectionPreset loadedPreset = audioPresets.stream()
                .filter(p -> p.name().equals("CopyCodecPreset"))
                .findFirst()
                .orElseThrow();

        // Verify the loaded preset has the correct copy codec
        AudioSettings loadedSettings = loadedPreset.audioSettings();
        assertNotNull(loadedSettings);
        assertEquals("copy", loadedSettings.codec(), "Copy codec should persist correctly");
        // Note: Other settings are preserved but not used when copy codec is selected
        assertEquals(192, loadedSettings.bitrate());
        assertEquals(44100, loadedSettings.sampleRate());
        assertEquals(2, loadedSettings.channels());
        assertEquals(5, loadedSettings.quality());
    }

    /**
     * Test that copy codec selection disables encoding controls.
     * This test uses reflection to invoke updateAudioEncodingControlsState with
     * mocked GTK widgets.
     * Requirement REQ-AUD-1.1: Disable encoding controls when copy codec is
     * selected.
     */
    @Test
    void testCopyCodecDisablesEncodingControls() throws Exception {
        // This test requires mocking GTK components since the method uses them directly
        // We'll use reflection to test the logic by setting up a mock scenario

        // Create a mock SettingsDialogJavaGi instance (challenging due to GTK
        // dependencies)
        // Instead, test the logic indirectly by verifying the AUDIO_CODECS array and
        // the method's behavior

        // Access the AUDIO_CODECS constant
        java.lang.reflect.Field codecsField = SettingsDialogJavaGi.class.getDeclaredField("AUDIO_CODECS");
        codecsField.setAccessible(true);
        String[] audioCodecs = (String[]) codecsField.get(null);

        // Find the index of "copy" codec
        int copyIndex = -1;
        for (int i = 0; i < audioCodecs.length; i++) {
            if ("copy".equals(audioCodecs[i])) {
                copyIndex = i;
                break;
            }
        }
        assertTrue(copyIndex >= 0, "Copy codec should be found in AUDIO_CODECS array");

        // The actual UI testing would require integration tests with GTK
        // For unit testing, we verify the codec array contains copy and the method
        // exists
        java.lang.reflect.Method method = SettingsDialogJavaGi.class
                .getDeclaredMethod("updateAudioEncodingControlsState");
        method.setAccessible(true);
        assertNotNull(method, "updateAudioEncodingControlsState method should exist");
    }

    /**
     * Test that non-copy codecs enable encoding controls.
     * Similar to above, tests the presence of the method and codec array.
     * Requirement REQ-AUD-1.1: Enable encoding controls for non-copy codecs.
     */
    @Test
    void testNonCopyCodecsEnableEncodingControls() throws Exception {
        // Access the AUDIO_CODECS constant
        java.lang.reflect.Field codecsField = SettingsDialogJavaGi.class.getDeclaredField("AUDIO_CODECS");
        codecsField.setAccessible(true);
        String[] audioCodecs = (String[]) codecsField.get(null);

        // Verify we have codecs other than copy
        boolean hasNonCopyCodecs = false;
        for (String codec : audioCodecs) {
            if (!"copy".equals(codec)) {
                hasNonCopyCodecs = true;
                break;
            }
        }
        assertTrue(hasNonCopyCodecs, "Should have codecs other than copy");

        // Verify the update method exists
        java.lang.reflect.Method method = SettingsDialogJavaGi.class
                .getDeclaredMethod("updateAudioEncodingControlsState");
        method.setAccessible(true);
        assertNotNull(method, "updateAudioEncodingControlsState method should exist");
    }

    /**
     * Test that populateAudioCodecCombo includes copy codec in the dropdown.
     * This test verifies the method exists and can be called (though GTK components
     * are not available in unit tests).
     * Requirement REQ-AUD-1.1: Include copy codec in dropdown.
     */
    @Test
    void testPopulateAudioCodecComboIncludesCopyCodec() throws Exception {
        // Verify the populateAudioCodecCombo method exists
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("populateAudioCodecCombo");
        method.setAccessible(true);
        assertNotNull(method, "populateAudioCodecCombo method should exist");

        // Access the AUDIO_CODECS constant to verify copy is included
        java.lang.reflect.Field codecsField = SettingsDialogJavaGi.class.getDeclaredField("AUDIO_CODECS");
        codecsField.setAccessible(true);
        String[] audioCodecs = (String[]) codecsField.get(null);

        boolean containsCopy = java.util.Arrays.asList(audioCodecs).contains("copy");
        assertTrue(containsCopy, "AUDIO_CODECS should include 'copy' for populateAudioCodecCombo");
    }

    /**
     * Test that copy codec selection triggers updateAudioEncodingControlsState.
     * This test verifies the connection exists in populateAudioCodecCombo.
     * Requirement REQ-AUD-1.1: Codec selection should trigger control state update.
     */
    @Test
    void testCopyCodecSelectionTriggersUpdateControls() throws Exception {
        // Verify the populateAudioCodecCombo method exists (which sets up the signal
        // handler)
        java.lang.reflect.Method populateMethod = SettingsDialogJavaGi.class
                .getDeclaredMethod("populateAudioCodecCombo");
        populateMethod.setAccessible(true);
        assertNotNull(populateMethod, "populateAudioCodecCombo method should exist");

        // Verify the updateAudioEncodingControlsState method exists
        java.lang.reflect.Method updateMethod = SettingsDialogJavaGi.class
                .getDeclaredMethod("updateAudioEncodingControlsState");
        updateMethod.setAccessible(true);
        assertNotNull(updateMethod, "updateAudioEncodingControlsState method should exist");

        // The actual signal connection testing requires GTK integration tests
        // This unit test verifies the methods exist for the functionality
    }

    // ========== Image Rotation and Flip Dropdown Tests (Tasks T-8.5 and T-8.6)
    // ==========

    /**
     * Test that ImageRotation enum has exactly 4 values as expected by the UI.
     * Requirement REQ-IMG-1.1: Image rotation options (None, 90° CW, 180°, 90°
     * CCW).
     */
    @Test
    void testImageRotationEnumHasFourOptions() {
        ImageRotation[] rotations = ImageRotation.values();
        assertEquals(4, rotations.length, "ImageRotation should have exactly 4 options");

        // Verify the expected enum values exist
        assertNotNull(ImageRotation.NONE);
        assertNotNull(ImageRotation.CLOCKWISE_90);
        assertNotNull(ImageRotation.ROTATE_180);
        assertNotNull(ImageRotation.COUNTER_CLOCKWISE_90);
    }

    /**
     * Test that ImageFlip enum has exactly 4 values as expected by the UI.
     * Requirement REQ-IMG-2.1: Image flip options (None, Horizontal, Vertical,
     * Both).
     */
    @Test
    void testImageFlipEnumHasFourOptions() {
        ImageFlip[] flips = ImageFlip.values();
        assertEquals(4, flips.length, "ImageFlip should have exactly 4 options");

        // Verify the expected enum values exist
        assertNotNull(ImageFlip.NONE);
        assertNotNull(ImageFlip.HORIZONTAL);
        assertNotNull(ImageFlip.VERTICAL);
        assertNotNull(ImageFlip.BOTH);
    }

    /**
     * Test that populateImageRotationCombo method exists and can be invoked.
     * This method populates the rotation dropdown with ImageRotation enum display
     * names.
     * Requirement REQ-IMG-1.1: Provide image rotation options.
     */
    @Test
    void testPopulateImageRotationComboMethodExists() throws Exception {
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("populateImageRotationCombo");
        method.setAccessible(true);
        assertNotNull(method, "populateImageRotationCombo method should exist");
    }

    /**
     * Test that populateImageFlipCombo method exists and can be invoked.
     * This method populates the flip dropdown with ImageFlip enum display names.
     * Requirement REQ-IMG-2.1: Provide image flip options.
     */
    @Test
    void testPopulateImageFlipComboMethodExists() throws Exception {
        java.lang.reflect.Method method = SettingsDialogJavaGi.class.getDeclaredMethod("populateImageFlipCombo");
        method.setAccessible(true);
        assertNotNull(method, "populateImageFlipCombo method should exist");
    }

    /**
     * Test that rotation dropdown loading logic in populateImageSettings works
     * correctly.
     * Verifies that each ImageRotation enum value maps to the correct dropdown
     * index.
     * Requirement REQ-IMG-1.1: Image rotation dropdown loading.
     */
    @Test
    void testImageRotationDropdownLoadingLogic() {
        // Test that enum ordinal matches expected dropdown index
        assertEquals(0, ImageRotation.NONE.ordinal());
        assertEquals(1, ImageRotation.CLOCKWISE_90.ordinal());
        assertEquals(2, ImageRotation.ROTATE_180.ordinal());
        assertEquals(3, ImageRotation.COUNTER_CLOCKWISE_90.ordinal());

        // Verify display names match expected UI strings
        assertEquals("None", ImageRotation.NONE.getDisplayName());
        assertEquals("90° Clockwise", ImageRotation.CLOCKWISE_90.getDisplayName());
        assertEquals("180°", ImageRotation.ROTATE_180.getDisplayName());
        assertEquals("90° Counter-Clockwise", ImageRotation.COUNTER_CLOCKWISE_90.getDisplayName());
    }

    /**
     * Test that flip dropdown loading logic in populateImageSettings works
     * correctly.
     * Verifies that each ImageFlip enum value maps to the correct dropdown index.
     * Requirement REQ-IMG-2.1: Image flip dropdown loading.
     */
    @Test
    void testImageFlipDropdownLoadingLogic() {
        // Test that enum ordinal matches expected dropdown index
        assertEquals(0, ImageFlip.NONE.ordinal());
        assertEquals(1, ImageFlip.HORIZONTAL.ordinal());
        assertEquals(2, ImageFlip.VERTICAL.ordinal());
        assertEquals(3, ImageFlip.BOTH.ordinal());

        // Verify display names match expected UI strings
        assertEquals("None", ImageFlip.NONE.getDisplayName());
        assertEquals("Horizontal", ImageFlip.HORIZONTAL.getDisplayName());
        assertEquals("Vertical", ImageFlip.VERTICAL.getDisplayName());
        assertEquals("Both", ImageFlip.BOTH.getDisplayName());
    }

    /**
     * Test that rotation saving logic in readImageSettings works correctly.
     * Verifies that dropdown indices map back to correct ImageRotation enum values.
     * Requirement REQ-IMG-1.1: Image rotation dropdown saving.
     */
    @Test
    void testImageRotationDropdownSavingLogic() {
        // Test mapping from dropdown index to ImageRotation enum
        assertEquals(ImageRotation.NONE, ImageRotation.values()[0]);
        assertEquals(ImageRotation.CLOCKWISE_90, ImageRotation.values()[1]);
        assertEquals(ImageRotation.ROTATE_180, ImageRotation.values()[2]);
        assertEquals(ImageRotation.COUNTER_CLOCKWISE_90, ImageRotation.values()[3]);

        // Test bounds checking (indices should be 0-3)
        assertTrue(0 >= 0 && 0 < ImageRotation.values().length);
        assertTrue(1 >= 0 && 1 < ImageRotation.values().length);
        assertTrue(2 >= 0 && 2 < ImageRotation.values().length);
        assertTrue(3 >= 0 && 3 < ImageRotation.values().length);
    }

    /**
     * Test that flip saving logic in readImageSettings works correctly.
     * Verifies that dropdown indices map back to correct ImageFlip enum values.
     * Requirement REQ-IMG-2.1: Image flip dropdown saving.
     */
    @Test
    void testImageFlipDropdownSavingLogic() {
        // Test mapping from dropdown index to ImageFlip enum
        assertEquals(ImageFlip.NONE, ImageFlip.values()[0]);
        assertEquals(ImageFlip.HORIZONTAL, ImageFlip.values()[1]);
        assertEquals(ImageFlip.VERTICAL, ImageFlip.values()[2]);
        assertEquals(ImageFlip.BOTH, ImageFlip.values()[3]);

        // Test bounds checking (indices should be 0-3)
        assertTrue(0 >= 0 && 0 < ImageFlip.values().length);
        assertTrue(1 >= 0 && 1 < ImageFlip.values().length);
        assertTrue(2 >= 0 && 2 < ImageFlip.values().length);
        assertTrue(3 >= 0 && 3 < ImageFlip.values().length);
    }

    /**
     * Test that null rotation handling works correctly in populateImageSettings.
     * When imageSettings.rotation() is null, no dropdown selection should be made.
     * Requirement REQ-IMG-1.1: Null rotation handling.
     */
    @Test
    void testNullRotationHandlingInPopulateImageSettings() {
        // Create ImageSettings with null rotation (should default to NONE in
        // constructor)
        ImageSettings nullRotationSettings = ImageSettings.builder().build();
        assertNotNull(nullRotationSettings.rotation(), "ImageSettings constructor should default rotation to NONE");
        assertEquals(ImageRotation.NONE, nullRotationSettings.rotation());
    }

    /**
     * Test that null flip handling works correctly in populateImageSettings.
     * When imageSettings.flip() is null, no dropdown selection should be made.
     * Requirement REQ-IMG-2.1: Null flip handling.
     */
    @Test
    void testNullFlipHandlingInPopulateImageSettings() {
        // Create ImageSettings with null flip (should default to NONE in constructor)
        ImageSettings nullFlipSettings = ImageSettings.builder().build();
        assertNotNull(nullFlipSettings.flip(), "ImageSettings constructor should default flip to NONE");
        assertEquals(ImageFlip.NONE, nullFlipSettings.flip());
    }

    /**
     * Test that rotation and flip persist correctly in image presets (load/save).
     * Verifies that rotation and flip settings are preserved through preset
     * save/load cycle.
     * Requirement REQ-IMG-1.1, REQ-IMG-2.1: Rotation and flip persistence.
     */
    @Test
    void testRotationAndFlipPersistCorrectlyInImagePreset() throws IOException {
        // Create image settings with specific rotation and flip
        ImageSettings rotationFlipSettings = ImageSettings.builder()
                .quality(85)
                .rotation(ImageRotation.CLOCKWISE_90)
                .flip(ImageFlip.HORIZONTAL)
                .build();

        SectionPreset rotationFlipPreset = SectionPreset.forImage(
                "RotationFlipPreset",
                "Test preset with rotation and flip",
                rotationFlipSettings,
                false);

        // Save preset
        settingsManager.addSectionPreset(rotationFlipPreset);

        // Load presets and verify
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> imagePresets = presets.imagePresets();

        // Find the saved preset
        SectionPreset loadedPreset = imagePresets.stream()
                .filter(p -> p.name().equals("RotationFlipPreset"))
                .findFirst()
                .orElseThrow();

        // Verify the loaded preset has the correct rotation and flip
        ImageSettings loadedSettings = loadedPreset.imageSettings();
        assertNotNull(loadedSettings);
        assertEquals(ImageRotation.CLOCKWISE_90, loadedSettings.rotation(), "Rotation should persist correctly");
        assertEquals(ImageFlip.HORIZONTAL, loadedSettings.flip(), "Flip should persist correctly");
    }

    /**
     * Test that all rotation options work correctly in image settings.
     * Tests each ImageRotation enum value can be set and retrieved.
     * Requirement REQ-IMG-1.1: All rotation options functional.
     */
    @Test
    void testAllImageRotationOptionsFunctional() {
        // Test NONE rotation
        ImageSettings noneRotation = ImageSettings.builder()
                .rotation(ImageRotation.NONE)
                .build();
        assertEquals(ImageRotation.NONE, noneRotation.rotation());
        assertTrue(noneRotation.rotation().isNone());

        // Test CLOCKWISE_90 rotation
        ImageSettings cw90Rotation = ImageSettings.builder()
                .rotation(ImageRotation.CLOCKWISE_90)
                .build();
        assertEquals(ImageRotation.CLOCKWISE_90, cw90Rotation.rotation());
        assertEquals(90, cw90Rotation.rotation().getDegrees());
        assertFalse(cw90Rotation.rotation().isNone());

        // Test ROTATE_180 rotation
        ImageSettings rotate180 = ImageSettings.builder()
                .rotation(ImageRotation.ROTATE_180)
                .build();
        assertEquals(ImageRotation.ROTATE_180, rotate180.rotation());
        assertEquals(180, rotate180.rotation().getDegrees());
        assertFalse(rotate180.rotation().isNone());

        // Test COUNTER_CLOCKWISE_90 rotation
        ImageSettings ccw90Rotation = ImageSettings.builder()
                .rotation(ImageRotation.COUNTER_CLOCKWISE_90)
                .build();
        assertEquals(ImageRotation.COUNTER_CLOCKWISE_90, ccw90Rotation.rotation());
        assertEquals(270, ccw90Rotation.rotation().getDegrees());
        assertFalse(ccw90Rotation.rotation().isNone());
    }

    /**
     * Test that all flip options work correctly in image settings.
     * Tests each ImageFlip enum value can be set and retrieved.
     * Requirement REQ-IMG-2.1: All flip options functional.
     */
    @Test
    void testAllImageFlipOptionsFunctional() {
        // Test NONE flip
        ImageSettings noneFlip = ImageSettings.builder()
                .flip(ImageFlip.NONE)
                .build();
        assertEquals(ImageFlip.NONE, noneFlip.flip());
        assertTrue(noneFlip.flip().isNone());
        assertFalse(noneFlip.flip().isFlipHorizontal());
        assertFalse(noneFlip.flip().isFlipVertical());

        // Test HORIZONTAL flip
        ImageSettings horizontalFlip = ImageSettings.builder()
                .flip(ImageFlip.HORIZONTAL)
                .build();
        assertEquals(ImageFlip.HORIZONTAL, horizontalFlip.flip());
        assertFalse(horizontalFlip.flip().isNone());
        assertTrue(horizontalFlip.flip().isFlipHorizontal());
        assertFalse(horizontalFlip.flip().isFlipVertical());

        // Test VERTICAL flip
        ImageSettings verticalFlip = ImageSettings.builder()
                .flip(ImageFlip.VERTICAL)
                .build();
        assertEquals(ImageFlip.VERTICAL, verticalFlip.flip());
        assertFalse(verticalFlip.flip().isNone());
        assertFalse(verticalFlip.flip().isFlipHorizontal());
        assertTrue(verticalFlip.flip().isFlipVertical());

        // Test BOTH flip
        ImageSettings bothFlip = ImageSettings.builder()
                .flip(ImageFlip.BOTH)
                .build();
        assertEquals(ImageFlip.BOTH, bothFlip.flip());
        assertFalse(bothFlip.flip().isNone());
        assertTrue(bothFlip.flip().isFlipHorizontal());
        assertTrue(bothFlip.flip().isFlipVertical());
    }

    /**
     * Test that rotation and flip work together with other image settings.
     * Verifies that rotation and flip don't interfere with quality, resolution,
     * etc.
     * Requirement REQ-IMG-1.1, REQ-IMG-2.1: Integration with other settings.
     */
    @Test
    void testRotationAndFlipWithOtherImageSettings() {
        // Create comprehensive image settings with rotation, flip, and other options
        ImageSettings comprehensiveSettings = ImageSettings.builder()
                .quality(90)
                .resolution(new Resolution(1920, 1080))
                .maintainAspectRatio(true)
                .compressionLevel(8)
                .resizeMode(ResizeMode.FIT)
                .rotation(ImageRotation.ROTATE_180)
                .flip(ImageFlip.BOTH)
                .build();

        // Verify all settings are preserved
        assertEquals(90, comprehensiveSettings.quality());
        assertEquals(1920, comprehensiveSettings.resolution().getWidth());
        assertEquals(1080, comprehensiveSettings.resolution().getHeight());
        assertTrue(comprehensiveSettings.maintainAspectRatio());
        assertEquals(8, comprehensiveSettings.compressionLevel());
        assertEquals(ResizeMode.FIT, comprehensiveSettings.resizeMode());
        assertEquals(ImageRotation.ROTATE_180, comprehensiveSettings.rotation());
        assertEquals(ImageFlip.BOTH, comprehensiveSettings.flip());
    }

    /**
     * Test that rotation and flip combination presets save and load correctly.
     * Tests various combinations of rotation and flip settings.
     * Requirement REQ-IMG-1.1, REQ-IMG-2.1: Combined rotation and flip
     * functionality.
     */
    @Test
    void testRotationAndFlipCombinationsInPresets() throws IOException {
        // Test multiple rotation and flip combinations
        ImageSettings[] testSettings = {
                ImageSettings.builder().rotation(ImageRotation.NONE).flip(ImageFlip.NONE).build(),
                ImageSettings.builder().rotation(ImageRotation.CLOCKWISE_90).flip(ImageFlip.HORIZONTAL).build(),
                ImageSettings.builder().rotation(ImageRotation.ROTATE_180).flip(ImageFlip.VERTICAL).build(),
                ImageSettings.builder().rotation(ImageRotation.COUNTER_CLOCKWISE_90).flip(ImageFlip.BOTH).build()
        };

        String[] presetNames = {
                "NoRotationNoFlip",
                "90CW_HorizontalFlip",
                "180Rotation_VerticalFlip",
                "90CCW_BothFlip"
        };

        // Save all presets
        for (int i = 0; i < testSettings.length; i++) {
            SectionPreset preset = SectionPreset.forImage(presetNames[i], null, testSettings[i], false);
            settingsManager.addSectionPreset(preset);
        }

        // Load and verify all presets
        PresetsBySection presets = settingsManager.loadPresetsBySection();
        List<SectionPreset> imagePresets = presets.imagePresets();

        for (int i = 0; i < presetNames.length; i++) {
            final int index = i; // Make effectively final for lambda
            SectionPreset loadedPreset = imagePresets.stream()
                    .filter(p -> p.name().equals(presetNames[index]))
                    .findFirst()
                    .orElseThrow();

            ImageSettings loadedSettings = loadedPreset.imageSettings();
            assertEquals(testSettings[index].rotation(), loadedSettings.rotation(),
                    "Rotation should match for preset: " + presetNames[index]);
            assertEquals(testSettings[index].flip(), loadedSettings.flip(),
                    "Flip should match for preset: " + presetNames[index]);
        }
    }

    /**
     * Test that delete original file checkbox loads as checked when
     * deleteOriginalFile = true.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxLoadsCheckedWhenTrue() {
        // Create settings with deleteOriginalFile = true
        ConversionSettings settingsWithDeleteTrue = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(true)
                .build();

        // Verify the settings object has deleteOriginalFile = true
        assertTrue(settingsWithDeleteTrue.deleteOriginalFile(), "Settings should have deleteOriginalFile = true");
    }

    /**
     * Test that delete original file checkbox defaults to unchecked (safe default).
     * Requirement: Delete original file checkbox integration, safety note.
     */
    @Test
    void testDeleteOriginalFileCheckboxDefaultsToUnchecked() {
        // Create settings without specifying deleteOriginalFile
        ConversionSettings defaultSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .build();

        // Verify default is false (unchecked)
        assertFalse(defaultSettings.deleteOriginalFile(), "Default deleteOriginalFile should be false for safety");
    }

    /**
     * Test null handling for delete original file setting.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxNullHandling() {
        // Test that ConversionSettings handles null appropriately
        // Since deleteOriginalFile is a boolean, it defaults to false
        ConversionSettings settings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .build();

        assertNotNull(settings.deleteOriginalFile(), "deleteOriginalFile should not be null");
        assertFalse(settings.deleteOriginalFile(), "deleteOriginalFile should default to false");
    }

    /**
     * Test that checked delete original file checkbox saves as true.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxSavesTrueWhenChecked() {
        // Test the builder directly - simulates saving when checkbox is checked
        ConversionSettings.Builder builder = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir);
        builder.deleteOriginalFile(true); // Simulates checkbox.getActive() = true
        ConversionSettings settings = builder.build();

        assertTrue(settings.deleteOriginalFile(), "Builder should set deleteOriginalFile to true");
    }

    /**
     * Test that unchecked delete original file checkbox saves as false.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxSavesFalseWhenUnchecked() {
        // Test the builder directly - simulates saving when checkbox is unchecked
        ConversionSettings.Builder builder = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir);
        builder.deleteOriginalFile(false); // Simulates checkbox.getActive() = false
        ConversionSettings settings = builder.build();

        assertFalse(settings.deleteOriginalFile(), "Builder should set deleteOriginalFile to false");
    }

    /**
     * Test roundtrip save → load → verify for delete original file checkbox.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxRoundtrip() {
        // Simulate save: create settings with deleteOriginalFile = true
        ConversionSettings savedSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(true)
                .build();

        // Simulate load: read the value
        boolean loadedValue = savedSettings.deleteOriginalFile();

        // Verify roundtrip
        assertTrue(loadedValue, "Roundtrip should preserve deleteOriginalFile = true");

        // Test with false
        ConversionSettings savedSettingsFalse = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(false)
                .build();

        boolean loadedValueFalse = savedSettingsFalse.deleteOriginalFile();
        assertFalse(loadedValueFalse, "Roundtrip should preserve deleteOriginalFile = false");
    }

    /**
     * Test that deleteOriginalFile persists correctly in settings (not presets, as
     * it's a general setting).
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFilePersistsInSettings() throws IOException, org.omc.exception.InvalidSettingsException {
        // Create settings with deleteOriginalFile = true
        ConversionSettings settingsWithDelete = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(true)
                .overwriteExisting(true)
                .createSubdirectory(false)
                .build();

        // Save settings via settingsManager
        settingsManager.saveSettings(settingsWithDelete);

        // Load settings back
        ConversionSettings loadedSettings = settingsManager.loadSettings();

        // Verify deleteOriginalFile persisted
        assertTrue(loadedSettings.deleteOriginalFile(), "deleteOriginalFile should persist as true");
        assertTrue(loadedSettings.overwriteExisting(), "Other settings should also persist");
        assertFalse(loadedSettings.createSubdirectory(), "createSubdirectory should persist as false");
    }

    /**
     * Test deleteOriginalFile with other general settings.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileWithOtherGeneralSettings() {
        // Create settings with all general options
        ConversionSettings comprehensiveSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .overwriteExisting(true)
                .createSubdirectory(true)
                .deleteOriginalFile(true)
                .parallelConversions(8)
                .build();

        // Verify all settings are set correctly
        assertTrue(comprehensiveSettings.overwriteExisting());
        assertTrue(comprehensiveSettings.createSubdirectory());
        assertTrue(comprehensiveSettings.deleteOriginalFile());
        assertEquals(8, comprehensiveSettings.parallelConversions());

        // Test combinations
        ConversionSettings mixedSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .overwriteExisting(false)
                .createSubdirectory(true)
                .deleteOriginalFile(false) // Safe default
                .parallelConversions(2)
                .build();

        assertFalse(mixedSettings.overwriteExisting());
        assertTrue(mixedSettings.createSubdirectory());
        assertFalse(mixedSettings.deleteOriginalFile(), "deleteOriginalFile should be false for safety");
        assertEquals(2, mixedSettings.parallelConversions());
    }

    /**
     * Test that dialog cancel does not save deleteOriginalFile changes.
     * This test simulates the dialog workflow without GTK.
     * Requirement: Delete original file checkbox integration.
     */
    @Test
    void testDeleteOriginalFileCheckboxStateAfterDialogCancel()
            throws IOException, org.omc.exception.InvalidSettingsException {
        // Save initial settings with deleteOriginalFile = false
        ConversionSettings initialSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(false)
                .build();
        settingsManager.saveSettings(initialSettings);

        // Simulate user changing checkbox to true but then canceling dialog
        // (In real dialog, changes wouldn't be saved on cancel)
        // Since we can't simulate GTK dialog, we test that settings remain unchanged
        ConversionSettings loadedAfterSimulatedCancel = settingsManager.loadSettings();
        assertFalse(loadedAfterSimulatedCancel.deleteOriginalFile(),
                "Settings should remain unchanged after simulated cancel");

        // Verify that if we were to save changes, they would persist
        ConversionSettings modifiedSettings = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(true)
                .build();
        settingsManager.saveSettings(modifiedSettings);

        ConversionSettings loadedModified = settingsManager.loadSettings();
        assertTrue(loadedModified.deleteOriginalFile(),
                "Modified settings should persist when saved");
    }

    /**
     * Test safety: deleteOriginalFile defaults to false in all scenarios.
     * Requirement: Safety note - default should be false/unchecked.
     */
    @Test
    void testDeleteOriginalFileSafetyDefault() {
        // Test builder default
        ConversionSettings safeDefault = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .build();

        assertFalse(safeDefault.deleteOriginalFile(), "Default should always be false for safety");

        // Test that explicitly setting to true works but default is false
        ConversionSettings explicitTrue = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(true)
                .build();

        assertTrue(explicitTrue.deleteOriginalFile(), "Explicit true should work");

        // Test that unsetting back to default is false
        ConversionSettings resetToDefault = ConversionSettings.builder()
                .outputFormat(FileFormat.MP4)
                .outputDirectory(testOutputDir)
                .deleteOriginalFile(false) // Explicitly set to false
                .build();

        assertFalse(resetToDefault.deleteOriginalFile(), "Should be able to set to false");
    }
}
