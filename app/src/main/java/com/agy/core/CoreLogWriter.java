package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Appends core stdout to files/logs/core.log, rotating to core.log.1 at
 * {@link #MAX_BYTES} so a long-lived core cannot grow the log without bound.
 */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            return new RotatingWriter(new File(dir, "core.log"), MAX_BYTES);
        } catch (Exception e) {
            Log.w(TAG, "cannot open core log", e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        try {
            log.write(SensitiveDataRedactor.redactLine(line));
            log.write('\n');
            log.flush();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Size-capped writer: core.log rolls over to core.log.1 once it passes the limit. */
    private static final class RotatingWriter extends Writer {
        private final File file;
        private final long maxBytes;
        private OutputStreamWriter out;
        private long bytesWritten;

        RotatingWriter(File file, long maxBytes) throws IOException {
            this.file = file;
            this.maxBytes = maxBytes;
            // A fresh core start truncates the active log; the previous run's
            // tail is preserved in core.log.1.
            if (file.isFile() && file.length() > 0) {
                File rotated = new File(file.getParentFile(), file.getName() + ".1");
                if (rotated.exists()) {
                    rotated.delete();
                }
                file.renameTo(rotated);
            }
            this.out = openWriter(false);
            this.bytesWritten = 0;
        }

        private OutputStreamWriter openWriter(boolean append) throws IOException {
            return new OutputStreamWriter(new FileOutputStream(file, append),
                    Charset.forName("UTF-8"));
        }

        private synchronized void rotateIfNeeded(int incomingBytes) throws IOException {
            if (bytesWritten + incomingBytes <= maxBytes) {
                return;
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
            File rotated = new File(file.getParentFile(), file.getName() + ".1");
            if (rotated.exists()) {
                rotated.delete();
            }
            file.renameTo(rotated);
            out = openWriter(false);
            bytesWritten = 0;
        }

        @Override
        public synchronized void write(char[] cbuf, int off, int len) throws IOException {
            String text = new String(cbuf, off, len);
            int byteLen = text.getBytes(Charset.forName("UTF-8")).length;
            rotateIfNeeded(byteLen);
            out.write(text);
            bytesWritten += byteLen;
        }

        @Override
        public synchronized void flush() throws IOException {
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            out.close();
        }
    }
}
