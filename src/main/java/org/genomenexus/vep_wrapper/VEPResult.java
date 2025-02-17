package org.genomenexus.vep_wrapper;

public class VEPResult {
    private String output;
    private int exitCode;

    VEPResult(String output, int exitCode) {
        this.output = output;
        this.exitCode = exitCode;
    }

    public String getOutput() {
        return output;
    }

    public int getExitCode() {
        return exitCode;
    }
}
