package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.model.DeployApplication;
import com.capitalone.dashboard.model.DeployEnvResCompData;
import com.capitalone.dashboard.repository.*;
import com.capitalone.dashboard.repository.DeployApplicationRepository;
import com.capitalone.dashboard.repository.DeployCollectorRepository;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * Collects {@link EnvironmentComponent} and {@link EnvironmentStatus} data from
 * {@link com.capitalone.dashboard.model.DeployApplication}s.
 */
@Component
public class DeployCollectorTask extends CollectorTask<DeployCollector> {
    @SuppressWarnings({"unused", "PMD.UnusedPrivateField"})
    private static final Logger LOGGER = LoggerFactory.getLogger(DeployCollectorTask.class);

    private final DeployCollectorRepository deployCollectorRepository;
    private final DeployApplicationRepository deployApplicationRepository;
    private final DeployClient deployClient;
    private final DeploySettings deploySettings;
    private final EnvironmentComponentRepository envComponentRepository;
    private final EnvironmentStatusRepository environmentStatusRepository;
	private final ConfigurationRepository configurationRepository;
    private final ComponentRepository dbComponentRepository;

    @Autowired
    public DeployCollectorTask(TaskScheduler taskScheduler,
                                DeployCollectorRepository DeployCollectorRepository,
                                DeployApplicationRepository uDeployApplicationRepository,
                                EnvironmentComponentRepository envComponentRepository,
                                EnvironmentStatusRepository environmentStatusRepository,
                                DeploySettings deploySettings, DeployClient uDeployClient,
                                ConfigurationRepository configurationRepository,
                                ComponentRepository dbComponentRepository) {
        super(taskScheduler, "Gitlab-Deploy");
        this.deployCollectorRepository = DeployCollectorRepository;
        this.deployApplicationRepository = uDeployApplicationRepository;
        this.deploySettings = deploySettings;
        this.deployClient = uDeployClient;
        this.envComponentRepository = envComponentRepository;
        this.environmentStatusRepository = environmentStatusRepository;
        this.dbComponentRepository = dbComponentRepository;
		this.configurationRepository = configurationRepository;
    }

    @Override
    public DeployCollector getCollector() {
		Configuration config = configurationRepository.findByCollectorName("Gitlab-Deploy");
        if (config != null) {
            config.decryptOrEncrptInfo();
            // TO clear the username and password from existing run and
            // pick the latest
            deploySettings.getUsernames().clear();
            deploySettings.getServers().clear();
//            deploySettings.getPasswords().clear();
            for (Map<String, String> udeployServer : config.getInfo()) {
                deploySettings.getServers().add(udeployServer.get("url"));
                deploySettings.getUsernames().add(udeployServer.get("userName"));
//                deploySettings.getPasswords().add(udeployServer.get("password"));
            }
        }
        return DeployCollector.prototype(deploySettings.getServers(), deploySettings.getNiceNames());
    }

    @Override
    public BaseCollectorRepository<DeployCollector> getCollectorRepository() {
        return deployCollectorRepository;
    }

    @Override
    public String getCron() {
        return deploySettings.getCron();
    }

    @Override
    public void collect(DeployCollector collector) {
        for (String instanceUrl : collector.getDeployServers()) {

            logBanner(instanceUrl);

            long start = System.currentTimeMillis();

            clean(collector);

            addNewApplications(deployClient.getApplications(instanceUrl),
                    collector);
            updateData(enabledApplications(collector, instanceUrl));

            log("Finished", start);
        }
    }

    /**
     * Clean up unused deployment collector items
     *
     * @param collector the {@link DeployCollector}
     */
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    private void clean(DeployCollector collector) {
        deleteUnwantedJobs(collector);
        Set<ObjectId> uniqueIDs = new HashSet<>();
        for (com.capitalone.dashboard.model.Component comp : dbComponentRepository
                .findAll()) {
            if (comp.getCollectorItems() == null || comp.getCollectorItems().isEmpty()) continue;
            List<CollectorItem> itemList = comp.getCollectorItems().get(
                    CollectorType.Deployment);
            if (itemList == null) continue;
            for (CollectorItem ci : itemList) {
                if (ci == null) continue;
                uniqueIDs.add(ci.getId());
            }
        }
        List<DeployApplication> appList = new ArrayList<>();
        Set<ObjectId> udId = new HashSet< >();
        udId.add(collector.getId());
        for (DeployApplication app : deployApplicationRepository.findByCollectorIdIn(udId)) {
            if (app != null) {
                app.setEnabled(uniqueIDs.contains(app.getId()));
                appList.add(app);
            }
        }
        deployApplicationRepository.save(appList);
    }

