package uk.org.squirm3.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts the Java2D user-interface scale to the real screen resolution so the
 * fixed-size Swing interface stays readable on HiDPI displays.
 *
 * On Windows and macOS the JDK scales Swing applications automatically; on
 * Linux/X11 it does not, so this helper probes the physical DPI of the
 * connected outputs (xrandr, xdpyinfo, sysfs DRM connectors) and sets
 * {@code sun.java2d.uiScale} before the AWT toolkit is initialized. The
 * property multiplies every font, icon, gap and component size throughout the
 * UI, including the simulation canvas.
 *
 * The automatic value can always be overridden with
 * {@code -Dsun.java2d.uiScale=<factor>}.
 */
public final class UiScaling {

    /** Logical (unscaled) reference DPI used by Java2D. */
    private static final double REFERENCE_DPI = 96.0;
    /** Fallback reference: logical height corresponding to a plain Full-HD screen. */
    private static final double REFERENCE_HEIGHT_PX = 1080.0;
    private static final double MIN_SCALE = 1.0;
    private static final double MAX_SCALE = 3.0;
    /** Detection noise below this factor is ignored so 96-DPI screens stay unscaled. */
    private static final double SCALE_THRESHOLD = 1.05;
    private static final double MM_PER_INCH = 25.4;

    /**
     * xrandr output line carrying the physical size of a connected output,
     * e.g. "eDP-1 connected primary 3408x2130+0+0 ... 344mm x 215mm".
     */
    private static final Pattern XRANDR_CONNECTED = Pattern
            .compile("\\bconnected\\b.*?(\\d+)mm x (\\d+)mm");
    /**
     * xrandr mode line carrying the native resolution, e.g. the mode flagged
     * with '+' ("   2560x1600    165.04*+"). The native mode is used rather
     * than the current geometry because compositors with fractional scaling
     * report a scaled framebuffer size that is larger than the real panel.
     */
    private static final Pattern XRANDR_NATIVE_MODE = Pattern
            .compile("^\\s+(\\d+)x(\\d+)\\s+.*\\+");
    private static final Pattern XDPYINFO_DPI = Pattern
            .compile("resolution:\\s+(\\d+)x\\d+ dots per inch");

    private UiScaling() {
    }

    /**
     * Detects the screen scale factor and sets {@code sun.java2d.uiScale}.
     * Must run before the AWT toolkit is initialized, otherwise the property
     * is read too late and has no effect.
     */
    public static void adaptToScreenResolution() {
        if (System.getProperty("sun.java2d.uiScale") != null
                || System.getenv("DISPLAY") == null
                || !System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }
        final Double scale = detectScale();
        if (scale != null && scale.doubleValue() > SCALE_THRESHOLD) {
            System.setProperty("sun.java2d.uiScale", scale.toString());
        }
    }

    private static Double detectScale() {
        final Integer dpi = probeDpi();
        if (dpi != null && dpi.intValue() > 0) {
            return Double.valueOf(clamp(dpi.doubleValue() / REFERENCE_DPI));
        }
        final Integer heightPixels = probeScreenHeightPixels();
        if (heightPixels != null && heightPixels.intValue() > 0) {
            return Double.valueOf(
                    clamp(heightPixels.doubleValue() / REFERENCE_HEIGHT_PX));
        }
        return null;
    }

    /**
     * Rounds to half steps (1.0, 1.5, 2.0 ...) and clamps to
     * [{@link #MIN_SCALE}, {@link #MAX_SCALE}]. Java2D honours integer or
     * half-integer scale factors cleanly.
     */
    private static double clamp(final double scale) {
        final double rounded = Math.round(scale * 2.0) / 2.0;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, rounded));
    }

    private static Integer probeDpi() {
        final Integer dpi = xrandrDpi();
        return dpi != null ? dpi : xdpyinfoDpi();
    }

    /**
     * Computes the physical DPI of the connected outputs from their native
     * mode and their reported size in millimetres. Returns the largest value
     * so the primary panel wins over any low-DPI external display.
     */
    private static Integer xrandrDpi() {
        try {
            final Process process = new ProcessBuilder("xrandr").start();
            int maxDpi = 0;
            int widthMm = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final Matcher connected = XRANDR_CONNECTED.matcher(line);
                    if (connected.find()) {
                        widthMm = Integer.parseInt(connected.group(1));
                        continue;
                    }
                    final Matcher mode = XRANDR_NATIVE_MODE.matcher(line);
                    if (mode.find() && widthMm > 0) {
                        final int widthPx = Integer.parseInt(mode.group(1));
                        final int dpi = Math
                                .round((float) (widthPx * MM_PER_INCH / widthMm));
                        maxDpi = Math.max(maxDpi, dpi);
                        widthMm = 0;
                    }
                }
            } finally {
                process.destroy();
            }
            return maxDpi > 0 ? Integer.valueOf(maxDpi) : null;
        } catch (IOException | NumberFormatException e) {
            // xrandr not installed or unparsable output
            return null;
        }
    }

    private static Integer xdpyinfoDpi() {
        try {
            final Process process = new ProcessBuilder("xdpyinfo").start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final Matcher matcher = XDPYINFO_DPI.matcher(line);
                    if (matcher.find()) {
                        return Integer.valueOf(matcher.group(1));
                    }
                }
            } finally {
                process.destroy();
            }
        } catch (IOException | NumberFormatException e) {
            // xdpyinfo not installed or unparsable output
        }
        return null;
    }

    /**
     * Reads the native mode of the largest connected DRM connector, e.g.
     * {@code /sys/class/drm/card0-eDP-1/modes} first line "2560x1600".
     */
    private static Integer probeScreenHeightPixels() {
        final File drm = new File("/sys/class/drm");
        final File[] connectors = drm.listFiles();
        if (connectors == null) {
            return null;
        }
        int maxHeight = 0;
        for (final File connector : connectors) {
            if (!"connected"
                    .equals(readFirstLine(new File(connector, "status")))) {
                continue;
            }
            final String mode = readFirstLine(new File(connector, "modes"));
            if (mode == null) {
                continue;
            }
            final int x = mode.indexOf('x');
            if (x < 0) {
                continue;
            }
            try {
                maxHeight = Math.max(maxHeight,
                        Integer.parseInt(mode.substring(x + 1).trim()));
            } catch (NumberFormatException e) {
                // skip unparsable mode line
            }
        }
        return maxHeight > 0 ? Integer.valueOf(maxHeight) : null;
    }

    private static String readFirstLine(final File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            final String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException e) {
            return null;
        }
    }
}
