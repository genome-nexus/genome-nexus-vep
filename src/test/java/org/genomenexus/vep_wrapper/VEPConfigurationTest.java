package org.genomenexus.vep_wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class VEPConfigurationTest {

    @Test
    void testDataSourceModeDefaultsToDatabase() {
        VEPConfiguration configuration = new VEPConfiguration();

        configuration.validate();

        assertEquals(VEPConfiguration.DATA_SOURCE_MODE_DATABASE, configuration.getDataSourceMode());
    }

    @Test
    void testDataSourceModeNormalizationSupportsCache() {
        VEPConfiguration configuration = new VEPConfiguration();

        configuration.setDataSourceMode("  CACHE ");

        assertEquals(VEPConfiguration.DATA_SOURCE_MODE_CACHE, configuration.getDataSourceMode());
        assertTrue(configuration.isCacheMode());
    }

    @Test
    void testInvalidDataSourceModeIsRejected() {
        VEPConfiguration configuration = new VEPConfiguration();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> configuration.setDataSourceMode("offline")
        );

        assertEquals(
            "Invalid VEP data source mode: 'offline'. Supported values: database, cache",
            exception.getMessage()
        );
    }
}
