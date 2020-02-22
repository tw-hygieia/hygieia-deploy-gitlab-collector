package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import java.util.List;

/**
 * Client for fetching information from UDeploy.
 */
public interface DeployClient {

    /**
     * Fetches all {@link DeployApplication}s for a given instance URL.
     *
     * @param instanceUrl instance URL
     * @return list of {@link DeployApplication}s
     */
    List<DeployApplication> getApplications(String instanceUrl);

    /**
     * Fetches all {@link Environment}s for a given {@link DeployApplication}.
     *
     * @param application a {@link DeployApplication}
     * @return list of {@link Environment}s
     */
    List<Environment> getEnvironments(DeployApplication application);

    /**
     * Fetches all {@link EnvironmentStatus}es for a given {@link DeployApplication} and {@link Environment}.
     *
     * @param application a {@link DeployApplication}
     * @param environment an {@link Environment}
     * @return list of {@link EnvironmentStatus}es
     */
    List<DeployEnvResCompData> getEnvironmentResourceStatusData(DeployApplication application, Environment environment);
}
