package org.genomenexus.vep_wrapper;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import io.micrometer.common.lang.Nullable;

@Configuration 
@ConfigurationProperties(prefix = "vep")
public class VEPConfiguration {
    private int port;
    private String host;
    private String username;
    private String password;
    private int forks;
    private int hgvsMaxThreads;

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
}
