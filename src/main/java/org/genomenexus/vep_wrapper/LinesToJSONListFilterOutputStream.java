package org.genomenexus.vep_wrapper;

import java.io.*;

/** Accepts lines of valid JSON elements (one per line) and outputs a valid JSON list
*/
public class LinesToJSONListFilterOutputStream extends FilterOutputStream {

    private boolean someDataHasBeenWritten = false;
    private boolean lastWriteEndedLineWithoutBeginningAnother = true;

    /** Creates an output stream filter built on top of the specified underlying output stream
    */
    public LinesToJSONListFilterOutputStream(OutputStream out) {
        super(out);
    }

    /** Outputs the ending JSON list bracket
    */
    public void complete() throws IOException {
        if (out != null) {
            if (!someDataHasBeenWritten) {
                out.write('[');
                lastWriteEndedLineWithoutBeginningAnother = true;
            }
            if (lastWriteEndedLineWithoutBeginningAnother) {
                out.write('\n'); // write the newline which was held back to determine if a comma was needed
            }
            out.write(']');
            out.write('\n');
            this.flush();
        }
    }

    /** Flushes this output stream and forces any buffered output bytes to be written out to the stream.
    */
    @Override
    public void flush() throws IOException {
        if (out != null) {
            super.flush();
        }
    }

    /** Writes b.length bytes to this output stream, modified so that
      * empty lines are dropped,
      * each line which is output (except the final one) have an added comma suffix.
    */
    @Override
    public void write(byte[] b) throws IOException {
        if (out != null) {
            this.write(b, 0, b.length);
        }
    }

    /** Writes len bytes to this output stream starting at offset off, modified so that
      * empty lines are dropped,
      * each line which is output (except the final one) have an added comma suffix.
    */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (out != null) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }
    }

    /** Writes the specified byte to this output stream.len bytes to this output stream, modified so that
      * empty lines are dropped,
      * each line which is output (except the final one) have an added comma suffix.
    */
    @Override
    public void write(int b) throws IOException {
        if (out != null) {
            if (b == '\n') {
                // on writes that end in newline, wait until more data comes before appending a comma to the line
                lastWriteEndedLineWithoutBeginningAnother = true;
            } else {
                if (!someDataHasBeenWritten) {
                    out.write('[');
                    lastWriteEndedLineWithoutBeginningAnother = true;
                }
                if (lastWriteEndedLineWithoutBeginningAnother) {
                    if (someDataHasBeenWritten) {
                        out.write(','); // append the comma for the output list element
                    }
                    out.write('\n'); // write the newline which was held back to determine if a comma was needed
                }
                out.write(b);
                someDataHasBeenWritten = true;
                lastWriteEndedLineWithoutBeginningAnother = false;
            }
        }
    }

}
