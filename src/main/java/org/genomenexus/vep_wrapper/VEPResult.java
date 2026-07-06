package org.genomenexus.vep_wrapper;

public class VEPResult {
    private String output;
    private String stderr;

    VEPResult(String output, String stderr) {
        this.output = output;
        this.stderr = stderr;
    }

    public String getOutput() {
        return output;
    }

    public String getStderr() {
        return stderr;
    }
}
