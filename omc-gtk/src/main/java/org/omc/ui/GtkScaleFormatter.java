package org.omc.ui;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.DoubleFunction;

import org.gnome.glib.GLib;
import org.gnome.gtk.Scale;
import org.gnome.gtk.ScaleFormatValueFunc;

/**
 * Returns scale labels allocated for GTK ownership. java-gi 0.12.2's default
 * upcall returns an Arena.ofAuto() buffer, but GTK frees the callback result.
 * That causes a second free when Java collects the arena. Allocate with GLib
 * instead, as required by the GtkScaleFormatValueFunc transfer-full contract.
 *
 * @see <a href="https://docs.gtk.org/gtk4/callback.ScaleFormatValueFunc.html">GTK ownership contract</a>
 */
final class GtkScaleFormatter implements ScaleFormatValueFunc {
    private final DoubleFunction<String> formatter;

    GtkScaleFormatter(DoubleFunction<String> formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    /** {@inheritDoc} */
    @Override
    public String run(Scale scale, double value) {
        return formatter.apply(value);
    }

    /** Returns a NUL-terminated UTF-8 string that GTK, exclusively, will free. */
    @Override
    public MemorySegment upcall(MemorySegment scale, double value, MemorySegment userData) {
        String text = formatter.apply(value);
        if (text == null) return MemorySegment.NULL;
        long size = text.getBytes(StandardCharsets.UTF_8).length + 1L;
        MemorySegment result = GLib.malloc(size).reinterpret(size);
        result.setString(0, text, StandardCharsets.UTF_8);
        return result;
    }
}
