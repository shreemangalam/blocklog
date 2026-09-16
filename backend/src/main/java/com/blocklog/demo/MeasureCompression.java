package com.blocklog.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Compression-ratio measurement. Sums on-disk sizes of {@code *.blk} files
 * under the engine's data dir and compares them to the accepted-bytes
 * counter reported by {@code /api/v1/status}. Prints the ratio and a
 * per-block breakdown.
 *
 * The reported accepted_bytes is the total encoded raw record size before
 * LZ4 compression + header framing, so the ratio isolates the compressor's
 * effect on the payload. A ratio > 1 means we're storing less than we
 * accepted.
 *
 * Usage: java -cp target/classes com.blocklog.demo.MeasureCompression \
 *          [--data-dir ./data] [--url http://localhost:8080]
 */
public final class MeasureCompression {

    public static void main(String[] args) throws Exception {
        String dataDir = "./data";
        String url = "http://localhost:8080";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = args[++i];
                case "--url"      -> url = args[++i];
                case "-h", "--help" -> {
                    System.out.println("MeasureCompression --data-dir <path> --url <base>");
                    System.exit(0);
                }
            }
        }

        Path dir = Path.of(dataDir);
        if (!Files.isDirectory(dir)) {
            System.err.println("not a directory: " + dir);
            System.exit(2);
        }

        List<Path> blocks;
        try (Stream<Path> s = Files.list(dir)) {
            blocks = s.filter(p -> p.getFileName().toString().endsWith(".blk"))
                    .sorted()
                    .toList();
        }
        long totalOnDisk = 0;
        long biggest = 0;
        long smallest = Long.MAX_VALUE;
        for (Path p : blocks) {
            long sz = Files.size(p);
            totalOnDisk += sz;
            biggest = Math.max(biggest, sz);
            smallest = Math.min(smallest, sz);
        }
        if (blocks.isEmpty()) {
            System.out.println("no blocks under " + dir);
            System.exit(0);
        }

        long acceptedBytes = fetchAcceptedBytes(url);

        double ratio = acceptedBytes == 0 ? Double.NaN : (double) acceptedBytes / totalOnDisk;
        System.out.printf(Locale.ROOT, "data_dir           %s%n", dir.toAbsolutePath());
        System.out.printf(Locale.ROOT, "blocks             %d%n", blocks.size());
        System.out.printf(Locale.ROOT, "on_disk_bytes      %d (%.2f KiB)%n", totalOnDisk, totalOnDisk / 1024.0);
        System.out.printf(Locale.ROOT, "accepted_bytes     %d (%.2f KiB)  # raw encoded record bytes before LZ4%n",
                acceptedBytes, acceptedBytes / 1024.0);
        System.out.printf(Locale.ROOT, "compression_ratio  %.2fx  # accepted / on_disk%n", ratio);
        System.out.printf(Locale.ROOT, "block_size_bytes   min=%d max=%d mean=%d%n",
                smallest, biggest, totalOnDisk / blocks.size());
    }

    private static long fetchAcceptedBytes(String baseUrl) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("status endpoint returned " + resp.statusCode());
        }
        String body = resp.body();
        int key = body.indexOf("\"accepted_bytes\"");
        int colon = body.indexOf(':', key);
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        int start = i;
        while (i < body.length() && Character.isDigit(body.charAt(i))) i++;
        return Long.parseLong(body.substring(start, i));
    }
}
