package org.omc.ui;

import org.omc.ui.SettingsDialogJavaGi;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the validatePresetName method in SettingsDialogJavaGi.
 * Tests the preset name validation logic as per Task 24 requirements.
 */
public class SettingsDialogPresetValidationTest {

    @Test
    void testValidatePresetName_ValidSimpleName_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "My Preset");
        assertNull(result);
    }

    @Test
    void testValidatePresetName_ValidUnderscoreName_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "video_1080p");
        assertNull(result);
    }

    @Test
    void testValidatePresetName_ValidHyphenName_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "Audio-HQ");
        assertNull(result);
    }

    @Test
    void testValidatePresetName_ValidNumericName_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "preset123");
        assertNull(result);
    }

    @Test
    void testValidatePresetName_NullName_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, (String) null);
        assertEquals("Preset name cannot be empty", result);
    }

    @Test
    void testValidatePresetName_EmptyString_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "");
        assertEquals("Preset name cannot be empty", result);
    }

    @Test
    void testValidatePresetName_WhitespaceOnly_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "   ");
        assertEquals("Preset name cannot be empty", result);
    }

    @Test
    void testValidatePresetName_TooLong_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String longName = "a".repeat(51);
        String result = (String) method.invoke(null, longName);
        assertEquals("Preset name must be 50 characters or less", result);
    }

    @Test
    void testValidatePresetName_InvalidCharacter_Exclamation_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "preset!");
        assertEquals("Preset name can only contain letters, numbers, spaces, hyphens, and underscores", result);
    }

    @Test
    void testValidatePresetName_InvalidCharacter_AtSymbol_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "my@preset");
        assertEquals("Preset name can only contain letters, numbers, spaces, hyphens, and underscores", result);
    }

    @Test
    void testValidatePresetName_InvalidCharacter_Bracket_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "test[1]");
        assertEquals("Preset name can only contain letters, numbers, spaces, hyphens, and underscores", result);
    }

    @Test
    void testValidatePresetName_LeadingSpace_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, " leading");
        assertEquals("Preset name cannot start or end with spaces", result);
    }

    @Test
    void testValidatePresetName_TrailingSpace_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "trailing ");
        assertEquals("Preset name cannot start or end with spaces", result);
    }

    @Test
    void testValidatePresetName_BothSpaces_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, " both ");
        assertEquals("Preset name cannot start or end with spaces", result);
    }

    @Test
    void testValidatePresetName_ReservedName_Default_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "default");
        assertEquals("This preset name is reserved", result);
    }

    @Test
    void testValidatePresetName_ReservedName_DefaultUppercase_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "DEFAULT");
        assertEquals("This preset name is reserved", result);
    }

    @Test
    void testValidatePresetName_ReservedName_None_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "none");
        assertEquals("This preset name is reserved", result);
    }

    @Test
    void testValidatePresetName_ReservedName_Custom_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "custom");
        assertEquals("This preset name is reserved", result);
    }

    @Test
    void testValidatePresetName_ReservedName_DoubleDash_ReturnsError() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "--something");
        assertEquals("This preset name is reserved", result);
    }

    @Test
    void testValidatePresetName_Exactly50Chars_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String name = "a".repeat(50);
        String result = (String) method.invoke(null, name);
        assertNull(result);
    }

    @Test
    void testValidatePresetName_SingleCharacter_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "a");
        assertNull(result);
    }

    @Test
    void testValidatePresetName_AllNumbers_ReturnsNull() throws Exception {
        Method method = getValidatePresetNameMethod();
        String result = (String) method.invoke(null, "12345");
        assertNull(result);
    }

    private Method getValidatePresetNameMethod() throws Exception {
        Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validatePresetName", String.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    void acceptsNamesInEverySupportedLanguage() throws ReflectiveOperationException {
        Method method = SettingsDialogJavaGi.class.getDeclaredMethod("validatePresetName", String.class);
        method.setAccessible(true);
        for (String name : new String[] { "Vidéo été", "Vídeo español", "Größe", "Áudio português", "高清视频", "高画質動画", "Cafe\u0301" }) {
            assertNull(method.invoke(null, name), name);
        }
        // A supplementary CJK letter is one code point, despite occupying two UTF-16 chars.
        assertNull(method.invoke(null, "\uD840\uDC00".repeat(50)));
        assertNotNull(method.invoke(null, "\uD840\uDC00".repeat(51)));
        assertNotNull(method.invoke(null, "../préréglage"));
    }
}
