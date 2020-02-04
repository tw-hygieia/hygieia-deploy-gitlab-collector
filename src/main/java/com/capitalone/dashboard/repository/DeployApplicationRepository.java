package com.capitalone.dashboard.repository;

import com.capitalone.dashboard.model.DeployApplication;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * Repository for {@link DeployApplication}s.
 */
public interface DeployApplicationRepository extends BaseCollectorItemRepository<DeployApplication> {

    /**
     * Find a {@link DeployApplication} by UDeploy instance URL and UDeploy application id.
     *
     * @param collectorId ID of the {@link com.capitalone.dashboard.model.DeployCollector}
     * @param instanceUrl UDeploy instance URL
     * @param applicationId UDeploy application ID
     * @return a {@link DeployApplication} instance
     */
    @Query(value="{ 'collectorId' : ?0, options.instanceUrl : ?1, options.applicationId : ?2}")
    DeployApplication findDeployApplication(ObjectId collectorId, String instanceUrl, String applicationId);

    /**
     * Finds all {@link DeployApplication}s for the given instance URL.
     *
     * @param collectorId ID of the {@link com.capitalone.dashboard.model.DeployCollector}
     * @param instanceUrl UDeploy instance URl
     * @return list of {@link DeployApplication}s
     */
    @Query(value="{ 'collectorId' : ?0, options.instanceUrl : ?1, enabled: true}")
    List<DeployApplication> findEnabledApplications(ObjectId collectorId, String instanceUrl);
}
