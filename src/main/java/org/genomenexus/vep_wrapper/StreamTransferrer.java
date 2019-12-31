package org.genomenexus.vep_wrapper;

import java.io.*;

public class StreamTransferrer extends Thread {
    private InputStream in;
    private FilterOutputStream out = null;
    public static final int DEFAULT_BUFFERSIZE = 60 * 1024;
    private int buffersize = DEFAULT_BUFFERSIZE;
    private int totalBytesRead = 0;
    private byte[] bytebuffer = null;
    private int bytebufferLastReadSize = -1;
    private boolean shutdownAnticipation = false;
    private boolean shutdownAsSoonAsPossible = false;

    public StreamTransferrer(InputStream inStream, FilterOutputStream outStream, int buffersize) {
        in = inStream;
        out = outStream;
        this.buffersize = buffersize;
    }

    public StreamTransferrer(InputStream inStream, int buffersize) {
        in = inStream;
        this.buffersize = buffersize;
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
        bytebufferLastReadSize = in.read(bytebuffer, 0, buffersize);
        totalBytesRead = totalBytesRead + bytebufferLastReadSize;
    }

    private void consume() throws IOException {
        while (true) {
            readNextChunk();
            if (bytebufferLastReadSize == -1) return;
            if (shutdownAsSoonAsPossible) return;
        }
    }

    private void transfer() throws IOException {
        while (true) {
            readNextChunk();
            if (bytebufferLastReadSize == -1) return;
            if (shutdownAsSoonAsPossible) return;
            out.write(bytebuffer,0,bytebufferLastReadSize);
        }
    }

    public void transferOrConsume() throws IOException {
        bytebuffer = new byte[buffersize];
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
        }
    }

    public void anticipateShutdown() {
        shutdownAnticipation = true;
    }

    public void requestShutdown() {
        shutdownAsSoonAsPossible = true;        
    }

}
