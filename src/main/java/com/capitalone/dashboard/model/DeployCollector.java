package com.capitalone.dashboard.model;

import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * Collector implementation for UDeploy that stores UDeploy server URLs.
 */
public class DeployCollector extends Collector {
    private List<String> udeployServers = new ArrayList<>();
    private List<String> niceNames = new ArrayList<>();
    private static final String NICE_NAME = "niceName";
    private static final String JOB_NAME = "options.jobName";

    public List<String> getDeployServers() {
        return udeployServers;
    }
    
    public List<String> getNiceNames() {
    	return niceNames;
    }

    public static DeployCollector prototype(List<String> servers, List<String> niceNames) {
        DeployCollector protoType = new DeployCollector();
        protoType.setName("Gitlab-Deploy");
        protoType.setCollectorType(CollectorType.Deployment);
        protoType.setOnline(true);
        protoType.setEnabled(true);
        protoType.getDeployServers().addAll(servers);
        if (!CollectionUtils.isEmpty(niceNames)) {
            protoType.getNiceNames().addAll(niceNames);
        }

        if (!CollectionUtils.isEmpty(niceNames)) {
            protoType.getNiceNames().addAll(niceNames);
        }
        
        Map<String, Object> allOptions = new HashMap<>();
        allOptions.put(DeployApplication.INSTANCE_URL,"");
        allOptions.put(DeployApplication.APP_NAME,"");
        allOptions.put(DeployApplication.APP_ID, "");
        protoType.setAllFields(allOptions);

        Map<String, Object> uniqueOptions = new HashMap<>();
        uniqueOptions.put(DeployApplication.INSTANCE_URL,"");
        uniqueOptions.put(DeployApplication.APP_NAME,"");
        protoType.setUniqueFields(uniqueOptions);
//        protoType.setSearchFields(Arrays.asList(JOB_NAME,NICE_NAME));
        return protoType;
    }
}