    private void deleteUnwantedJobs(DeployCollector collector) {

        List<DeployApplication> deleteAppList = new ArrayList<>();
        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        for (DeployApplication app : deployApplicationRepository.findByCollectorIdIn(udId)) {
            if (!collector.getDeployServers().contains(app.getInstanceUrl()) ||
                    (!app.getCollectorId().equals(collector.getId()))) {
                deleteAppList.add(app);
            }
        }

        deployApplicationRepository.delete(deleteAppList);

    }
    
    private List<EnvironmentComponent> getEnvironmentComponent(List<DeployEnvResCompData> dataList, Environment environment, DeployApplication application) {
        List<EnvironmentComponent> returnList = new ArrayList<>();
        for (DeployEnvResCompData data : dataList) {
            EnvironmentComponent component = new EnvironmentComponent();
            component.setComponentName(data.getComponentName());
            component.setCollectorItemId(data.getCollectorItemId());
            component.setComponentVersion(data
                    .getComponentVersion());
            component.setDeployed(data.isDeployed());
            component.setEnvironmentName(data
                    .getEnvironmentName());

            component.setEnvironmentName(environment.getName());
            component.setAsOfDate(data.getAsOfDate());
            component.setDeployTime(data.getDeployTime());
            String environmentURL = StringUtils.removeEnd(
                    application.getInstanceUrl(), "/")
                    + "/api/v4/projects/"+application.getApplicationId()+"/environments/" + environment.getId();
            component.setEnvironmentUrl(environmentURL);

            returnList.add(component);
        }
        return returnList;
    }

    private List<EnvironmentStatus> getEnvironmentStatus(List<DeployEnvResCompData> dataList) {
        List<EnvironmentStatus> returnList = new ArrayList<>();
        for (DeployEnvResCompData data : dataList) {
            EnvironmentStatus status = new EnvironmentStatus();
            status.setCollectorItemId(data.getCollectorItemId());
            status.setComponentID(data.getComponentID());
            status.setComponentName(data.getComponentName());
            status.setEnvironmentName(data.getEnvironmentName());
            status.setOnline(data.isOnline());
            status.setResourceName(data.getResourceName());

            returnList.add(status);
        }
        return returnList;
    }

    /**
     * For each {@link DeployApplication}, update the current
     * {@link EnvironmentComponent}s and {@link EnvironmentStatus}.
     *
     * @param uDeployApplications list of {@link DeployApplication}s
     */
    private void updateData(List<DeployApplication> uDeployApplications) {
        for (DeployApplication application : uDeployApplications) {
            List<EnvironmentComponent> compList = new ArrayList<>();
            List<EnvironmentStatus> statusList = new ArrayList<>();
            long startApp = System.currentTimeMillis();

            log("Deploy Applications size----" + uDeployApplications.size());

            for (Environment environment : deployClient
                    .getEnvironments(application)) {
                log("Environment Instance URL ---- " + environment.getName());
                log("Environment name----" + environment.getName());

                List<DeployEnvResCompData> combinedDataList = deployClient
                        .getEnvironmentResourceStatusData(application,
                                environment);

                log("Combined List----" + combinedDataList.size());

                compList.addAll(getEnvironmentComponent(combinedDataList, environment, application));
                statusList.addAll(getEnvironmentStatus(combinedDataList));
            }

            log("Environment Component List----" + compList.size());
            log("Environment Status List----" + compList.size());

            if (!compList.isEmpty()) {
                List<EnvironmentComponent> existingComponents = envComponentRepository
                        .findByCollectorItemId(application.getId());
                envComponentRepository.delete(existingComponents);
                envComponentRepository.save(compList);
            }
            if (!statusList.isEmpty()) {
                List<EnvironmentStatus> existingStatuses = environmentStatusRepository
                        .findByCollectorItemId(application.getId());
                environmentStatusRepository.delete(existingStatuses);
                environmentStatusRepository.save(statusList);
            }

            log(" " + application.getApplicationName(), startApp);
        }
    }

