package org.omc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gnome.glib.GLib;
import org.gnome.gtk.Button;
import org.gnome.gtk.Dialog;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Scale;
import org.gnome.gtk.StringList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.omc.model.AspectRatio;
import org.omc.model.AudioSettings;
import org.omc.model.ConversionSettings;
import org.omc.model.ImageFlip;
import org.omc.model.ImageRotation;
import org.omc.model.ImageSettings;
import org.omc.model.VideoSettings;
import org.omc.util.JsonUtils;

/**
 * Exercises UTF-8 UI loading and real settings widgets on the GTK main loop.
 * Run separately with {@code -Domc.nativeGtk=true -Dtest=I18nGtkTest} so native
 * GTK object state is isolated from the suite's mocked native wrappers.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@EnabledIfSystemProperty(named = "omc.nativeGtk", matches = "true")
class I18nGtkTest {
    @TempDir
    Path directory;
    private String previousOverride;
    private Locale previousLocale;

    @BeforeEach
    void rememberLocale() {
        previousOverride = System.getProperty("omc.language");
        previousLocale = I18n.locale();
    }

    @AfterEach
    void restoreLocale() {
        System.setProperty("omc.language", previousLocale.toLanguageTag());
        I18n.setLanguage("system");
        if (previousOverride == null) System.clearProperty("omc.language");
        else System.setProperty("omc.language", previousOverride);
    }

    @Test
    void configuredLocaleBuildsRealWindowsAndKeepsCodecMappingsStable() throws IOException {
        String language = System.getProperty("omc.language", "en");
        // Exercise the saved preference rather than relying on the test-run override.
        // A damaged section also checks that startup does not reload the moved backup
        // and replace the recovered settings with defaults.
        ObjectNode saved = JsonUtils.getObjectMapper().valueToTree(
                ConversionSettings.builder().outputDirectory(directory).language(language).build().withDefaults());
        saved.put("videoSettings", "corrupted section");
        JsonUtils.writeJsonFile(saved, directory.resolve("settings.json").toFile());
        System.clearProperty("omc.language");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MainApplicationJavaGi application = new MainApplicationJavaGi();
        application.onActivate(() -> GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 200, () -> {
            try {
                verifyWidgets(application, language);
            } catch (AssertionError | RuntimeException | ReflectiveOperationException error) {
                failure.set(error);
            } finally {
                application.getMainWindow().close();
            }
            return false;
        }));
        assertEquals(0, application.run(new String[] { "omc-i18n-test", "--config-dir", directory.toString() }));
        if (failure.get() != null) throw new AssertionError("GTK localization failed", failure.get());
    }

    private void verifyWidgets(MainApplicationJavaGi application, String language) throws ReflectiveOperationException {
        var controller = application.getController();
        assertEquals(language, I18n.locale().getLanguage());
        assertEquals(language, controller.getCurrentSettings().language());
        assertEquals(directory, controller.getCurrentSettings().outputDirectory());
        var mainWindow = application.getMainWindow();
        // These buttons display icons; their translated text is the tooltip.
        assertEquals(I18n.tr("Add files to convert"), ((Button) field(mainWindow, "addFilesButton")).getTooltipText());
        assertEquals(I18n.tr("Start the conversion process"), ((Button) field(mainWindow, "convertButton")).getTooltipText());
        var video = VideoSettings.builder().codec("libx264").preset("slow")
                .aspectRatio(AspectRatio.RATIO_16_9).frameRate(30).build();
        var audio = AudioSettings.builder().codec("aac").sampleRate(44100).channels(2).build();
        var image = ImageSettings.builder().rotation(ImageRotation.CLOCKWISE_90)
                .flip(ImageFlip.HORIZONTAL).build();
        var settings = ConversionSettings.builder().outputDirectory(directory).language(language)
                .videoSettings(video).audioSettings(audio).imageSettings(image).build().withDefaults();
        var settingsDialog = new SettingsDialogJavaGi(mainWindow, settings, controller.getSettingsManager(), controller);
        settingsDialog.showDialog();
        try {
            assertEquals(I18n.tr("Settings"), ((Dialog) field(settingsDialog, "dialog")).getTitle());
            var languageDropdown = (DropDown) field(settingsDialog, "languageDropdown");
            assertEquals(I18n.LANGUAGE_TAGS.indexOf(language), languageDropdown.getSelected());
            var presetDropdown = (DropDown) field(settingsDialog, "videoPresetDropdown");
            assertEquals(I18n.tr("slow - Better compression"),
                    ((StringList) presetDropdown.getModel()).getString(presetDropdown.getSelected()));
            var rotationDropdown = (DropDown) field(settingsDialog, "imageRotationDropdown");
            assertEquals(I18n.tr("90° Clockwise"),
                    ((StringList) rotationDropdown.getModel()).getString(rotationDropdown.getSelected()));
            var actual = settingsDialog.getConversionSettings();
            assertEquals(language, actual.language());
            assertEquals(video, actual.videoSettings());
            assertEquals(audio, actual.audioSettings());
            assertEquals(image, actual.imageSettings());
            // GTK frees each formatted scale label. Exercise repeated callbacks and
            // collection to catch a Java arena also attempting to free those strings.
            var quality = (Scale) field(settingsDialog, "imageQualityScale");
            for (int i = 0; i < 100; i++) quality.setValue(i % 3 - 1);
            System.gc();
            for (int i = 0; i < 100; i++) quality.setValue(i % 3 - 1);
            assertEquals(-1, settingsDialog.getConversionSettings().imageSettings().quality());
            String nextLanguage = language.equals("fr") ? "ja" : "fr";
            languageDropdown.setSelected(I18n.LANGUAGE_TAGS.indexOf(nextLanguage));
            assertEquals(nextLanguage, settingsDialog.getConversionSettings().language());
            assertTrue((boolean) field(settingsDialog, "hasUnsavedChanges"));
            assertEquals(language, I18n.locale().getLanguage(), "Language changes apply after restart");
        } finally {
            settingsDialog.closeDialog();
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
