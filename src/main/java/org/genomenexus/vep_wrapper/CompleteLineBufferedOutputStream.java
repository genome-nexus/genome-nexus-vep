package org.genomenexus.vep_wrapper;

import java.io.*;

/** Buffers output so that only complete lines are send to the underlying output stream.
 *  As data is written to this object, complete lines (terminated by newline) are immediately
 *  written to the underlying output stream. Any bytes received without a newline are stored in
 *  the buffer until the newline is encountered. A method to purge the buffer is also available.
 */
public class CompleteLineBufferedOutputStream extends BufferedOutputStream {

    public static final int DEFAULT_BUFFER_SIZE = 393216; // 384 KB

    /** create a new CompleteLineBufferedOutputStream using the default buffer size
     */
    public CompleteLineBufferedOutputStream(OutputStream out) {
        super(out, DEFAULT_BUFFER_SIZE);
    }

    /** create a new CompleteLineBufferedOutputStream with the specified buffer size
     */
    public CompleteLineBufferedOutputStream(OutputStream out, int size) {
        super(out, size);
    }

    /** purge any buffered content without writing to the underlying stream
     */
    public void purge() {
        count = 0;
    }

    /** Flush does nothing; completed lines are written as soon as they are available, so the
     *  only content in the buffer is uncompleted lines, which are not written.
    */
    @Override
    public void flush() throws IOException {
    }

    /** Writes b.length bytes to this output stream.
      * Any complete lines present (considering the buffer as well) are flushed to the
      * underlying OutputStream. Any trailing bytes are stored in the buffer.
    */
    @Override
    public void write(byte[] b) throws IOException {
        if (out == null) {
            return; // no underlying output stream - nothing needs to be done
        }
        this.write(b, 0, b.length);
    }

    /** Writes len bytes to this output stream starting at offset off.
      * each line which is output (except the final one) have an added comma suffix.
    */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (out == null) {
            return; // no underlying output stream - nothing needs to be done
        }
        if (b == null) {
            throw new IOException("write() called with null array");
        }
        if (off < 0) {
            throw new IOException("write() called with negative offset: " + off);
        }
        if (len < 0) {
            throw new IOException("write() called with negative length : " + len);
        }
        if (off + len - 1 > b.length) {
            throw new IOException("write() called with offset (" + off +
                    ") and length (" + len + ") beyond array length (" + b.length + ")");
        }
        if (len == 0) {
            return; // nothing to write
        }
        int b_end_index = off + len;
        int next_newline_index = off;
        // scan for the first newline position
        while (next_newline_index < b_end_index) {
            if (b[next_newline_index] == '\n') {
                break;
            }
            ++next_newline_index;
        }
        while (next_newline_index != b_end_index) {
            // there is still a newline in b, so write buf if necessary and the next complete line in b
            if (count != 0) {
                out.write(buf, 0, count);
                count = 0;
            }
            out.write(b, off, next_newline_index - off + 1); // write next complete line
            len = len - next_newline_index + off - 1; // reset args for remainder of b
            off = ++next_newline_index;
            // scan for the next newline position
            while (next_newline_index < b_end_index) {
                if (b[next_newline_index] == '\n') {
                    break;
                }
                ++next_newline_index;
            }
        }
        // there is no newline left in b : try to add remaining data to buf
        if (count + len > buf.length) {
            throw new IOException("buffer overflow : CompleteLineBufferedOutputStream with capacity " + buf.length +
                    " contained " + count + " bytes, and " + len +
                    " additional bytes were attempted to be written without completing a line.");
        }
        // cannibalize args
        while (len > 0) {
            buf[count++] = b[off++];
            --len;
        }
    }

    /** Writes the specified byte to this output stream.
      * If this byte completes a line (considering the buffer as well), the line is written to
      * underlying OutputStream. Otherwise the byte is stored in the buffer.
    */
    @Override
    public void write(int b) throws IOException {
        if (out == null) {
            return; // no underlying output stream - nothing needs to be done
        }
        if (b == '\n') {
            if (count == buf.length) {
                // buf cannot hold one more character
                out.write(buf, 0, count);
                out.write(b);
            } else {
                buf[count++] = (byte) (b & 0x00FF); // ignore all but the 8 low-order bits
                out.write(buf, 0, count);
            }
            count = 0;
        } else {
            if (count == buf.length) {
                // buf cannot hold one more character
                throw new IOException("buffer overflow : CompleteLineBufferedOutputStream with capacity " + buf.length +
                        " contained " + count +
                        " bytes, and one additional byte was written without completing a line.");
            }
            buf[count++] = (byte) (b & 0X00FF);
        }
    }

}
