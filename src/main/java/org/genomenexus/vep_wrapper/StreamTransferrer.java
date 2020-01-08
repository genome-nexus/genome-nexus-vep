package org.genomenexus.vep_wrapper;

import java.io.*;

public class StreamTransferrer extends Thread {
    private InputStream in;
    private FilterOutputStream out = null;
    public static final int DEFAULT_BUFFERSIZE = 60 * 1024;
    private int bufferSize = DEFAULT_BUFFERSIZE;
    private int totalBytesRead = 0;
    private byte[] byteBuffer = null;
    private int byteBufferLastReadSize = -1;
    private boolean shutdownAnticipation = false;
    private boolean shutdownAsSoonAsPossible = false;
    private String inputStreamName;

    public StreamTransferrer(InputStream inStream, FilterOutputStream outStream, int bufferSize, String inputStreamName) {
        in = inStream;
        out = outStream;
        this.bufferSize = bufferSize;
        this.inputStreamName = inputStreamName;
    }

    public StreamTransferrer(InputStream inStream, int bufferSize) {
        in = inStream;
        this.bufferSize = bufferSize;
    }

    public StreamTransferrer(InputStream inStream, FilterOutputStream outStream) {
        in = inStream;
        out = outStream;
    }

    public StreamTransferrer(InputStream inStream) {
        in = inStream;
    }

    public int getTotalBytesRead() {
        return totalBytesRead;
    }

    private void readNextChunk() throws IOException {
        byteBufferLastReadSize = in.read(byteBuffer, 0, bufferSize);
        totalBytesRead = totalBytesRead + byteBufferLastReadSize;
    }

    private void consume() throws IOException {
        while (true) {
            readNextChunk();
            if (byteBufferLastReadSize == -1) return;
            if (shutdownAsSoonAsPossible) return;
        }
    }

    private void transfer() throws IOException {
        while (true) {
            readNextChunk();
            if (byteBufferLastReadSize == -1) return;
            if (shutdownAsSoonAsPossible) return;
            out.write(byteBuffer,0,byteBufferLastReadSize);
        }
    }

    public void transferOrConsume() throws IOException {
        byteBuffer = new byte[bufferSize];
        if (out == null) {
            consume();
        } else {
            transfer();
            out.flush();
        }
    }

    @Override
    public void run() {
        try {
            transferOrConsume();
        } catch (IOException e) {
            if (shutdownAsSoonAsPossible) return;
            if (!shutdownAnticipation) {
                System.out.println("error during stream copy in class StreamTransferrer");
            }
        } catch (Throwable e) {
            if (shutdownAsSoonAsPossible) return;
        } finally {
            System.out.println("StreamTransferrer for stream " + inputStreamName + " exited");
        }
    }

    public void anticipateShutdown() {
        shutdownAnticipation = true;
    }

    public void requestShutdown() {
        shutdownAsSoonAsPossible = true;        
    }

}
