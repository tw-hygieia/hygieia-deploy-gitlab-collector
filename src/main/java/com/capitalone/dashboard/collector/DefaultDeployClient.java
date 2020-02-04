package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import com.capitalone.dashboard.util.Supplier;
import org.bson.types.ObjectId;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.sun.activation.registries.LogSupport.log;

@Component
public class DefaultDeployClient implements DeployClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDeployClient.class);
    private static final int DEPLOYMENTS_PAGE_SIZE = 100;

    private final DeploySettings gitlabSettings;
    private final RestOperations restOperations;
    private final CommitRepository commitRepository;
    private final PipelineRepository pipelineRepository;
    private final CollectorItemRepository collectorItemRepository;
    private final CollectorRepository collectorRepository;
    private final ComponentRepository componentRepository;
    private final DashboardRepository dashboardRepository;

    private static final String GITLAB_API_SUFFIX = "/api/v4";
    private static final String GITLAB_PROJECT_API_SUFFIX = String.format("%s/%s", GITLAB_API_SUFFIX, "projects");
    private static final String DEPLOYMENTS_URL_WITH_SORT = "/deployments?per_page=" + DEPLOYMENTS_PAGE_SIZE + "&order_by=created_at&sort=desc";

    @Autowired
    public DefaultDeployClient(DeploySettings gitlabSettings,
                               Supplier<RestOperations> restOperationsSupplier,
                               CommitRepository commitRepository,
                               PipelineRepository pipelineRepository,
                               CollectorItemRepository collectorItemRepository,
                               CollectorRepository collectorRepository, ComponentRepository componentRepository, DashboardRepository dashboardRepository) {
        this.gitlabSettings = gitlabSettings;
        this.restOperations = restOperationsSupplier.get();
        this.commitRepository = commitRepository;
        this.pipelineRepository = pipelineRepository;
        this.collectorRepository = collectorRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.componentRepository = componentRepository;
        this.dashboardRepository = dashboardRepository;
    }

    //Fetches the list of Project
    @Override
    public List<DeployApplication> getApplications(String instanceUrl) {
        List<DeployApplication> applications = new ArrayList<>();

        for (String projectId : gitlabSettings.getProjectIds()) {
            JSONObject jsonObject = parseAsJsonObject(makeRestCall(instanceUrl, new String[]{GITLAB_PROJECT_API_SUFFIX,
                    projectId}));
            DeployApplication application = new DeployApplication();
            String appID = str(jsonObject, "id");
//            application.setInstanceUrl(joinURL(instanceUrl, new String[]{GITLAB_PROJECT_API_SUFFIX, appID}));
            application.setInstanceUrl(instanceUrl);
            application.setApplicationName(str(jsonObject, "name"));
            application.setApplicationId(appID);
            applications.add(application);
        }

//        for (Object applicationItem : paresAsArray(makeRestCall(instanceUrl, new String[]{GITLAB_PROJECT_API_SUFFIX}))) {
//            JSONObject jsonObject = (JSONObject) applicationItem;
//            DeployApplication application = new DeployApplication();
//            String appID = str(jsonObject, "id");
////            application.setInstanceUrl(joinURL(instanceUrl, new String[]{GITLAB_PROJECT_API_SUFFIX, appID}));
//            application.setInstanceUrl(instanceUrl);
//                    application.setApplicationName(str(jsonObject, "name"));
//            application.setApplicationId(appID);
//            applications.add(application);
//        }
        return applications;
    }

    //Fetches the list of Environments available to the given project
    @Override
    public List<Environment> getEnvironments(DeployApplication application) {
        List<Environment> environments = new ArrayList<>();
        String url = application.getApplicationId() + "/environments";

        for (Object item : paresAsArray(makeRestCall(
                application.getInstanceUrl(), new String[]{GITLAB_PROJECT_API_SUFFIX, url}))) {
            JSONObject jsonObject = (JSONObject) item;
            environments.add(new Environment(str(jsonObject, "id"), str(
                    jsonObject, "name")));
        }
        return environments;
    }

    //DeployApplication --> Deploy project
    //Environment --> A job that represents a deployment
    //We'll hard code the above items.
    //EnvironmentComponent --> An actual deployment, i.e. an instance of a deployment
    @Override
    public List<EnvironmentComponent> getEnvironmentComponents(
            DeployApplication application, Environment environment
            /* environment represents a job that we're interested in, say "Deploy to Dev"*/) {
        List<EnvironmentComponent> allComponents = new ArrayList<>();
        int nextPage = 1;
        while (true) {
            List<EnvironmentComponent> components =
                    getEnvironmentComponentsWithPagination(application, environment, nextPage);
            if (components.isEmpty()) {
                break;
            }
            allComponents.addAll(components);
            ++nextPage;
        }
        return allComponents;
    }

    @SuppressWarnings("PMD.AvoidCatchingNPE")
    public List<EnvironmentComponent> getEnvironmentComponentsWithPagination(
            DeployApplication application, Environment environment,
            /* environment represents a job that we're interested in, say "Deploy to Dev"*/int pageNum) {
        List<EnvironmentComponent> components = new ArrayList<>();
        String environmentUrl = application.getApplicationId() + "/environments";

        try {
            //We might have to iterate over pipelines
            ResponseEntity<String> deploymentResponse = makeRestCall(
                    application.getInstanceUrl(),
                    new String[]{GITLAB_PROJECT_API_SUFFIX, DEPLOYMENTS_URL_WITH_SORT + String.format("&page=%d", pageNum)});
            log("****** Inside getEnvironmentComponentsWithPagination, pageNum " + pageNum);
            for (Object item : paresAsArray(deploymentResponse)) {
                JSONObject jsonObject = (JSONObject) item;

//                JSONObject versionObject = (JSONObject) jsonObject
//                        .get("version");
                JSONObject deployableObject = (JSONObject) jsonObject
                        .get("deployable");
                //Skip deployments that are simply "created" or "cancelled".
                //Created deployments are never triggered. So there is no point in considering them
                if (!isDeployed(str(deployableObject, "status"))) continue;

                EnvironmentComponent component = new EnvironmentComponent();
                component.setEnvironmentID(environment.getId());
                component.setEnvironmentName(environment.getName());
                component.setEnvironmentUrl(joinURL(application.getInstanceUrl(),
                        new String[]{GITLAB_PROJECT_API_SUFFIX,
                                environmentUrl,
                                environment.getId()}));
                component.setComponentID(str(deployableObject, "id"));
                component.setComponentName(str(deployableObject, "name"));
//                component.setComponentVersion(str(versionObject, "name"));
                component.setDeployed(true);

                long deployTimeToConsider = getTime(deployableObject, "finished_at");
                component.setDeployTime(deployTimeToConsider == 0 ? getTime(deployableObject, "created_at")
                        : deployTimeToConsider);
                component.setAsOfDate(System.currentTimeMillis());

                JSONObject environmentObject = (JSONObject) deployableObject.get("environment");
                processPipelineCommits(application, deployableObject, environmentObject, deployTimeToConsider);

                components.add(component);
            }
        } catch (NullPointerException npe) {
            LOGGER.info("No Environment data found, No components deployed");
        }

        return components;
    }

    /**
     * Finds or creates a pipeline for a dashboard collectoritem
     * @param collectorItem
     * @return
     */
    protected Pipeline getOrCreatePipeline(CollectorItem collectorItem) {
        Pipeline pipeline = pipelineRepository.findByCollectorItemId(collectorItem.getId());
        if(pipeline == null){
            pipeline = new Pipeline();
            pipeline.setCollectorItemId(collectorItem.getId());
        }
        return pipeline;
    }

    private List<Dashboard> findAllDashboardsForCommit(Commit commit) {
        if (commit.getCollectorItemId() == null) return new ArrayList<>();
        CollectorItem commitCollectorItem = collectorItemRepository.findOne(commit.getCollectorItemId()); //Find the SCM collector which collected this commit
        List<com.capitalone.dashboard.model.Component> components = componentRepository
                .findBySCMCollectorItemId(commitCollectorItem.getId()); //Find the component of the SCM collector - will mostly resolve to a Team dashboard component
        List<ObjectId> componentIds = components.stream().map(BaseModel::getId).collect(Collectors.toList());
        return dashboardRepository.findByApplicationComponentIdsIn(componentIds); //Find the dashboard for the above component
    }

    private void processPipelineCommits(DeployApplication application, JSONObject deployableObject, JSONObject environmentObject, long timestamp) {

        application.setEnvironment(str(environmentObject, "name"));
        JSONObject commitObject = (JSONObject) deployableObject.get("commit");
        List<String> commitIds = new ArrayList<>();
        commitIds.add(str(commitObject, "id"));
        //Consider parent commits too
        for (Object o : (JSONArray) commitObject.get("parent_ids")) {
            commitIds.add((String) o);
        }
        List<Commit> commits = commitIds.stream()
                .flatMap(cId -> commitRepository.findByScmRevisionNumber(cId).stream())
                .filter(Objects::nonNull)
                .filter(c -> c.getScmParentRevisionNumbers().size() > 1) //Take only merge commits
                .collect(Collectors.toList());

        if (commits.size() > 0) {
            List<Dashboard> allDashboardsForCommit = findAllDashboardsForCommit(commits.get(0)); //Find the team dashboard which contains the SCM collector for this commit

            List<Collector> collectorList = collectorRepository.findAllByCollectorType(CollectorType.Product); //Get a Product collector
            List<CollectorItem> collectorItemList = collectorItemRepository
                    .findByCollectorIdIn(collectorList.stream().map(BaseModel::getId)
                            .collect(Collectors.toList())); //Find the collector item for the product dashboard

            for (CollectorItem collectorItem : collectorItemList) {
                List<String> dashBoardIds = allDashboardsForCommit.stream().map(d -> d.getId().toString()).collect(Collectors.toList());
                boolean dashboardId = dashBoardIds.contains(collectorItem.getOptions().get("dashboardId").toString());
                if (dashboardId) { //If the product dashboard and team dashboard match
                    Pipeline pipeline = getOrCreatePipeline(collectorItem);
                    Map<String, EnvironmentStage> environmentStageMap = pipeline.getEnvironmentStageMap();
                    if (environmentStageMap.get(application.getEnvironment()) == null) {
                        environmentStageMap.put(application.getEnvironment(), new EnvironmentStage());
                    }

                    EnvironmentStage environmentStage = environmentStageMap.get(application.getEnvironment());
                    if (environmentStage.getCommits() == null) {
                        environmentStage.setCommits(new HashSet<>());
                    }
                    environmentStage.getCommits().addAll(commits.stream().map(commit -> new PipelineCommit(commit, timestamp)).collect(Collectors.toSet()));
                    pipelineRepository.save(pipeline);
                }
            }
        }
    }

    private boolean isDeployed(String deployStatus) {
        return deployStatus != null && !deployStatus.isEmpty() && deployStatus.equalsIgnoreCase("success");
    }

    @Override
    public List<DeployEnvResCompData> getEnvironmentResourceStatusData(
            DeployApplication application, Environment environment) {
        List<DeployEnvResCompData> allComponents = new ArrayList<>();
        int nextPage = 1;
        while (true) {
            List<DeployEnvResCompData> components =
                    getEnvironmentResourceStatusDataWithPagination(application, environment, nextPage);
            if (components.isEmpty()) {
                break;
            }
            allComponents.addAll(components);
            ++nextPage;
        }
        return allComponents;
    }

    // Called by DefaultEnvironmentStatusUpdater
