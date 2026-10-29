package org.omc.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.omc.core.ConfigurationManager;
import org.omc.core.ValidationEngine;
import org.omc.exception.InvalidSettingsException;
import org.omc.exception.StateIOException;
import org.omc.model.ConversionFile;
import org.omc.model.ConversionSettings;
import org.omc.model.DocumentSettings;
import org.omc.model.FileFormat;
import org.omc.model.FileSettingsOverride;
import org.omc.model.VideoSettings;
import org.omc.service.FileHandler;
import org.omc.util.JsonUtils;

/** Verifies language preferences survive restart, legacy settings and section recovery. */
class LanguagePreferenceTest {
    @TempDir
    Path directory;
    private SettingsManager manager;

    @BeforeEach
    void initialize() throws IOException, StateIOException {
        var config = new ConfigurationManager(
                Files.createDirectories(directory.resolve("config")),
                Files.createDirectories(directory.resolve("data")),
                Files.createDirectories(directory.resolve("cache")));
        manager = new SettingsManager(config, new ValidationEngine(new FileHandler(config)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "system", "en", "fr", "es", "de", "pt", "zh", "ja" })
    void savesAndReloadsLanguageWithoutChangingConversionSettings(String language)
            throws IOException, InvalidSettingsException {
        ConversionSettings settings = ConversionSettings.builder()
                .outputDirectory(directory).language(language).parallelConversions(3)
                .videoSettings(VideoSettings.builder().codec("libx264").preset("slow").crf(19).build())
                .build().withDefaults();
        manager.saveSettings(settings);
        assertEquals(settings, manager.loadSettings());
        assertEquals(language, manager.getCurrentSettings().language());
    }

    @Test
    void oldSettingsWithoutLanguageFollowTheSystem() throws IOException {
        ObjectNode json = JsonUtils.getObjectMapper().valueToTree(
                ConversionSettings.builder().outputDirectory(directory).language("ja").build().withDefaults());
        json.remove("language");
        JsonUtils.writeJsonFile(json, manager.getSettingsFilePath().toFile());
        assertEquals("system", manager.loadSettings().language());
        assertEquals("system", ConversionSettings.builder().language(" ").build().language());
        assertEquals("system", ConversionSettings.builder().language(null).build().language());
    }

    @Test
    void recoversLanguageWhenAnotherSectionIsCorrupted() throws IOException {
        ObjectNode json = JsonUtils.getObjectMapper().valueToTree(
                ConversionSettings.builder().outputDirectory(directory).language("fr").build().withDefaults());
        json.put("videoSettings", "corrupted section");
        JsonUtils.writeJsonFile(json, manager.getSettingsFilePath().toFile());
        ConversionSettings recovered = manager.loadSettings();
        assertEquals("fr", recovered.language());
        assertTrue(recovered.videoSettings().isValid());
        assertEquals(directory, recovered.outputDirectory());
    }

    @Test
    void defaultsAndFilePresetsRetainLanguage() {
        ConversionSettings partial = ConversionSettings.builder().language("pt").build();
        assertEquals("pt", partial.withDefaults().language());
        assertEquals("pt", partial.withDocumentSettings(DocumentSettings.builder().build()).language());
        var video = VideoSettings.builder().preset("slow").build();
        var file = ConversionFile.create(directory.resolve("film.mp4"), FileFormat.MP4, 42)
                .withSettingsOverride(FileSettingsOverride.forVideo("Qualidade", video));
        ConversionSettings effective = partial.forFile(file);
        assertEquals("pt", effective.language());
        assertEquals(video, effective.videoSettings());
    }
}
