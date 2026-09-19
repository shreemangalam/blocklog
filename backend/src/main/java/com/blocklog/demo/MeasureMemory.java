package com.blocklog.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

/**
 * Backend memory-overhead sampler.
 *
 * Samples heap (JMX committed + used) via {@code /actuator/metrics/{jvm.memory.*}}
 * every {@code intervalMs} for {@code durationSeconds}, reporting min / max /
 * mean under the observed workload. This process itself doesn't run the
 * backend; it hits the backend's own metrics endpoints so the numbers reflect
 * the JVM under test, not this measurement tool.
 *
 * Windows working set is not read here; the point is to expose the delta
 * between an idle and loaded JVM. For platform-specific resident-set
 * numbers, cross-reference {@code Get-Process -Name java} in PowerShell
 * or the OS-level equivalent; this tool intentionally stays JVM-only.
 *
 * Usage:
 *   java -cp target/classes com.blocklog.demo.MeasureMemory \
 *       [--url http://localhost:8080] [--seconds 30] [--interval 500]
 */
public final class MeasureMemory {

    public static void main(String[] args) throws Exception {
        String url = "http://localhost:8080";
        int seconds = 30;
        int intervalMs = 500;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url"      -> url = args[++i];
                case "--seconds"  -> seconds = Integer.parseInt(args[++i]);
                case "--interval" -> intervalMs = Integer.parseInt(args[++i]);
                case "-h", "--help" -> {
                    System.out.println("MeasureMemory --url <base> --seconds <n> --interval <ms>");
                    System.exit(0);
                }
            }
        }

        HttpClient http = HttpClient.newHttpClient();
        int samples = Math.max(1, (seconds * 1000) / intervalMs);
        long[] usedHeap = new long[samples];
        long[] committedHeap = new long[samples];
        long[] mmapBuffers = new long[samples];
        long[] queueBytes = new long[samples];
        long[] bufferBytes = new long[samples];

        System.out.printf(Locale.ROOT,
                "sampling backend at %s every %d ms for %d seconds (%d samples)%n",
                url, intervalMs, seconds, samples);

        long start = System.currentTimeMillis();
        for (int i = 0; i < samples; i++) {
            usedHeap[i]      = readMeasurement(http, url, "jvm.memory.used", "area:heap");
            committedHeap[i] = readMeasurement(http, url, "jvm.memory.committed", "area:heap");
            mmapBuffers[i]   = readMeasurement(http, url, "jvm.buffer.memory.used", "id:mapped");
            queueBytes[i]    = readGauge(http, url, "blocklog.queue.bytes");
            bufferBytes[i]   = readGauge(http, url, "blocklog.buffer.bytes");

            long next = start + (long) (i + 1) * intervalMs;
            long sleep = next - System.currentTimeMillis();
            if (sleep > 0) Thread.sleep(sleep);
        }

        System.out.println();
        System.out.println("metric                         min          max          mean         final");
        System.out.println("-----------------------------  -----------  -----------  -----------  -----------");
        report("heap.used (bytes)", usedHeap);
        report("heap.committed (bytes)", committedHeap);
        report("buffer.mapped.used (bytes)", mmapBuffers);
        report("blocklog.queue.bytes", queueBytes);
        report("blocklog.buffer.bytes", bufferBytes);
    }

    private static void report(String label, long[] samples) {
        long min = Long.MAX_VALUE, max = 0, sum = 0;
        for (long v : samples) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        long mean = sum / Math.max(samples.length, 1);
        long last = samples.length > 0 ? samples[samples.length - 1] : 0;
        System.out.printf(Locale.ROOT, "%-30s %11d %11d %11d %11d%n", label, min, max, mean, last);
    }

    private static long readMeasurement(HttpClient http, String base, String metric, String tag) throws Exception {
        String path = "/actuator/metrics/" + metric + "?tag=" + tag;
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return 0;
        return parseValue(resp.body(), "VALUE");
    }

    private static long readGauge(HttpClient http, String base, String metric) throws Exception {
        String path = "/actuator/metrics/" + metric;
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return 0;
        return parseValue(resp.body(), "VALUE");
    }

    /**
     * Pull the value out of an /actuator/metrics response of the shape:
     * {"name":"...","measurements":[{"statistic":"VALUE","value":1.234e7}, ...], ...}.
     * We look for the given statistic and read the number after "value".
     */
    private static long parseValue(String json, String statistic) {
        int cursor = 0;
        while (cursor < json.length()) {
            int statKey = json.indexOf("\"statistic\":\"" + statistic + "\"", cursor);
            if (statKey < 0) return 0;
            int valueKey = json.indexOf("\"value\":", statKey);
            if (valueKey < 0) return 0;
            int i = valueKey + 8;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            int start = i;
            while (i < json.length() && "0123456789.eE+-".indexOf(json.charAt(i)) >= 0) i++;
            if (i == start) return 0;
            try {
                return (long) Double.parseDouble(json.substring(start, i));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

}