    private List<DeployApplication> enabledApplications(
            DeployCollector collector, String instanceUrl) {
        return deployApplicationRepository.findEnabledApplications(
                collector.getId(), instanceUrl);
    }

    /**
     * Add any new {@link DeployApplication}s.
     *
     * @param applications list of {@link DeployApplication}s
     * @param collector    the {@link DeployCollector}
     */
    private void addNewApplications(List<DeployApplication> applications,
                                    DeployCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;

        log("All apps", start, applications.size());
        for (DeployApplication application : applications) {
        	DeployApplication existing = findExistingApplication(collector, application);
            log("Iterating apps----" + application.getApplicationName());
        	String niceName = getNiceName(application, collector);
            if (existing == null) {
                application.setCollectorId(collector.getId());
                application.setEnabled(false);
                application.setDescription(application.getApplicationName());
                if (StringUtils.isNotEmpty(niceName)) {
                	application.setNiceName(niceName);
                }
                try {
                    deployApplicationRepository.save(application);
                } catch (org.springframework.dao.DuplicateKeyException ce) {
                    log("Duplicates items not allowed", 0);

                }
                count++;
            } else if (StringUtils.isEmpty(existing.getNiceName()) && StringUtils.isNotEmpty(niceName)) {
				existing.setNiceName(niceName);
				deployApplicationRepository.save(existing);
            }

        }
        log("New apps", start, count);
    }

    private DeployApplication findExistingApplication(DeployCollector collector,
                                     DeployApplication application) {
        return deployApplicationRepository.findDeployApplication(
                collector.getId(), application.getInstanceUrl(),
                application.getApplicationId());
    }
    
    private String getNiceName(DeployApplication application, DeployCollector collector) {
        if (CollectionUtils.isEmpty(collector.getDeployServers())) return "";
        List<String> servers = collector.getDeployServers();
        List<String> niceNames = collector.getNiceNames();
        if (CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(application.getInstanceUrl()) && niceNames.size() > i) {
                return niceNames.get(i);
            }
        }
        return "";
    }

    @SuppressWarnings("unused")
	private boolean changed(EnvironmentStatus status, EnvironmentStatus existing) {
        return existing.isOnline() != status.isOnline();
    }

    @SuppressWarnings("unused")
	private EnvironmentStatus findExistingStatus(
            final EnvironmentStatus proposed,
            List<EnvironmentStatus> existingStatuses) {

        return Iterables.tryFind(existingStatuses,
                new Predicate<EnvironmentStatus>() {
                    @Override
                    public boolean apply(EnvironmentStatus existing) {
                        return existing.getEnvironmentName().equals(
                                proposed.getEnvironmentName())
                                && existing.getComponentName().equals(
                                proposed.getComponentName())
                                && existing.getResourceName().equals(
                                proposed.getResourceName());
                    }
                }).orNull();
    }

    @SuppressWarnings("unused")
	private boolean changed(EnvironmentComponent component,
                            EnvironmentComponent existing) {
        return existing.isDeployed() != component.isDeployed()
                || existing.getAsOfDate() != component.getAsOfDate() || !existing.getComponentVersion().equalsIgnoreCase(component.getComponentVersion());
    }

    @SuppressWarnings("unused")
	private EnvironmentComponent findExistingComponent(
            final EnvironmentComponent proposed,
            List<EnvironmentComponent> existingComponents) {

        return Iterables.tryFind(existingComponents,
                new Predicate<EnvironmentComponent>() {
                    @Override
                    public boolean apply(EnvironmentComponent existing) {
                        return existing.getEnvironmentName().equals(
                                proposed.getEnvironmentName())
                                && existing.getComponentName().equals(
                                proposed.getComponentName());

                    }
                }).orNull();
    }
}
