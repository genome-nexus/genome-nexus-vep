package org.genomenexus.vep_wrapper;

import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "vep")
@Validated
public class VEPConfiguration {
    
    public final Mode mode;
	public final int forks;
    public final int hgvsMaxThreads;
    public final Optional<String> polyphenSiftFilename;
    public final Optional<String> alphaMissenseFilename;
    public final DataConfiguration dataConfiguration;

    public VEPConfiguration(
        Mode mode,
        DatabaseConfiguration database,
        CacheConfiguration cache,
        int forks,
        int hgvsMaxThreads,
        Optional<String> polyphenSiftFilename,
        Optional<String> alphaMissenseFilename
    ) {
        this.mode = mode;
        this.dataConfiguration = switch (ensurePresent(mode, "vep.mode")) {
            case Database -> new DatabaseConfiguration(
                ensurePresent(database.port, "vep.database.port"), 
                ensurePresent(database.host, "vep.database.host"), 
                ensurePresent(database.username, "vep.database.username"), 
                ensurePresent(database.password, "vep.database.password")
            );
            case Cache -> new CacheConfiguration(ensurePresent(cache.fastaFilename, "vep.cache.fasta-filename"));
        };
        this.forks = ensurePresent(forks, "vep.forks");
        this.hgvsMaxThreads = ensurePresent(hgvsMaxThreads, "vep.hgvs-max-threads");
        this.polyphenSiftFilename = polyphenSiftFilename.filter(val -> !val.isBlank());
        this.alphaMissenseFilename = alphaMissenseFilename.filter(val -> !val.isBlank());
    }

    enum Mode {
        Database,
        Cache
    }

    sealed interface DataConfiguration permits DatabaseConfiguration, CacheConfiguration {}

    record DatabaseConfiguration(
        int port,
        String host, 
        String username, 
        String password
    ) implements DataConfiguration {}

    record CacheConfiguration(String fastaFilename) implements DataConfiguration {}

    private static <T> T ensurePresent(T value, String path) {
        if (value == null || value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration: " + path);
        } 
        return value;
    }
}
