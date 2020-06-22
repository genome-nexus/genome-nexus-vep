package org.genomenexus.vep_wrapper;

public class VepLaunchFailureException extends Exception {

    public VepLaunchFailureException(String msg) {
        super(msg);
    }

    public VepLaunchFailureException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public VepLaunchFailureException(Throwable cause) {
        super(cause);
    }
}
