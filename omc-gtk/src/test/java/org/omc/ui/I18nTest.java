package org.omc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.omc.model.AspectRatio;
import org.omc.model.ImageFlip;
import org.omc.model.ImageRotation;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Catalog, locale-selection and XML regression checks without a display server. */
class I18nTest {
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([a-zA-Z%])");
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
    void resolvesDesktopLocaleAndExplicitPreferenceInPriorityOrder() {
        assertEquals("fr-CA", I18n.selectLocale("system",
                Map.of("LANG", "fr_CA.UTF-8"), Locale.ENGLISH).toLanguageTag());
        assertEquals("ja", I18n.selectLocale("system",
                Map.of("LANGUAGE", "it:ja:de", "LANG", "en_US.UTF-8"), Locale.ENGLISH).getLanguage());
        assertEquals("de", I18n.selectLocale("system",
                Map.of("LC_MESSAGES", "de_DE.UTF-8", "LANG", "es_ES.UTF-8"), Locale.ENGLISH).getLanguage());
        assertEquals("es", I18n.selectLocale("system",
                Map.of("LC_ALL", "es_ES.UTF-8", "LC_MESSAGES", "de_DE.UTF-8"), Locale.ENGLISH).getLanguage());
        assertEquals("pt", I18n.selectLocale("pt", Map.of("LANG", "fr_FR.UTF-8"), Locale.JAPANESE).getLanguage());
        assertEquals("zh", I18n.selectLocale("fr", Map.of("OMC_LANGUAGE", "zh_CN"), Locale.ENGLISH).getLanguage());
        assertEquals(Locale.ENGLISH, I18n.selectLocale("system",
                Map.of("LC_ALL", "C.UTF-8", "LANGUAGE", "fr"), Locale.FRENCH));
        assertEquals(Locale.ENGLISH, I18n.selectLocale("it", Map.of(), Locale.FRENCH));
        assertEquals(Locale.ENGLISH, I18n.selectLocale("system", Map.of("LANG", "ko_KR"), Locale.FRENCH));
        assertEquals(Locale.JAPANESE, I18n.selectLocale(null, Map.of(), Locale.JAPANESE));
    }

    @Test
    void propertyOverrideAndDisplayFormattingLeaveToolLocaleUntouched() {
        Locale defaultLocale = Locale.getDefault();
        Locale numericLocale = Locale.getDefault(Locale.Category.FORMAT);
        select("fr");
        I18n.setLanguage("ja");
        assertEquals("fr", I18n.locale().getLanguage());
        assertEquals("1,5 Ko", I18n.bytes(1536));
        assertEquals("1,5 Ko/s", I18n.speed(1536));
        assertEquals("1 h 2 min 3 s", I18n.duration(Duration.ofSeconds(3723)));
        assertEquals(defaultLocale, Locale.getDefault());
        assertEquals(numericLocale, Locale.getDefault(Locale.Category.FORMAT));
    }

    @Test
    void readsUtf8CatalogsAndFallsBackWithoutTranslatingUserContent() {
        select("fr-CA");
        assertEquals("Paramètres", I18n.tr("Settings"));
        select("zh-CN");
        assertEquals("设置", I18n.tr("Settings"));
        select("ja");
        assertEquals("設定", I18n.tr("Settings"));
        String filename = "Paramètres 100% & <日本語>.png";
        assertTrue(I18n.tr("%s - Conversion Details", filename).contains(filename));
        assertEquals("Missing future message", I18n.tr("Missing future message"));
        select("unsupported");
        assertEquals("Settings", I18n.tr("Settings"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "fr", "es", "de", "pt", "zh", "ja" })
    void everyCatalogIsCompleteAndFormatsAllMessages(String language) throws IOException {
        Properties english = catalog("");
        Properties translated = catalog("_" + language);
        assertEquals(english.stringPropertyNames(), translated.stringPropertyNames());
        select(language);
        assertNotEquals("Settings", I18n.tr("Settings"));
        for (String key : english.stringPropertyNames()) {
            String value = translated.getProperty(key);
            assertFalse(value.isBlank(), language + ": " + key);
            Map<Integer, String> arguments = argumentTypes(key);
            assertEquals(arguments, argumentTypes(value), language + ": " + key);
            int count = arguments.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            Object[] sample = new Object[count];
            arguments.forEach((index, type) -> sample[index - 1] = switch (type) {
                case "d" -> 42;
                case "f" -> 1.5;
                default -> "é漢字 100%";
            });
            assertNotNull(I18n.tr(key, sample), language + ": " + key);
        }
    }

    @Test
    void catalogCoversUiAndDynamicEnumLabels() throws Exception {
        Properties english = catalog("");
        Set<String> invariant = Set.of("Open Media Converter", "  (1-16)", "×", "kbps", "CRF:", "px", "mm", "0 B", "180°");
        for (String path : List.of("/ui/main_window.ui", "/ui/settings_dialog.ui")) {
            try (var stream = I18n.class.getResourceAsStream(path)) {
                assertNotNull(stream);
                var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
                var elements = document.getElementsByTagName("*");
                for (int i = 0; i < elements.getLength(); i++) {
                    Element element = (Element) elements.item(i);
                    if ("yes".equals(element.getAttribute("translatable"))) {
                        String message = element.getTextContent();
                        assertTrue(english.containsKey(message) || invariant.contains(message), message);
                    }
                }
            }
        }
        for (ImageFlip flip : ImageFlip.values()) assertTrue(english.containsKey(flip.getDisplayName()));
        for (ImageRotation rotation : ImageRotation.values()) {
            assertTrue(english.containsKey(rotation.getDisplayName()) || invariant.contains(rotation.getDisplayName()));
        }
        for (AspectRatio ratio : AspectRatio.values()) assertTrue(english.containsKey(ratio.getDisplayName()));
    }

    @Test
    void translatesXmlTextWithoutChangingActionsIdsOrMarkup() throws Exception {
        select("fr");
        String ui = """
                <interface><object class="GtkButton" id="addFilesButton">
                <property name="label" translatable="yes">Add Files</property>
                <property name="tooltip-text" translatable="yes">A &amp; B &lt;test&gt;</property>
                <property name="action-name">win.add-files</property>
                </object></interface>
                """;
        String localized = I18n.translateUi(ui);
        assertFalse(localized.contains("translatable="));
        try (var reader = new StringReader(localized)) {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(reader));
            var object = (Element) document.getElementsByTagName("object").item(0);
            assertEquals("addFilesButton", object.getAttribute("id"));
            assertEquals("GtkButton", object.getAttribute("class"));
            var properties = document.getElementsByTagName("property");
            assertEquals(I18n.tr("Add Files"), properties.item(0).getTextContent());
            assertEquals("A & B <test>", properties.item(1).getTextContent());
            assertEquals("win.add-files", properties.item(2).getTextContent());
        }
    }

    private static void select(String language) {
        System.setProperty("omc.language", language);
        I18n.setLanguage("system");
    }

    private static Properties catalog(String suffix) throws IOException {
        Properties properties = new Properties();
        try (var stream = I18n.class.getResourceAsStream("/i18n/messages" + suffix + ".properties")) {
            assertNotNull(stream);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static Map<Integer, String> argumentTypes(String text) {
        Map<Integer, String> arguments = new HashMap<>();
        var matcher = PLACEHOLDER.matcher(text);
        int next = 0;
        while (matcher.find()) {
            String type = matcher.group(2);
            if (type.equals("%") || type.equals("n")) continue;
            int index = matcher.group(1) == null ? ++next : Integer.parseInt(matcher.group(1));
            arguments.put(index, type);
        }
        return arguments;
    }
}
