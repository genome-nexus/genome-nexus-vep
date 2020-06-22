package org.genomenexus.vep_wrapper;

public class ProcessSurveyItem {

    private int processId;
    private int parentProcessId;
    private char stateCode;
    private String command;

    public ProcessSurveyItem(int processId, int parentProcessId, char stateCode, String command) {
        this.processId = processId;
        this.parentProcessId = parentProcessId;
        this.stateCode = stateCode;
        this.command = command;
    }

    public int getProcessId() {
        return processId;
    }

    public void setProcessId(int processId) {
        this.processId = processId;
    }

    public int getParentProcessId() {
        return parentProcessId;
    }

    public void setParentProcessId(int parentProcessId) {
        this.parentProcessId = parentProcessId;
    }

    public char getStateCode() {
        return stateCode;
    }

    public void setStateCode(char stateCode) {
        this.stateCode = stateCode;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
}