//    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts") // agreed, this method needs refactoring.

    public List<DeployEnvResCompData> getEnvironmentResourceStatusDataWithPagination(
            DeployApplication application, Environment environment, int pageNum) {

        List<DeployEnvResCompData> environmentStatuses = new ArrayList<>();

        String deploymentsUrl = application.getApplicationId() + DEPLOYMENTS_URL_WITH_SORT + String.format("&page=%d", pageNum);
        ResponseEntity<String> inventoryResponse = makeRestCall(application.getInstanceUrl(), new String[]{GITLAB_PROJECT_API_SUFFIX, deploymentsUrl});

        JSONArray allDeploymentJSON = paresAsArray(inventoryResponse);

        if (allDeploymentJSON != null && allDeploymentJSON.size() > 0) {
            // Failed to deploy list:
//			Set<String> failedComponents = getFailedComponents(inventoryJSON);
//	        Map<String, List<String>> versionFileMap = new HashMap<>();
            for (Object deployment : allDeploymentJSON) {
                JSONObject jsonObject = (JSONObject) deployment;
                if (jsonObject == null) continue;

                JSONObject environmentObj = (JSONObject) jsonObject.get("environment");
                JSONObject deployableObj = (JSONObject) jsonObject.get("deployable");
                JSONObject runnerObj = (JSONObject) deployableObj.get("runner");

                if (environmentObj == null || deployableObj == null) continue;

                String environmentID = str(environmentObj, "id");

                if (environmentID == null || (!environmentID.equals(environment.getId()))) continue;
                //Skip deployments that are simply "created" or "cancelled".
                //Created deployments are never triggered. So there is no point in considering them
                if (!isDeployed(str(deployableObj, "status"))) continue;

                DeployEnvResCompData deployData = new DeployEnvResCompData();

                deployData.setCollectorItemId(application.getId());
                deployData.setEnvironmentName(environment.getName());

                deployData.setComponentID(str(deployableObj, "id"));
                deployData.setComponentName(application.getApplicationName());
                deployData.setDeployed(true);
                deployData.setAsOfDate(System.currentTimeMillis());

                if (runnerObj == null) {
                    deployData.setOnline(true);
                    deployData.setResourceName("gitlab-runner");
                } else {
                    deployData.setOnline(bool(runnerObj, "online"));
                    deployData.setResourceName(str(runnerObj, "name"));
                }

                JSONObject environmentObject = (JSONObject) jsonObject.get("environment");

                long deployTimeToConsider = getTime(deployableObj, "finished_at");
                processPipelineCommits(application, deployableObj, environmentObject,
                        deployTimeToConsider == 0 ? getTime(deployableObj, "created_at") : deployTimeToConsider);
                environmentStatuses.add(deployData);
//				JSONArray childArray = getResourceComponent(application, jsonObject, new JSONArray());
//	            if (childArray.isEmpty()) continue;
//	            for (Object child : childArray) {
//	                JSONObject childObject = (JSONObject) child;
//	                JSONArray jsonVersions = (JSONArray) childObject.get("versions");
//	                if (jsonVersions == null || jsonVersions.size() == 0) continue;
//	                JSONObject versionObject = (JSONObject) jsonVersions.get(0);
//	                // get version fileTree and build data.
//	                List<String> physicalFileNames = versionFileMap.get(str(versionObject, "id"));
//	                if (CollectionUtils.isEmpty(physicalFileNames)) {
//	                    physicalFileNames = getPhysicalFileList(application, versionObject);
//	                    versionFileMap.put(str(versionObject, "id"), physicalFileNames);
//	                }
//	                for (String fileName : physicalFileNames) {
//	                    environmentStatuses.add(buildDeployEnvResCompData(environment, application, versionObject, fileName, childObject, failedComponents));
//	                }
//	            }
            }
        }
        return environmentStatuses;
    }

    @SuppressWarnings("unchecked")
    private JSONArray getResourceComponent(DeployApplication application, JSONObject topParent,
                                           JSONArray returnArray) {

        JSONObject resourceRole = (JSONObject) topParent.get("role");

        String resourceSpecialType = null;

        if (resourceRole != null) {
            resourceSpecialType = str(resourceRole, "specialType");
        }

        if (resourceSpecialType != null && resourceSpecialType.equalsIgnoreCase("COMPONENT")) {
            JSONArray jsonVersions = (JSONArray) topParent.get("versions");

            if (jsonVersions != null && jsonVersions.size() > 0) {
                returnArray.add(topParent);
            }
        } else {
            String hasChildren = str(topParent, "hasChildren");

            if ("true".equalsIgnoreCase(hasChildren)) {
                String resourceId = str(topParent, "id");

                String urlResources = "resource/resource/" + resourceId + "/resources";

                ResponseEntity<String> resourceResponse = makeRestCall(application.getInstanceUrl(), new String[]{urlResources});

                JSONArray resourceListJSON = paresAsArray(resourceResponse);

                for (Object resourceObject : resourceListJSON) {
                    JSONObject childJSON = (JSONObject) resourceObject;

                    getResourceComponent(application, childJSON, returnArray);
                }
            }
        }

        return returnArray;
    }

    private List<String> getPhysicalFileList(DeployApplication application, JSONObject versionObject) {
        List<String> list = new ArrayList<>();
        String fileTreeUrl = "deploy/version/" + str(versionObject, "id") + "/fileTree";
        ResponseEntity<String> fileTreeResponse = makeRestCall(
                application.getInstanceUrl(), new String[]{fileTreeUrl});
        JSONArray fileTreeJson = paresAsArray(fileTreeResponse);
        for (Object f : fileTreeJson) {
            JSONObject fileJson = (JSONObject) f;
            list.add(cleanFileName(str(fileJson, "name"), str(versionObject, "name")));
        }
        return list;
    }

    private Set<String> getFailedComponents(JSONArray environmentInventoryJSON) {
        HashSet<String> failedComponents = new HashSet<>();

        for (Object inventory : environmentInventoryJSON) {
            JSONObject inventoryObject = (JSONObject) inventory;
            JSONObject compliancyObject = (JSONObject) inventoryObject.get("compliancy");

            if (compliancyObject == null)
                continue;

            long correctCount = date(compliancyObject, "correctCount");
            long desiredCount = date(compliancyObject, "desiredCount");

            if (correctCount < desiredCount) {
                JSONObject componentObject = (JSONObject) inventoryObject.get("component");
                if (componentObject != null) {
                    failedComponents.add(str(componentObject, "name"));
                }
            }
        }

        return failedComponents;
    }

    private String cleanFileName(String fileName, String version) {
        if (fileName.contains("-" + version))
            return fileName.replace("-" + version, "");
        if (fileName.contains(version))
            return fileName.replace(version, "");
        return fileName;
    }

    private DeployEnvResCompData buildDeployEnvResCompData(Environment environment, DeployApplication application, JSONObject versionObject, String fileName, JSONObject childObject, Set<String> failedComponents) {
        DeployEnvResCompData data = new DeployEnvResCompData();
        data.setEnvironmentName(environment.getName());
        data.setCollectorItemId(application.getId());
        data.setComponentVersion(str(versionObject, "name"));
        data.setAsOfDate(date(versionObject, "created"));

        JSONObject childRole = (JSONObject) childObject.get("role");
        String childRoleName = str(childRole, "name");

        data.setDeployed(!failedComponents.contains(childRoleName));
        data.setComponentName(fileName);
        data.setOnline("ONLINE".equalsIgnoreCase(str(
                childObject, "status")));
        JSONObject resource = getParentAgent(childObject);
        if (resource != null) {
            data.setResourceName(str(resource, "name"));
        }
        return data;
    }

    public JSONObject getParentAgent(JSONObject childObject) {
        JSONObject parentAgent = null;
        String resourceType = null;
        String hasAgent = null;

        JSONObject parentObject = (JSONObject) childObject.get("parent");

        if (parentObject != null) {
            resourceType = str(parentObject, "type");
            hasAgent = str(parentObject, "hasAgent");

            if (resourceType != null && resourceType.equalsIgnoreCase("agent")) {
                parentAgent = parentObject;
            } else {
                if ("true".equalsIgnoreCase(hasAgent)) {
                    parentAgent = getParentAgent(parentObject);
                }
            }
        }

        return parentAgent;
    }
    // ////// Helpers

    private ResponseEntity<String> makeRestCall(String instanceUrl,
                                                String[] endpoint) {

        String url = joinURL(instanceUrl, endpoint);

        UriComponentsBuilder thisuri =
                UriComponentsBuilder.fromHttpUrl(url);


        String token = this.gitlabSettings.getApiKeys().get(0);

        ResponseEntity<String> response = null;
        try {
            log("Calling -> " + thisuri.toUriString());
            response = restOperations.exchange(thisuri.toUriString(), HttpMethod.GET,
                    new HttpEntity<>(createHeaders(token)), String.class);

        } catch (RestClientException re) {
            LOGGER.error("Error with REST url: " + url);
            LOGGER.error(re.getMessage());
        }
        return response;
    }

    // If we are putting token in the application.properties file
    // Then it overrides all usernames and passwords given in the UI

    protected HttpHeaders createHeaders(final String apiToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("PRIVATE-TOKEN", apiToken);
        return headers;
    }

    private JSONObject parseAsJsonObject(ResponseEntity<String> response) {
        if (response == null)
            return new JSONObject();
        try {
            return (JSONObject) new JSONParser().parse(response.getBody());
        } catch (ParseException pe) {
            LOGGER.debug(response.getBody());
            LOGGER.error(pe.getMessage());
        }
        return new JSONObject();
    }

    private JSONArray paresAsArray(ResponseEntity<String> response) {
        if (response == null)
            return new JSONArray();
        try {
            return (JSONArray) new JSONParser().parse(response.getBody());
        } catch (ParseException pe) {
            LOGGER.debug(response.getBody());
            LOGGER.error(pe.getMessage());
        }
        return new JSONArray();
    }

    private boolean bool(JSONObject json, String key) {
        Object value = json.get(key);
        return value != null && (boolean) value;
    }

    private String str(JSONObject json, String key) {
        Object value = json.get(key);
        return value == null ? null : value.toString();
    }

    private long date(JSONObject jsonObject, String key) {
        Object value = jsonObject.get(key);
        return value == null ? 0 : (long) value;
    }

    private long getTime(String dateToConsider) {

        if (dateToConsider != null) {
            return Instant.from(DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz")
                    .parse(dateToConsider)).toEpochMilli();
        } else {
            return 0L;
        }
    }
    private long getTime(JSONObject buildJson, String jsonField) {

        String dateToConsider = getString(buildJson, jsonField);
        if (dateToConsider != null) {
            return Instant.from(DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz")
                    .parse(getString(buildJson, jsonField))).toEpochMilli();
        } else{
            return 0L;
        }
    }
    private String getString(JSONObject json, String key) {
        return (String) json.get(key);
    }

    private String joinURL(String base, String[] paths) {
        StringBuilder result = new StringBuilder(base);
        Arrays.stream(paths).map(path -> path.replaceFirst("^(\\/)+", "")).forEach(p -> {
            if (result.lastIndexOf("/") != result.length() - 1) {
                result.append('/');
            }
            result.append(p);
        });
        return result.toString();
    }
}
