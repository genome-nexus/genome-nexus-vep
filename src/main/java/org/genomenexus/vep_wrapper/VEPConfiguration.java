package org.genomenexus.vep_wrapper;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import io.micrometer.common.lang.Nullable;

@Configuration
@ConfigurationProperties(prefix = "vep")
public class VEPConfiguration {
    public static final String DATA_SOURCE_MODE_DATABASE = "database";
    public static final String DATA_SOURCE_MODE_CACHE = "cache";

    private int port;
    private String host;
    private String username;
    private String password;
    private int forks;
    private int hgvsMaxThreads;
    private String dataSourceMode = DATA_SOURCE_MODE_DATABASE;

    @Nullable
    private String polyphenSiftFilename;
    @Nullable
    private String alphaMissenseFilename;

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getForks() {
        return forks;
    }

    public void setForks(int forks) {
        this.forks = forks;
    }

    public int getHgvsMaxThreads() {
        return hgvsMaxThreads;
    }

    public void setHgvsMaxThreads(int maxThreads) {
        this.hgvsMaxThreads = maxThreads;
    }

    public String getDataSourceMode() {
        return dataSourceMode;
    }

    public void setDataSourceMode(String dataSourceMode) {
        this.dataSourceMode = normalizeDataSourceMode(dataSourceMode);
    }

    public boolean isCacheMode() {
        return DATA_SOURCE_MODE_CACHE.equals(dataSourceMode);
    }

    public String getPolyphenSiftFilename() {
        return polyphenSiftFilename;
    }

    public void setPolyphenSiftFilename(String polyphenSiftFilename) {
        this.polyphenSiftFilename = polyphenSiftFilename;
    }

    public String getAlphaMissenseFilename() {
        return alphaMissenseFilename;
    }

    public void setAlphaMissenseFilename(String alphaMissenseFilename) {
        this.alphaMissenseFilename = alphaMissenseFilename;
    }

    @PostConstruct
    public void validate() {
        dataSourceMode = normalizeDataSourceMode(dataSourceMode);
    }

    private String normalizeDataSourceMode(String dataSourceMode) {
        String normalizedValue = dataSourceMode == null
            ? DATA_SOURCE_MODE_DATABASE
            : dataSourceMode.trim().toLowerCase();
        if (!DATA_SOURCE_MODE_DATABASE.equals(normalizedValue) && !DATA_SOURCE_MODE_CACHE.equals(normalizedValue)) {
            throw new IllegalArgumentException(
                "Invalid VEP data source mode: '" + dataSourceMode + "'. Supported values: database, cache"
            );
        }
        return normalizedValue;
    }
}
