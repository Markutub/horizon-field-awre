package dev.awre.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Append-only JSON-lines file with ONE backup generation: when a new line would take the file past {@code maxBytes}, the file is renamed to
 * {@code <name>.1} (replacing an older backup) and a fresh file starts, so disk use is bounded by about 2 x maxBytes. A line must be a single
 * line (no CR/LF), otherwise the file would stop being JSONL. Thread-safe. I/O errors are thrown to the caller, which decides what to log.
 */
public final class JsonlLog {
    private final Path file;
    private final long maxBytes;

    public JsonlLog(Path file, long maxBytes) {
        if (maxBytes < 1024) throw new IllegalArgumentException("maxBytes must be >= 1024: " + maxBytes);
        this.file = file; this.maxBytes = maxBytes;
    }

    public Path file() { return file; }

    public synchronized void append(String line) throws IOException {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new IllegalArgumentException("a JSONL line must not contain a line break");
        byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (data.length > maxBytes) throw new IllegalArgumentException("one line is " + data.length + " bytes, larger than the whole log limit " + maxBytes);
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (Files.exists(file) && Files.size(file) + data.length > maxBytes) {
            Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
