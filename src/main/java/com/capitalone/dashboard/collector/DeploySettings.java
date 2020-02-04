package com.capitalone.dashboard.collector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bean to hold settings specific to the Gitlab collector.
 */
@Component
@ConfigurationProperties(prefix = "gitlab")
public class DeploySettings {


    private String cron;
    private List<String> servers = new ArrayList<>();
    //will be injected from the properties file
    private String projectIds = "";
    private List<String> niceNames;
    //eg. DEV, QA, PROD etc
    private List<String> environments = new ArrayList<>();
    private List<String> usernames = new ArrayList<>();
    private List<String> apiKeys = new ArrayList<>();
    private String dockerLocalHostIP; //null if not running in docker on http://localhost
    private int pageSize;
    @Value("${folderDepth:10}")
    private int folderDepth;

    @Value("${gitlab.connectTimeout:20000}")
    private int connectTimeout;

    @Value("${gitlab.readTimeout:20000}")
    private int readTimeout;

    public String getCron() {
        return cron;
    }

    public List<String> getServers() {
        return servers;
    }

    public void setServers(List<String> servers) {
        this.servers = servers;
    }

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public List<String> getNiceNames() {
        return niceNames;
    }

    public List<String> getEnvironments() {
        return environments;
    }

    //Docker NATs the real host localhost to 10.0.2.2 when running in docker
    //as localhost is stored in the JSON payload from gitlab we need
    //this hack to fix the addresses
    public String getDockerLocalHostIP() {

        //we have to do this as spring will return NULL if the value is not set vs and empty string
        String localHostOverride = "";
        if (dockerLocalHostIP != null) {
            localHostOverride = dockerLocalHostIP;
        }
        return localHostOverride;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getFolderDepth() {
        return folderDepth;
    }

    public int getConnectTimeout() { return connectTimeout; }

    public int getReadTimeout() { return readTimeout; }

    public List<String> getProjectIds() {
        return Arrays.asList(projectIds.split(","));
    }

    public void setProjectIds(String projectIds) {
        this.projectIds = projectIds;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }
}
