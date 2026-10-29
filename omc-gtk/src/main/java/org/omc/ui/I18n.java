package org.omc.ui;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * UTF-8 message catalogs for the desktop interface. English source messages are
 * catalog keys, so an unavailable translation safely falls back to English.
 * Display locale selection never changes the JVM locale used by conversion tools.
 */
public final class I18n {
    static final List<String> LANGUAGE_TAGS = List.of("system", "en", "fr", "es", "de", "pt", "zh", "ja");
    private static final List<String> LANGUAGE_NAMES = List.of(
            "English", "Français", "Español", "Deutsch", "Português", "简体中文", "日本語");
    private static final Map<Locale, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();
    private static volatile Locale locale = configuredLocale("system");

    private I18n() {
    }

    /**
     * Selects the interface language at startup. An explicit {@code omc.language}
     * JVM property or {@code OMC_LANGUAGE} environment variable overrides the saved
     * preference. A saved {@code system} preference follows the desktop locale.
     *
     * @param preference saved language tag, or {@code system} for automatic selection
     */
    public static void setLanguage(String preference) {
        locale = configuredLocale(preference);
    }

    private static Locale configuredLocale(String preference) {
        String override = System.getProperty("omc.language");
        return override == null || override.isBlank()
                ? selectLocale(preference, System.getenv(), Locale.getDefault(Locale.Category.DISPLAY))
                : supportedLocale(override);
    }

    /** @return the active display locale, independently of conversion-tool formatting */
    public static Locale locale() {
        return locale;
    }

    /**
     * Translates a complete English message and optionally formats its arguments.
     * Catalogs may use numbered printf placeholders to reorder translated arguments.
     *
     * @param english English source message
     * @param arguments optional printf arguments; user content is never a format string
     * @return translated and formatted text, with English fallback
     */
    public static String tr(String english, Object... arguments) {
        Objects.requireNonNull(english, "english");
        Locale selected = locale;
        ResourceBundle bundle = BUNDLES.computeIfAbsent(selected, language -> ResourceBundle.getBundle(
                "i18n.messages", language,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)));
        String pattern = bundle.containsKey(english) ? bundle.getString(english) : english;
        return arguments.length == 0 ? pattern : String.format(selected, pattern, arguments);
    }

    /** @return native language names, preceded by the translated automatic-selection label */
    public static String[] languageNames() {
        String[] names = new String[LANGUAGE_TAGS.size()];
        names[0] = tr("System default");
        for (int i = 0; i < LANGUAGE_NAMES.size(); i++) names[i + 1] = LANGUAGE_NAMES.get(i);
        return names;
    }

    /**
     * Localizes only explicitly translatable XML text before GTK builds widgets.
     * IDs, actions, model values, markup and signal wiring remain unchanged.
     *
     * @param xml trusted classpath GtkBuilder definition
     * @return localized UTF-8 XML
     * @throws IllegalArgumentException if the UI definition cannot be parsed
     */
    public static String translateUi(String xml) {
        try (StringReader reader = new StringReader(xml); StringWriter writer = new StringWriter()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(reader));
            var elements = document.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                if ("yes".equals(element.getAttribute("translatable"))) {
                    element.setTextContent(tr(element.getTextContent()));
                    element.removeAttribute("translatable");
                }
            }
            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            transformers.newTransformer().transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException failure) {
            throw new IllegalArgumentException("Cannot localize GTK UI definition", failure);
        }
    }

    /**
     * Formats a duration using translated units.
     * @param duration duration, or null when unavailable
     * @return a localized duration
     */
    public static String duration(Duration duration) {
        if (duration == null) return tr("N/A");
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds >= 3600) return tr("%dh %dm %ds", seconds / 3600, seconds % 3600 / 60, seconds % 60);
        if (seconds >= 60) return tr("%dm %ds", seconds / 60, seconds % 60);
        return tr("%ds", seconds);
    }

    /**
     * Formats a byte count for display using localized decimal separators and units.
     * @param bytes byte count
     * @return human-readable size
     */
    public static String bytes(long bytes) {
        if (bytes < 1024) return tr("%d B", bytes);
        if (bytes < 1024L * 1024) return tr("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return tr("%.1f MB", bytes / (1024.0 * 1024));
        return tr("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Formats conversion throughput without changing machine-readable tool arguments.
     * @param bytesPerSecond throughput in bytes per second
     * @return localized throughput, or a dash when unavailable
     */
    public static String speed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "--";
        if (bytesPerSecond < 1024) return tr("%.1f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return tr("%.1f KB/s", bytesPerSecond / 1024);
        if (bytesPerSecond < 1024L * 1024 * 1024) return tr("%.1f MB/s", bytesPerSecond / (1024 * 1024));
        return tr("%.1f GB/s", bytesPerSecond / (1024L * 1024 * 1024));
    }

    static Locale selectLocale(String preference, Map<String, String> environment, Locale fallback) {
        String override = environment.get("OMC_LANGUAGE");
        String requested = override == null || override.isBlank() ? preference : override;
        if (requested != null && !requested.isBlank() && !"system".equalsIgnoreCase(requested)) {
            return supportedLocale(requested);
        }
        String all = environment.get("LC_ALL");
        if (all != null && (all.equals("C") || all.startsWith("C.") || all.startsWith("POSIX"))) {
            return Locale.ENGLISH;
        }
        String languages = environment.get("LANGUAGE");
        if (languages != null && !languages.isBlank()) {
            for (String candidate : languages.split(":")) {
                Locale parsed = parseLocale(candidate);
                if (LANGUAGE_TAGS.contains(parsed.getLanguage())) return parsed;
            }
        }
        for (String key : List.of("LC_ALL", "LC_MESSAGES", "LANG")) {
            String value = environment.get(key);
            if (value != null && !value.isBlank()) return supportedLocale(value);
        }
        return supportedLocale(fallback.toLanguageTag());
    }

    private static Locale supportedLocale(String tag) {
        Locale parsed = parseLocale(tag);
        return LANGUAGE_TAGS.contains(parsed.getLanguage()) ? parsed : Locale.ENGLISH;
    }

    private static Locale parseLocale(String tag) {
        String normalized = tag.trim().split("[.@]", 2)[0].replace('_', '-');
        if (normalized.equalsIgnoreCase("C") || normalized.equalsIgnoreCase("POSIX")) return Locale.ENGLISH;
        return Locale.forLanguageTag(normalized);
    }
}
