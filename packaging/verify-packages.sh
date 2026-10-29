#!/bin/bash
# Validate the artifacts that will be attached to a release. Run in omc-dev.
set -euo pipefail
PACKAGE_ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$PACKAGE_ROOT/dist"
VERSION="${1:?Pass the package version}"
[[ "$VERSION" =~ ^[0-9]+([.][0-9]+){1,3}$ ]] || exit 2
CHECK_ROOT="$(mktemp -d)"
trap 'rm -rf "$CHECK_ROOT"' EXIT
DEB="$DIST_DIR/open-media-converter_${VERSION}_amd64.deb"
RPM="$DIST_DIR/open-media-converter-${VERSION}-1.x86_64.rpm"
ARCH="$DIST_DIR/open-media-converter-${VERSION}-1-x86_64.pkg.tar.zst"
APPIMAGE="$DIST_DIR/Open_Media_Converter-${VERSION}-x86_64.AppImage"
for artifact in "$DEB" "$RPM" "$ARCH" "$APPIMAGE"; do test -s "$artifact"; done
[[ "$(dpkg-deb -f "$DEB" Version)" == "$VERSION" ]]
[[ "$(dpkg-deb -f "$DEB" Architecture)" == amd64 ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{VERSION}-%{RELEASE}' "$RPM")" == "$VERSION-1" ]]
[[ "$(rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '%{ARCH}' "$RPM")" == x86_64 ]]
# Native packages must install supported converters, not merely recommend them.
dpkg-deb -f "$DEB" Depends > "$CHECK_ROOT/deb-depends"
rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --requires "$RPM" > "$CHECK_ROOT/rpm-requires"
for dependency in 'ffmpeg >= 6.1' 'pandoc >= 3.1'; do
    read -r tool operator minimum <<< "$dependency"
    grep -Eq "(^|, )$tool \\(>= $minimum\\)(,|$)" "$CHECK_ROOT/deb-depends"
    grep -Fxq "$dependency" "$CHECK_ROOT/rpm-requires"
done
rpm --dbpath "$CHECK_ROOT/rpmdb" -K --nosignature "$RPM"
rpm --dbpath "$CHECK_ROOT/rpmdb" -qp --qf '[%{FILEUSERNAME}:%{FILEGROUPNAME}\n]' "$RPM" > "$CHECK_ROOT/rpm-owners"
if grep -vqx 'root:root' "$CHECK_ROOT/rpm-owners"; then
    echo 'RPM contains non-root payload ownership' >&2; exit 1
fi
mkdir "$CHECK_ROOT/deb" "$CHECK_ROOT/rpm" "$CHECK_ROOT/arch"
dpkg-deb --fsys-tarfile "$DEB" > "$CHECK_ROOT/deb.tar"
zstd -dq "$ARCH" -o "$CHECK_ROOT/arch.tar"
python3 - "$CHECK_ROOT/deb.tar" "$CHECK_ROOT/arch.tar" <<'PY'
import sys, tarfile
for archive in sys.argv[1:]:
    with tarfile.open(archive) as package:
        assert all(m.uid == 0 and m.gid == 0 for m in package.getmembers()), archive
PY
tar -xf "$CHECK_ROOT/deb.tar" -C "$CHECK_ROOT/deb"
rpm2cpio "$RPM" | bsdtar -xf - -C "$CHECK_ROOT/rpm"
tar -xf "$CHECK_ROOT/arch.tar" -C "$CHECK_ROOT/arch"
grep -qx "pkgver = ${VERSION}-1" "$CHECK_ROOT/arch/.PKGINFO"
grep -qx 'arch = x86_64' "$CHECK_ROOT/arch/.PKGINFO"
grep -Fxq 'depend = ffmpeg>=6.1' "$CHECK_ROOT/arch/.PKGINFO"
grep -Fxq 'depend = pandoc>=3.1' "$CHECK_ROOT/arch/.PKGINFO"
test -s "$CHECK_ROOT/arch/.MTREE"
test -s "$CHECK_ROOT/arch/.BUILDINFO"
python3 - "$CHECK_ROOT/arch" <<'PYMTREE'
import gzip, hashlib, pathlib, shlex, sys
root = pathlib.Path(sys.argv[1])
checked = 0
for line in gzip.open(root / '.MTREE', 'rt'):
    if not line.startswith('./'):
        continue
    fields = shlex.split(line)
    digest = next((v.split('=', 1)[1] for v in fields[1:] if v.startswith('sha256digest=')), None)
    if digest:
        path = root / fields[0]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, str(path)
        checked += 1
assert checked > 0, 'MTREE contains no payload hashes'
print(f'Validated {checked} Arch MTREE hashes')
PYMTREE
# Native formats must carry precisely the same application, runtime and licensing files.
for format in deb rpm arch; do
    root="$CHECK_ROOT/$format"
    test -x "$root/usr/bin/open-media-converter"
    test -s "$root/usr/share/doc/open-media-converter/copyright"
    test -s "$root/usr/share/licenses/open-media-converter/LICENSE"
    test -s "$root/usr/share/applications/open-media-converter.desktop"
    test -s "$root/usr/share/applications/org.omc.OpenMediaConverter.desktop"
    grep -qx 'NoDisplay=true' "$root/usr/share/applications/org.omc.OpenMediaConverter.desktop"
    grep -qx 'Exec=/usr/bin/open-media-converter %F' "$root/usr/share/applications/open-media-converter.desktop"
    grep -qx 'Icon=open-media-converter' "$root/usr/share/applications/open-media-converter.desktop"
    grep -qx 'StartupWMClass=open-media-converter' "$root/usr/share/applications/open-media-converter.desktop"
    test -s "$root/usr/share/metainfo/open-media-converter.metainfo.xml"
    test -s "$root/usr/share/man/man1/open-media-converter.1"
    # Native packages must not overwrite the shared system icon-theme index.
    test ! -e "$root/usr/share/icons/hicolor/index.theme"
    test -s "$root/usr/share/icons/hicolor/scalable/apps/open-media-converter.svg"
    test -s "$root/usr/share/icons/hicolor/256x256/apps/open-media-converter.png"
    (cd "$root" && find opt usr -type f -print0 | sort -z | xargs -0 sha256sum) > "$CHECK_ROOT/$format.sha256"
done
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/rpm.sha256"
diff -u "$CHECK_ROOT/deb.sha256" "$CHECK_ROOT/arch.sha256"
APP_ROOT="$CHECK_ROOT/deb/opt/open-media-converter"
python3 - "$APP_ROOT/omc.jar" <<'PYNATIVES'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as jar:
    names = set(jar.namelist())
    for ui in ('ui/main_window.ui', 'ui/settings_dialog.ui'):
        assert ui in names, f'Missing packaged UI resource: {ui}'
    for suffix in ('', '_fr', '_es', '_de', '_pt', '_zh', '_ja'):
        catalog = f'i18n/messages{suffix}.properties'
        assert catalog in names, f'Missing packaged language catalog: {catalog}'
        assert jar.read(catalog).decode('utf-8').strip(), f'Empty language catalog: {catalog}'
    # A previous bundled build must never contaminate a native package.
    for tool in ('ffmpeg', 'ffprobe', 'pandoc'):
        assert not any(n.endswith('/' + tool) for n in names), f'Native jar embeds {tool}'
print('Native jars contain UI resources, seven language catalogs and no embedded conversion tools')
PYNATIVES
# AppImage carries the same application code/resources plus verified tools.
(cd "$CHECK_ROOT" && "$APPIMAGE" --appimage-extract >/dev/null 2>&1)
APPIMAGE_ROOT="$CHECK_ROOT/squashfs-root/opt/open-media-converter"
test -x "$CHECK_ROOT/squashfs-root/AppRun"
test -x "$APPIMAGE_ROOT/runtime/bin/java"
python3 "$PACKAGE_ROOT/appimage/check-abi.py" "$CHECK_ROOT/squashfs-root"
diff -qr "$APP_ROOT/runtime" "$APPIMAGE_ROOT/runtime"
python3 - "$APP_ROOT/omc.jar" "$APPIMAGE_ROOT/omc.jar" <<'PYAPPIMAGE'
import hashlib, sys, zipfile

def application_entries(jar):
    return {n for n in jar.namelist() if not n.endswith('/') and not n.startswith('bin/')}

def manifest_without_build_time(content):
    return '\n'.join(line for line in content.decode().splitlines()
                     if not line.startswith('Build-Time: '))

with zipfile.ZipFile(sys.argv[1]) as native, zipfile.ZipFile(sys.argv[2]) as portable:
    names = application_entries(native)
    assert names == application_entries(portable), 'Application entries differ across jar variants'
    for name in names:
        left, right = native.read(name), portable.read(name)
        if name == 'META-INF/MANIFEST.MF':
            left, right = manifest_without_build_time(left), manifest_without_build_time(right)
        assert left == right, f'Application resource differs across jar variants: {name}'
    for tool, directory in (('ffmpeg', 'ffmpeg'), ('ffprobe', 'ffmpeg'), ('pandoc', 'pandoc')):
        resource = f'bin/linux-x86_64/{directory}/{tool}'
        with portable.open(resource) as binary:
            header = binary.read(20)
            assert header[:6] == b'\x7fELF\x02\x01' and int.from_bytes(header[18:20], 'little') == 62, \
                f'AppImage tool must be Linux x86-64 ELF: {tool}'
        with portable.open(resource) as binary:
            actual = hashlib.file_digest(binary, 'sha256').hexdigest()
        expected = portable.read(resource + '.sha256').decode().split()[0].lower()
        assert actual == expected, f'AppImage tool checksum mismatch: {tool}'
        assert f'bin/linux-x86_64/{directory}/SOURCE.json' in portable.namelist()
    assert 'bin/linux-x86_64/pandoc/COPYING' in portable.namelist()
    assert any(n.startswith('bin/linux-x86_64/ffmpeg/') and
               n.rsplit('/', 1)[-1].lower().startswith(('license', 'copying'))
               for n in portable.namelist()), 'Missing FFmpeg license'
print('AppImage contains matching application code, runtime and checksum-verified conversion tools')
PYAPPIMAGE
"$APP_ROOT/runtime/bin/java" --list-modules > "$CHECK_ROOT/modules"
grep -q '^jdk.localedata@' "$CHECK_ROOT/modules"
grep -q '^jdk.zipfs@' "$CHECK_ROOT/modules"
# Exercise native GTK resource loading with the actual bundled JVM/JAR.
cat > "$CHECK_ROOT/PackageRuntimeCheck.java" <<'JAVA'
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.omc.core.ConfigurationManager;
import org.omc.model.AudioSettings;
import org.omc.model.ConversionResult;
import org.omc.model.DocumentSettings;
import org.omc.model.FileFormat;
import org.omc.model.VideoSettings;
import org.omc.service.FFmpegService;
import org.omc.service.PandocService;
import org.omc.service.ToolDiscovery;
import org.omc.ui.I18n;

public class PackageRuntimeCheck {
    public static void main(String[] args) throws Exception {
        if (!"amd64".equals(System.getProperty("os.arch"))) {
            throw new AssertionError("Bundled JVM must target amd64");
        }
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
        for (String ui : new String[]{"/ui/main_window.ui", "/ui/settings_dialog.ui"}) {
            String uiXml;
            try (var inputStream = PackageRuntimeCheck.class.getResourceAsStream(ui)) {
                if (inputStream == null) {
                    throw new AssertionError("Missing packaged UI resource: " + ui);
                }
                uiXml = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
            }
            for (String language : List.of("en", "fr", "es", "de", "pt", "zh", "ja")) {
                I18n.setLanguage(language);
                require(I18n.locale().getLanguage().equals(language), "Wrong packaged display language");
                require(language.equals("en") || !I18n.tr("Settings").equals("Settings"),
                        "Missing packaged translation for " + language);
                String translated = I18n.translateUi(uiXml);
                var builder = new GtkBuilder();
                builder.addFromString(translated, translated.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        System.out.println("Packaged runtime and GTK UI definitions passed in all seven languages");
        checkConversions(args[0], Path.of(args[1]));
    }

    private static void checkConversions(String mode, Path root) throws Exception {
        Files.createDirectories(root);
        var configuration = new ConfigurationManager(root.resolve("config"), root.resolve("data"),
                root.resolve("cache"));
        // Use the same discovery entry point as DependencyFactory. Reuse the
        // config/cache between modes to cover upgrades and AppImage switching.
        var tools = new ToolDiscovery(configuration).discoverTools();
        boolean bundled = mode.equals("appimage");
        Path cache = configuration.getCacheDirectory().resolve("tools").toAbsolutePath().normalize();
        for (Path tool : new Path[]{tools.getFfmpegPath(), tools.getFfprobePath(), tools.getPandocPath()}) {
            require(tool != null && Files.isExecutable(tool), "Missing executable in " + mode + ": " + tool);
            require(tool.toAbsolutePath().normalize().startsWith(cache) == bundled,
                    "Wrong tool origin in " + mode + ": " + tool);
            System.out.println(mode + " uses " + tool);
        }
        requireVersion(tools.getFfmpegVersion(), 6, 1, "FFmpeg");
        requireVersion(tools.getPandocVersion(), 3, 1, "Pandoc");
        Path work = Files.createTempDirectory(root, mode + "-");
        String ffmpeg = tools.getFfmpegPath().toString();
        String ffprobe = tools.getFfprobePath().toString();
        String encoders = command(work, ffmpeg, "-hide_banner", "-encoders");
        for (String encoder : List.of("libx264", "libx265", "libvpx", "libvpx-vp9", "aac",
                "libmp3lame", "libopus", "libvorbis", "flac")) {
            require(encoders.lines().map(String::trim).map(line -> line.split("\\s+"))
                    .anyMatch(fields -> fields.length > 1 && fields[1].equals(encoder)),
                    "FFmpeg lacks required encoder: " + encoder);
        }
        Path input = work.resolve("input.mkv");
        command(work, ffmpeg, "-v", "error", "-f", "lavfi", "-i", "color=red:size=80x40:duration=0.3",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.3", "-c:v", "ffv1",
                "-c:a", "pcm_s16le", input.toString());
        var media = new FFmpegService(tools.getFfmpegPath(), tools.getFfprobePath());
        Path video = work.resolve("output.mp4");
        requireSuccess(media.convertVideo(input, video,
                VideoSettings.builder().outputFormat(FileFormat.MP4).build(), (p, b, s) -> {}));
        require(command(work, ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name", "-of", "default=nw=1:nk=1", video.toString()).trim().equals("h264"),
                "Packaged video conversion did not produce H.264");
        Path audio = work.resolve("output.mp3");
        requireSuccess(media.convertAudio(input, audio,
                AudioSettings.builder().outputFormat(FileFormat.MP3).build(), (p, b, s) -> {}));
        require(command(work, ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
                "stream=codec_name", "-of", "default=nw=1:nk=1", audio.toString()).trim().equals("mp3"),
                "Packaged audio conversion did not produce MP3");
        command(work, ffmpeg, "-v", "error", "-i", video.toString(), "-f", "null", "-");
        command(work, ffmpeg, "-v", "error", "-i", audio.toString(), "-f", "null", "-");
        Path markdown = Files.writeString(work.resolve("input.md"), "# Package conversion check\n\nContent survives.\n");
        Path document = work.resolve("output.docx");
        requireSuccess(new PandocService(tools.getPandocPath()).convertDocument(markdown, document,
                DocumentSettings.builder().outputFormat(FileFormat.DOCX).build(), (p, b, s) -> {}));
        try (var zip = new ZipFile(document.toFile());
                var xml = zip.getInputStream(zip.getEntry("word/document.xml"))) {
            require(new String(xml.readAllBytes(), StandardCharsets.UTF_8).contains("Content survives."),
                    "Packaged document conversion lost content");
        }
        System.out.println(mode + " tool discovery, encoder capabilities and real video/audio/document conversions passed");
    }

    private static void requireVersion(String version, int major, int minor, String tool) {
        require(version != null && version.matches("[0-9]+(\\.[0-9]+)*"), "Unrecognized " + tool + " version: " + version);
        String[] parts = version.split("\\.");
        int actualMajor = Integer.parseInt(parts[0]);
        int actualMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        require(actualMajor > major || (actualMajor == major && actualMinor >= minor),
                tool + " " + version + " is older than supported " + major + "." + minor);
    }

    private static String command(Path work, String... args) throws Exception {
        Path log = Files.createTempFile(work, "command-", ".log");
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            require(process.waitFor(30, TimeUnit.SECONDS), "Tool command timed out: " + List.of(args));
            String output;
            try (var stream = Files.newInputStream(log)) {
                output = new String(stream.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
            }
            require(process.exitValue() == 0, "Tool command failed: " + List.of(args) + "\n" + output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void requireSuccess(ConversionResult result) {
        require(result.success(), result.errorMessage().orElse("Conversion failed") + "\n" + result.toolOutput().orElse(""));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
JAVA
javac --release 23 -cp "$APP_ROOT/omc.jar" "$CHECK_ROOT/PackageRuntimeCheck.java"
# Use an isolated home/cache for every check; never modify the verifier user's
# settings or leave extracted tools behind. Both modes share this test cache.
export XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data"
export XDG_CACHE_HOME="$CHECK_ROOT/cache" XDG_STATE_HOME="$CHECK_ROOT/state"
for mode in native appimage native; do
    runtime_root="$APP_ROOT"
    library_options=()
    if [[ "$mode" == appimage ]]; then
        runtime_root="$APPIMAGE_ROOT"
        library_options=("-Djavagi.path=$CHECK_ROOT/squashfs-root/usr/lib/omc"
            "-Djava.library.path=$CHECK_ROOT/squashfs-root/usr/lib/omc")
    fi
    xvfb-run -a timeout -k 10s 120s "$runtime_root/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
        "${library_options[@]}" \
        -cp "$CHECK_ROOT:$runtime_root/omc.jar" PackageRuntimeCheck "$mode" "$CHECK_ROOT/tool-work"
done
# Bound the real application's startup; its GTK entry point has no dedicated smoke switch.
set +e
XDG_CONFIG_HOME="$CHECK_ROOT/config" XDG_DATA_HOME="$CHECK_ROOT/data" XDG_STATE_HOME="$CHECK_ROOT/state" \
    xvfb-run -a timeout -k 10s 25s "$APP_ROOT/runtime/bin/java" --enable-native-access=ALL-UNNAMED \
    -jar "$APP_ROOT/omc.jar" > "$CHECK_ROOT/launcher.log" 2>&1
launch_status=$?
set -e
cat "$CHECK_ROOT/launcher.log"
[[ "$launch_status" == 124 ]]
if grep -Eq 'Startup failed|NoClassDefFoundError|NoSuchMethodError' "$CHECK_ROOT/launcher.log"; then
    exit 1
fi
timeout -k 10s 30s "$CHECK_ROOT/squashfs-root/AppRun" --version
echo 'All package formats and the bundled launcher passed'
