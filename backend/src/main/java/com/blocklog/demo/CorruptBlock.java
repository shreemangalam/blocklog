package com.blocklog.demo;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Deliberately flips one byte deep inside a {@code .blk} file's compressed
 * payload region — the last 128 bytes are safely inside every block. The
 * header CRC still verifies (so restart discovery keeps the block in the
 * catalog as available), but the payload CRC fails on any read. The engine
 * degrades the block to unavailable on the first scan and reports it via
 * {@code unavailable_blocks} on the next {@code /api/v1/status} poll and via
 * {@code partial=true} on any overlapping search.
 *
 * This is the failure-injection lever behind the "honest partiality" demo:
 * seed → corrupt → search shows how the engine surfaces damage instead of
 * silently returning a zero-match "success".
 *
 * Usage:
 *   java -cp target/classes com.blocklog.demo.CorruptBlock \
 *       --file data/&lt;uuid&gt;.blk [--offset -8] [--seed 42]
 *
 * A negative --offset is interpreted from end-of-file. Default is -8, safely
 * inside the compressed payload.
 */
public final class CorruptBlock {

    public static void main(String[] args) throws Exception {
        Path file = null;
        long offset = -8;
        Long seed = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file"   -> file = Path.of(args[++i]);
                case "--offset" -> offset = Long.parseLong(args[++i]);
                case "--seed"   -> seed = Long.parseLong(args[++i]);
                case "-h", "--help" -> {
                    System.out.println("CorruptBlock --file <path.blk> [--offset -8] [--seed N]");
                    System.exit(0);
                }
                default -> {
                    if (args[i].endsWith(".blk")) file = Path.of(args[i]);
                }
            }
        }
        if (file == null) {
            System.err.println("--file is required");
            System.exit(2);
        }
        if (!Files.isRegularFile(file)) {
            System.err.println("not a file: " + file);
            System.exit(2);
        }

        long size = Files.size(file);
        long pos = offset < 0 ? size + offset : offset;
        if (pos < 0 || pos >= size) {
            System.err.println("offset " + offset + " is out of range for file size " + size);
            System.exit(2);
        }

        // Pick a mask that's guaranteed to change the byte value.
        int mask;
        if (seed == null) {
            mask = 1 + new SecureRandom().nextInt(255);
        } else {
            // Deterministic mask derived from the seed.
            mask = 1 + (int) (Math.floorMod(seed, 255));
        }

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(pos);
            int original = raf.read();
            int corrupted = (original ^ mask) & 0xFF;
            raf.seek(pos);
            raf.write(corrupted);
            System.out.printf(Locale.ROOT,
                    "flipped byte at offset %d (from end: %d) in %s: 0x%02X -> 0x%02X%n",
                    pos, pos - size, file, original, corrupted);
        }

        System.out.println();
        System.out.println("Next steps to see the honest-partiality behavior:");
        System.out.println("  1. Restart the backend (mvnw spring-boot:run)");
        System.out.println("  2. Watch startup log: this block is still recovered (header CRC survives)");
        System.out.println("  3. Run a search that overlaps this block's time range");
        System.out.println("  4. Response will carry partial=true, unavailable_blocks >= 1,");
        System.out.println("     and the block file will now be reported unavailable on /status");
    }
}
