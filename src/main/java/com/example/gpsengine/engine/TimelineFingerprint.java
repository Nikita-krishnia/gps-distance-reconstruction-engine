package com.example.gpsengine.engine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Computes a deterministic 64-character SHA-256 hexadecimal digest from the final
 * cleaned and merged trip timeline, active engine configuration, and rule version.
 *
 * <p>CANONICAL ENCODING CONVENTION:
 * To avoid locale, floating-point string formatting, or endian variations, coordinates
 * are encoded directly as raw 64-bit IEEE 754 bits via {@link Double#doubleToLongBits(double)}.
 * Timestamps are recorded as epoch seconds (long) plus nanosecond adjustments (int).
 * String identifiers (origin, anchor source, config fingerprint, rule version) are written
 * with UTF-8 byte length prefixes.
 *
 * <p>WHY COMPUTED AFTER CLEANING & ANCHORS:
 * Duplicate, out-of-order, or teleport pings dropped during GPS cleaning produce identical
 * post-cleaning timelines. Therefore, useless late pings yield the exact same fingerprint,
 * preventing redundant recomputations or duplicate database rows.
 */
public final class TimelineFingerprint {

    private TimelineFingerprint() {}

    /**
     * Calculates the 64-character hexadecimal SHA-256 fingerprint for a timeline.
     *
     * @param timeline the final merged list of TripPoints fed to DistanceEngine
     * @param configFingerprint canonical string summarizing all configuration thresholds
     * @param ruleVersion the engine rule version string (e.g. "2026.1")
     * @return 64-character lowercase hex string
     */
    public static String compute(List<TripPoint> timeline, String configFingerprint, String ruleVersion) {
        Objects.requireNonNull(timeline, "timeline must not be null");
        Objects.requireNonNull(configFingerprint, "configFingerprint must not be null");
        Objects.requireNonNull(ruleVersion, "ruleVersion must not be null");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DigestOutputStream dos = new DigestOutputStream(baos, md);
                 DataOutputStream out = new DataOutputStream(dos)) {

                // 1. Write rule version and config fingerprint
                writeUtf(out, ruleVersion);
                writeUtf(out, configFingerprint);

                // 2. Write count of timeline points
                out.writeInt(timeline.size());

                // 3. Write each timeline point canonically
                for (TripPoint p : timeline) {
                    out.writeLong(Double.doubleToLongBits(p.latitude()));
                    out.writeLong(Double.doubleToLongBits(p.longitude()));
                    out.writeLong(p.timestamp().getEpochSecond());
                    out.writeInt(p.timestamp().getNano());
                    writeUtf(out, p.origin().name());
                    writeUtf(out, p.anchorSource() != null ? p.anchorSource().name() : "");
                }
                out.flush();
            }

            byte[] digest = md.digest();
            return toHex(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to calculate timeline fingerprint", e);
        }
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}

