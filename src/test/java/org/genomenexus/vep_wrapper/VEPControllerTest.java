package org.genomenexus.vep_wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class VEPControllerTest {

    @Test
    void testSimpleSNV() {
        String input = "1:g.123A>T";
        String expected = "1:123-123:1/T";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testDeletionRange() {
        String input = "1:g.100_102del";
        String expected = "1:100-102:1/-";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testInsertionWithAlt() {
        String input = "1:g.200_201insA";
        String expected = "1:200-201:1/A";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testChromosomeWithPrefix() {
        String input = "chr1:g.500C>G";
        String expected = "chr1:500-500:1/G";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testDelins() {
        String input = "1:g.123_124delinsAT";
        String expected = "1:123-124:1/AT";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testWithNucleotideN() {
        String input = "1:g.300_301delinsN";
        String expected = "1:300-301:1/N";
        String actual = VEPController.hgvsgToRegion(input);
        assertEquals(expected, actual);
    }

    @Test
    void testInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> VEPController.hgvsgToRegion("not-a-valid-hgvs"));
    }
}
