package sqlg3.remote.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

public final class CountInputStream extends InputStream {

    private final InputStream is;
    private final Consumer<String> logger;

    private long count = 0;
    private boolean closed = false;

    public CountInputStream(InputStream is, Consumer<String> logger) {
        this.is = is;
        this.logger = logger;
    }

    public int read() throws IOException {
        int ret = is.read();
        if (ret >= 0) {
            count++;
        }
        return ret;
    }

    public int read(byte[] b, int off, int len) throws IOException {
        int ret = is.read(b, off, len);
        if (ret > 0) {
            count += ret;
        }
        return ret;
    }

    public long skip(long n) throws IOException {
        long ret = is.skip(n);
        count += ret;
        return ret;
    }

    public int available() throws IOException {
        return is.available();
    }

    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (logger != null) {
                logger.accept("READ: " + count);
            }
        }
        is.close();
    }

    public synchronized void mark(int readlimit) {
        is.mark(readlimit);
    }

    public synchronized void reset() throws IOException {
        is.reset();
    }

    public boolean markSupported() {
        return is.markSupported();
    }

    public long getCount() {
        return count;
    }
}
