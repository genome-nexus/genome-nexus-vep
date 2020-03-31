package org.genomenexus.vep_wrapper;

import java.io.*;
import java.util.*;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
public class CompleteLineBufferedOutputStreamTest {

    @Test
    public void testCompleteLineBlock() throws IOException {
        List<String> inputs = new ArrayList<>(Arrays.asList("this is\n", "a three\n", "line file\n"));
        String expectedOutput = "this is\na three\nline file\n";
        runTestByteArray(inputs, expectedOutput, null);
    }

    @Test
    public void testPartial() throws IOException {
        List<String> inputs = new ArrayList<>(Arrays.asList("fragment only"));
        String expectedOutput = "";
        runTestByteArray(inputs, expectedOutput, null);
    }

    @Test
    public void testSplitLineBlock() throws IOException {
        List<String> inputs = new ArrayList<>(Arrays.asList("this ", "is\n", "a ", "three\n", "line ", "file\n"));
        String expectedOutput = "this is\na three\nline file\n";
        runTestByteArray(inputs, expectedOutput, null);
    }

    @Test
    public void testBlankLines() throws IOException {
        List<String> inputs = new ArrayList<>(Arrays.asList("\n", "this is\n\n\n", "a three\n\n", "line file\n", "\n"));
        String expectedOutput = "\nthis is\n\n\na three\n\nline file\n\n";
        runTestByteArray(inputs, expectedOutput, null);
    }

    @Test
    public void testPurgeOfFragment() throws IOException {
        List<String> inputs = new ArrayList<>(Arrays.asList("this is\n", "a three\n", "this fragment will be", " purged", "line file\n"));
        String expectedOutput = "this is\na three\nline file\n";
        Set<Integer> purgeAfterSet = new HashSet<>();
        purgeAfterSet.add(new Integer(3));
        runTestByteArray(inputs, expectedOutput, purgeAfterSet);
    }

    @Test
    public void testCompleteLineBlockByByte() throws IOException {
        String input = "this is\na three\nline file\n";
        String expectedOutput = "this is\na three\nline file\n";
        runTestByte(input, expectedOutput, null);
    }

    @Test
    public void testPartialByByte() throws IOException {
        String input = "fragment only";
        String expectedOutput = "";
        runTestByte(input, expectedOutput, null);
    }

    @Test
    public void testSplitLineBlockByByte() throws IOException {
        String input = "this is\na three\nline file\n";
        String expectedOutput = "this is\na three\nline file\n";
        runTestByte(input, expectedOutput, null);
    }

    @Test
    public void testBlankLinesByByte() throws IOException {
        String input = "\nthis is\n\n\na three\n\nline file\n\n";
        String expectedOutput = "\nthis is\n\n\na three\n\nline file\n\n";
        runTestByte(input, expectedOutput, null);
    }

    @Test
    public void testPurgeOfFragmentByByte() throws IOException {
        String input = "this is\na three\nthis fragment will be purgedline file\n";
        String expectedOutput = "this is\na three\nline file\n";
        Set<Integer> purgeAfterSet = new HashSet<>();
        purgeAfterSet.add(new Integer(43));
        runTestByte(input, expectedOutput, purgeAfterSet);
    }

    @Test
    public void testCompleteLineBlockPlusPartialByByte() throws IOException {
        String input = "this is\na three\nline file\nplus a fragment";
        String expectedOutput = "this is\na three\nline file\n";
        runTestByte(input, expectedOutput, null);
    }

    public void runTestByteArray(List<String> testingInputStrings, String expectedOutput, Set<Integer> doPurgeAfterWrite) throws IOException {
        ByteArrayOutputStream underlyingOut = new ByteArrayOutputStream();
        CompleteLineBufferedOutputStream testOut = new CompleteLineBufferedOutputStream(underlyingOut); 
        int lineNumber = 0;
        for (String testingInput : testingInputStrings) {
            testOut.write(testingInput.getBytes());
            if (doPurgeAfterWrite != null && doPurgeAfterWrite.contains(new Integer(lineNumber))) {
                testOut.purge();
            }
            lineNumber = lineNumber + 1;
        }
        testOut.close();
        String actualOutput = underlyingOut.toString();
        if (!expectedOutput.equals(actualOutput)) {
            Assert.fail("expected output (" + expectedOutput + ") actual output (" + actualOutput + ")");
        }
    }

    public void runTestByte(String testingInputString, String expectedOutput, Set<Integer> doPurgeAfterWrite) throws IOException {
        ByteArrayOutputStream underlyingOut = new ByteArrayOutputStream();
        CompleteLineBufferedOutputStream testOut = new CompleteLineBufferedOutputStream(underlyingOut); 
        int characterNumber = 0;
        for (char testingCharacter : testingInputString.toCharArray()) {
            testOut.write((byte)(testingCharacter & 0x00ff));
            if (doPurgeAfterWrite != null && doPurgeAfterWrite.contains(new Integer(characterNumber))) {
                testOut.purge();
            }
            characterNumber = characterNumber + 1;
        }
        testOut.close();
        String actualOutput = underlyingOut.toString();
        if (!expectedOutput.equals(actualOutput)) {
            Assert.fail("expected output (" + expectedOutput + ") actual output (" + actualOutput + ")");
        }
    }
}
