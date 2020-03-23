package com.capitalone.dashboard.collector;

import com.capitalone.dashboard.model.*;
import com.capitalone.dashboard.repository.*;
import com.capitalone.dashboard.util.Supplier;
import org.apache.commons.collections4.CollectionUtils;
import org.bson.types.ObjectId;
import org.joda.time.DateTime;
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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.sun.activation.registries.LogSupport.log;

@Component
public class DefaultDeployClient implements DeployClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDeployClient.class);
    private static final int DEPLOYMENTS_PAGE_SIZE = 100;
    private static final int COMMITS_PAGE_SIZE = 100;

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
    private static final String COMMITS_URL_WITH_SORT = "/repository/commits?per_page=" + COMMITS_PAGE_SIZE + "&ref_name=";

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
            final String apiKey = gitlabSettings.getProjectKey(projectId);
            JSONObject jsonObject = parseAsJsonObject(makeRestCall(instanceUrl, new String[]{GITLAB_PROJECT_API_SUFFIX,
                    projectId}, apiKey));
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
        final String apiKey = gitlabSettings.getProjectKey(application.getApplicationId());

        for (Object item : paresAsArray(makeRestCall(
                application.getInstanceUrl(), new String[]{GITLAB_PROJECT_API_SUFFIX, url}, apiKey))) {
            JSONObject jsonObject = (JSONObject) item;
            environments.add(new Environment(str(jsonObject, "id"), str(
                    jsonObject, "name")));
        }
        return environments;
    }

    /**
     * Finds or creates a pipeline for a dashboard collectoritem
     *
     * @param collectorItem
     * @return
     */
    protected Pipeline getOrCreatePipeline(CollectorItem collectorItem) {
        Pipeline pipeline = pipelineRepository.findByCollectorItemId(collectorItem.getId());
        if (pipeline == null) {
            pipeline = new Pipeline();
            pipeline.setCollectorItemId(collectorItem.getId());
        }
        return pipeline;
    }

    private List<String> findAllDashboardIds(DeployApplication application) {
        List<CollectorItem> collectorItems = collectorItemRepository
                .findByCollectorIdIn(Collections.singletonList(application.getCollectorId()));
        if (collectorItems == null || collectorItems.size() == 0) {
            return Collections.emptyList();
        }
        Optional<CollectorItem> collectorItemOptional =
                collectorItems.stream().filter(item ->
                        application.getApplicationId().equals(item.getOptions().get("applicationId"))).findFirst();
        if (!collectorItemOptional.isPresent()) {
            return Collections.emptyList();
        }
        CollectorItem collectorItem = collectorItemOptional.get();//Find the SCM collector which collected this commit
        List<com.capitalone.dashboard.model.Component> components = componentRepository
                .findByDeployCollectorItemId(collectorItem.getId()); //Find the component of the SCM collector - will mostly resolve to a Team dashboard component
        List<ObjectId> componentIds = components.stream().map(BaseModel::getId).collect(Collectors.toList());
        List<Dashboard> allDashboardsForCommit = dashboardRepository.findByApplicationComponentIdsIn(componentIds);
        List<String> dashBoardIds = allDashboardsForCommit.stream().map(d -> d.getId().toString()).collect(Collectors.toList());
        return dashBoardIds;
    }

    private List<PipelineCommit> getPipelineCommits(DeployApplication application, JSONObject deployableObject, JSONObject environmentObject, long timestamp) {

        application.setEnvironment(str(environmentObject, "name"));
        JSONObject commitObject = (JSONObject) deployableObject.get("commit");
        List<String> commitIds = new ArrayList<>();
        commitIds.add(str(commitObject, "id"));
        //Consider parent commits too
        for (Object o : (JSONArray) commitObject.get("parent_ids")) {
            commitIds.add((String) o);
        }
        if (commitIds.size() == 0) {
            return Collections.emptyList();
        }
        List<PipelineCommit> commits = new ArrayList<>();
        commitIds.forEach(commitId -> {
            List<Commit> matchedCommits = commitRepository.findByScmRevisionNumber(commitId);
            Commit newCommit;
            if (matchedCommits != null && matchedCommits.size() > 0) {
                newCommit = matchedCommits.get(0);
            } else {
                newCommit = getCommit(commitId, application.getInstanceUrl(), application.getApplicationId());
            }
            List<String> parentRevisionNumbers = newCommit != null ? newCommit.getScmParentRevisionNumbers() : null;
            /* Extract only merge commits */
            if (parentRevisionNumbers != null && !parentRevisionNumbers.isEmpty() && parentRevisionNumbers.size() > 1) {
                commits.add(new PipelineCommit(newCommit, timestamp));
            }
        });
        return commits;
    }

    private void saveToPipelines(DeployApplication application, List<PipelineCommit> commits) {
        if (commits.size() == 0) {
            return;
        }
        List<String> dashBoardIds = findAllDashboardIds(application);

        List<CollectorItem> collectorItemList = getCollectorItems();

        for (CollectorItem collectorItem : collectorItemList) {
            boolean dashboardId = dashBoardIds.contains(collectorItem.getOptions().get("dashboardId").toString());
            if (dashboardId) { //If the product dashboard and team dashboard match
                Pipeline pipeline = getOrCreatePipeline(collectorItem);
                Map<String, EnvironmentStage> environmentStageMap = pipeline.getEnvironmentStageMap();
                if (environmentStageMap.get(application.getEnvironment()) == null) {
                    environmentStageMap.put(application.getEnvironment(), new EnvironmentStage());
                }

                HashSet<PipelineCommit> allPipelineCommits = new HashSet<>();
                EnvironmentStage commitStage = environmentStageMap.get(PipelineStage.COMMIT.getName());

                if(commitStage != null && commits.size() > 0) {
                    for (PipelineCommit commit : commits) {
                        List<PipelineCommit> intermediateCommits = commitStage.getCommits().stream()
                                .filter(c -> c.getScmParentRevisionNumbers().size() > 1 && c.getTimestamp() < commit.getTimestamp()).collect(Collectors.toList());
                        allPipelineCommits.addAll(intermediateCommits);
                    }
                }

                EnvironmentStage environmentStage = environmentStageMap.get(application.getEnvironment());
                if (environmentStage.getCommits() == null) {
                    environmentStage.setCommits(new HashSet<>());
                }
                environmentStage.getCommits().addAll(new LinkedHashSet<>(allPipelineCommits));
                environmentStage.getCommits().addAll(new LinkedHashSet<>(commits));
                pipelineRepository.save(pipeline);
            }
        }
    }

    private List<CollectorItem> getCollectorItems() {
        List<Collector> collectorList = collectorRepository.findAllByCollectorType(CollectorType.Product); //Get a Product collector
        return collectorItemRepository
                .findByCollectorIdIn(collectorList.stream().map(BaseModel::getId)
                        .collect(Collectors.toList()));
    }

    private Commit getCommit(String commitId, String instanceUrl, String applicationId) {
        String url = joinURL(instanceUrl, new String[]{String.format("%s/%s", GITLAB_PROJECT_API_SUFFIX, applicationId), "repository/commits", commitId});
        final String apiKey = gitlabSettings.getProjectKey(applicationId);
        ResponseEntity<GitLabCommit> response = makeCommitRestCall(url, apiKey);

        GitLabCommit gitlabCommit = response.getBody();
        if (gitlabCommit == null) {
            return null;
        }

        long timestamp = new DateTime(gitlabCommit.getCreatedAt()).getMillis();
        int parentSize = CollectionUtils.isNotEmpty(gitlabCommit.getParentIds()) ? gitlabCommit.getParentIds().size() : 0;
        CommitType commitType = parentSize > 1 ? CommitType.Merge : CommitType.New;

        LastPipeline lastPipeline = gitlabCommit.getLastPipeline();
        if (lastPipeline == null) {
            return null;
        }
        String web_url = lastPipeline.getWeb_url();
        String repo_url = web_url.split("/pipelines")[0];
        return getCommit(gitlabCommit, timestamp, commitType, repo_url);
    }

    private Commit getCommit(GitLabCommit gitlabCommit, long timestamp, CommitType commitType, String repo_url) {
        Commit commit = new Commit();
        commit.setTimestamp(System.currentTimeMillis());
        commit.setScmUrl(repo_url);
        commit.setScmBranch(gitlabCommit.getLastPipeline().getRef());
        commit.setScmRevisionNumber(gitlabCommit.getId());
        commit.setScmAuthor(gitlabCommit.getAuthorName());
        commit.setScmCommitLog(gitlabCommit.getMessage());
        commit.setScmCommitTimestamp(timestamp);
        commit.setNumberOfChanges(1);
        commit.setScmParentRevisionNumbers(gitlabCommit.getParentIds());
        commit.setType(commitType);
        return commit;
    }

    private ResponseEntity<GitLabCommit> makeCommitRestCall(String url, String apiKey) {
        return restOperations.exchange(url, HttpMethod.GET,
                new HttpEntity<>(createHeaders(apiKey)), GitLabCommit.class);
    }


    private boolean isDeployed(String deployStatus) {
        //Skip deployments that are simply "created" or "cancelled".
        //Created deployments are never triggered. So there is no point in considering them
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
        String deploymentsUrl = String.format("%s%s&page=%d&updated_after=%s", application.getApplicationId(),
                DEPLOYMENTS_URL_WITH_SORT,
                pageNum, getDeploymentThresholdTime());
        final String apiKey = gitlabSettings.getProjectKey(application.getApplicationId());
        ResponseEntity<String> inventoryResponse = makeRestCall(application.getInstanceUrl(), new String[]{GITLAB_PROJECT_API_SUFFIX, deploymentsUrl}, apiKey);

        JSONArray allDeploymentJSON = paresAsArray(inventoryResponse);

        if (allDeploymentJSON == null || allDeploymentJSON.size() == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<PipelineCommit> allPipelineCommits = new LinkedHashSet<>();
        List<DeployEnvResCompData> environmentStatuses = new ArrayList<>();
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
            List<PipelineCommit> pipelineCommits = getPipelineCommits(application, deployableObj, environmentObject,
                    deployTimeToConsider == 0 ? getTime(deployableObj, "created_at") : deployTimeToConsider);
            allPipelineCommits.addAll(pipelineCommits);
            environmentStatuses.add(deployData);
        }
        saveToPipelines(application, new ArrayList<>(allPipelineCommits));
        return environmentStatuses;
    }

    private String getDeploymentThresholdTime() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmX")
                .withZone(ZoneOffset.UTC).format
                        (Instant.now().minus(gitlabSettings.getFirstRunHistoryDays(), ChronoUnit.DAYS));
    }

    private Predicate<PipelineCommit> NDaysCommits() {
        return c -> {
            LocalDate commitDate =
                    Instant.ofEpochMilli(c.getScmCommitTimestamp()).atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate currentDate = LocalDate.now();
            long elapsedDays = Duration.between(commitDate.atTime(0, 0),
                    currentDate.atTime(0, 0)).toDays();
            return elapsedDays <= gitlabSettings.getFirstRunHistoryDays();
        };
    }

    // ////// Helpers

    private ResponseEntity<String> makeRestCall(String instanceUrl,
                                                String[] endpoint, String apiKey) {

        String url = joinURL(instanceUrl, endpoint);

        UriComponentsBuilder thisUrl =
                UriComponentsBuilder.fromHttpUrl(url);

        ResponseEntity<String> response = null;
        try {
            log("Calling -> " + thisUrl.toUriString());
            if(!apiKey.isEmpty())
            response = restOperations.exchange(thisUrl.toUriString(), HttpMethod.GET,
                    new HttpEntity<>(createHeaders(apiKey)), String.class);

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

    private long getTime(JSONObject buildJson, String jsonField) {

        String dateToConsider = getString(buildJson, jsonField);
        if (dateToConsider != null) {
            return Instant.from(DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz")
                    .parse(getString(buildJson, jsonField))).toEpochMilli();
        } else {
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
