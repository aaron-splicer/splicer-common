package io.splicer.service;

import com.amazonaws.services.codepipeline.AWSCodePipeline;
import com.amazonaws.services.codepipeline.AWSCodePipelineClientBuilder;
import com.amazonaws.services.codepipeline.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Base service for triggering AWS CodePipeline deployments of preview environments.
 *
 * Orchestrates: resolve config → trigger CodePipeline → wait for completion → return URL.
 *
 * Subclass in each consuming project, annotate with @Service, and implement
 * {@link #resolveDeploymentConfig(Long, String)} to handle entity loading,
 * ownership checks, and GitHub auth resolution (which depend on project-specific
 * classes like AppTemplate, AppTemplateRepository, SecurityUtils).
 *
 * Do NOT annotate this base class with @Service.
 */
public abstract class BasePreviewEnvironmentService {

    private final Logger log = LoggerFactory.getLogger(BasePreviewEnvironmentService.class);

    protected final BaseGitHubAppService gitHubAppService;
    protected final BaseGitHubService gitHubService;

    private AWSCodePipeline codePipelineClient;

    @Value("${aws.codepipeline.name:splicer-publish-validate}")
    private String pipelineName;

    @Value("${aws.codepipeline.region:us-west-1}")
    private String pipelineRegion;

    // Shared cluster for all preview environments
    protected static final String SHARED_PREVIEW_CLUSTER = "splicer-previews";

    // Route53 configuration for automatic DNS (used by aws-ecs-public-dns lambda)
    protected static final String ROUTE53_DOMAIN = "splicerapp.com";

    protected BasePreviewEnvironmentService(BaseGitHubAppService gitHubAppService, BaseGitHubService gitHubService) {
        this.gitHubAppService = gitHubAppService;
        this.gitHubService = gitHubService;
    }

    @PostConstruct
    public void init() {
        if (pipelineName == null || pipelineName.isEmpty()) {
            pipelineName = "splicer-publish-validate";
            log.warn("Pipeline name not configured, using default: {}", pipelineName);
        }
        if (pipelineRegion == null || pipelineRegion.isEmpty()) {
            pipelineRegion = "us-west-1";
            log.warn("Pipeline region not configured, using default: {}", pipelineRegion);
        }

        log.info("Initializing CodePipeline client - Pipeline: {}, Region: {}", pipelineName, pipelineRegion);
        this.codePipelineClient = AWSCodePipelineClientBuilder.standard()
            .withRegion(pipelineRegion)
            .build();
    }

    /**
     * Resolve deployment configuration from an AppTemplate ID and optional branch.
     *
     * Subclass must:
     * 1. Load AppTemplate from DB
     * 2. Check ownership (SecurityUtils)
     * 3. Resolve GitHub auth (installation token or fallback)
     * 4. Verify branch exists
     * 5. Return a {@link DeploymentConfig}
     *
     * @param appTemplateId AppTemplate ID
     * @param branch optional branch override
     * @return DeploymentConfig with all resolved values
     * @throws DeploymentException if resolution fails
     */
    protected abstract DeploymentConfig resolveDeploymentConfig(Long appTemplateId, String branch) throws DeploymentException;

    /**
     * Start a preview environment deployment (non-blocking).
     * Returns immediately with executionId and previewUrl.
     * Frontend polls getPreviewStatus() until complete.
     */
    public StartResult startPreviewDeploy(Long appTemplateId, String branch) throws DeploymentException {
        log.info("Starting preview environment deployment for AppTemplate ID: {}", appTemplateId);

        DeploymentConfig config = resolveDeploymentConfig(appTemplateId, branch);

        String previewId = generatePreviewId(config.appTemplateId);
        log.info("PREVIEW_ID: {}, CLUSTER: {}", previewId, SHARED_PREVIEW_CLUSTER);

        log.info("Starting CodePipeline execution: {}", pipelineName);
        String executionId;
        try {
            executionId = startPipelineExecution(previewId, config);
            log.info("Pipeline execution started: {}", executionId);
        } catch (Exception e) {
            throw new DeploymentException("Failed to start CodePipeline execution: " + e.getMessage(), e);
        }

        String previewUrl = generatePreviewUrl(previewId);
        String imageUri = String.format("663099330265.dkr.ecr.us-east-1.amazonaws.com/gateway:%s", previewId);

        return new StartResult(executionId, previewUrl, imageUri);
    }

    /**
     * Poll pipeline execution status.
     * Returns current status: InProgress, Succeeded, Failed, etc.
     */
    public StatusResult getPreviewStatus(String executionId) {
        try {
            GetPipelineExecutionRequest request = new GetPipelineExecutionRequest()
                .withPipelineName(pipelineName)
                .withPipelineExecutionId(executionId);

            GetPipelineExecutionResult result = codePipelineClient.getPipelineExecution(request);
            String status = result.getPipelineExecution().getStatus();
            log.info("Pipeline execution status: {}", status);
            return new StatusResult(executionId, status);
        } catch (Exception e) {
            log.error("Failed to get pipeline status for {}: {}", executionId, e.getMessage());
            return new StatusResult(executionId, "Error", e.getMessage());
        }
    }

    /**
     * Blocking deploy — starts pipeline and waits for completion.
     * Used by triggerProductionDeploy on workbench (not called via Cloudflare).
     */
    public DeploymentResult deployPreview(Long appTemplateId, String branch) throws DeploymentException {
        StartResult start = startPreviewDeploy(appTemplateId, branch);

        log.info("Waiting for pipeline execution to complete...");
        List<String> pipelineOutput = new ArrayList<>();
        try {
            log.info("Waiting 5 seconds for pipeline execution to be registered...");
            Thread.sleep(5000);

            waitForPipelineCompletion(start.getExecutionId(), pipelineOutput);
            log.info("Pipeline execution completed successfully");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeploymentException("Pipeline monitoring interrupted", pipelineOutput, e);
        } catch (Exception e) {
            throw new DeploymentException("Pipeline execution failed: " + e.getMessage(), pipelineOutput, e);
        }

        log.info("Successfully deployed preview environment: {}", start.getPreviewUrl());
        return new DeploymentResult(start.getPreviewUrl(), start.getImageUri(), pipelineOutput);
    }

    private String startPipelineExecution(String previewId, DeploymentConfig config) {
        try {
            List<PipelineVariable> variables = new ArrayList<>();

            variables.add(new PipelineVariable().withName("home").withValue("preview"));
            variables.add(new PipelineVariable().withName("cluster").withValue(SHARED_PREVIEW_CLUSTER));
            variables.add(new PipelineVariable().withName("service_prefix").withValue(previewId));
            variables.add(new PipelineVariable().withName("user_github_token").withValue(config.githubToken));
            variables.add(new PipelineVariable().withName("app").withValue(config.modelName));
            variables.add(new PipelineVariable().withName("gatewayrepo").withValue(config.repoUrl));
            variables.add(new PipelineVariable().withName("microservicerepo")
                .withValue("github.com/aaron-splicer/mobileMicroservice.git"));
            variables.add(new PipelineVariable().withName("preview_mode").withValue("true"));
            variables.add(new PipelineVariable().withName("publish").withValue("true"));
            variables.add(new PipelineVariable().withName("branch").withValue(config.branch));

            log.info("DEBUG PREVIEW: previewId={}, cluster={}, service_prefix={}, app={}, branch={}",
                previewId, SHARED_PREVIEW_CLUSTER, previewId, config.modelName, config.branch);

            StartPipelineExecutionRequest request = new StartPipelineExecutionRequest()
                .withName(pipelineName)
                .withVariables(variables);

            StartPipelineExecutionResult result = codePipelineClient.startPipelineExecution(request);
            return result.getPipelineExecutionId();

        } catch (Exception e) {
            log.error("Failed to start pipeline execution", e);
            throw new RuntimeException("Failed to start pipeline execution: " + e.getMessage(), e);
        }
    }

    private void waitForPipelineCompletion(String executionId, List<String> output) throws Exception {
        int maxWaitMinutes = 20;
        int pollIntervalSeconds = 30;
        int maxPolls = (maxWaitMinutes * 60) / pollIntervalSeconds;

        for (int i = 0; i < maxPolls; i++) {
            GetPipelineExecutionRequest request = new GetPipelineExecutionRequest()
                .withPipelineName(pipelineName)
                .withPipelineExecutionId(executionId);

            GetPipelineExecutionResult result = codePipelineClient.getPipelineExecution(request);
            PipelineExecution execution = result.getPipelineExecution();
            String status = execution.getStatus();

            output.add(String.format("Pipeline status: %s", status));
            log.info("Pipeline execution status: {}", status);

            if ("Succeeded".equals(status)) {
                return;
            } else if ("Failed".equals(status) || "Cancelled".equals(status) || "Superseded".equals(status)) {
                throw new Exception("Pipeline execution " + status.toLowerCase() + ": " + executionId);
            }

            Thread.sleep(pollIntervalSeconds * 1000L);
        }

        throw new Exception("Pipeline execution timed out after " + maxWaitMinutes + " minutes");
    }

    protected String convertToGitUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isEmpty()) {
            throw new IllegalArgumentException("Repository URL cannot be empty");
        }
        String url = repoUrl
            .replace("https://", "")
            .replace("http://", "")
            .replace("git@", "")
            .replace(":", "/");
        if (!url.endsWith(".git")) {
            url = url + ".git";
        }
        return url;
    }

    protected String generatePreviewId(Long appTemplateId) {
        String previewId = String.format("preview-%d", appTemplateId);
        log.debug("Generated preview ID: {}", previewId);
        return previewId;
    }

    protected String generatePreviewUrl(String previewId) {
        return String.format("http://%s.%s:8080", previewId, ROUTE53_DOMAIN);
    }

    /** Expose pipeline client for subclass production deploy. */
    protected AWSCodePipeline getCodePipelineClient() {
        return codePipelineClient;
    }

    /** Expose pipeline name for subclass production deploy. */
    protected String getPipelineName() {
        return pipelineName;
    }

    /** Expose wait logic for subclass production deploy. */
    protected void waitForCompletion(String executionId, List<String> output) throws Exception {
        waitForPipelineCompletion(executionId, output);
    }

    // ---- Inner classes ----

    /**
     * Result of starting a preview deployment (non-blocking).
     */
    public static class StartResult {
        private final String executionId;
        private final String previewUrl;
        private final String imageUri;

        public StartResult(String executionId, String previewUrl, String imageUri) {
            this.executionId = executionId;
            this.previewUrl = previewUrl;
            this.imageUri = imageUri;
        }

        public String getExecutionId() { return executionId; }
        public String getPreviewUrl() { return previewUrl; }
        public String getImageUri() { return imageUri; }
    }

    /**
     * Pipeline execution status.
     */
    public static class StatusResult {
        private final String executionId;
        private final String status;
        private final String error;

        public StatusResult(String executionId, String status) {
            this.executionId = executionId;
            this.status = status;
            this.error = null;
        }

        public StatusResult(String executionId, String status, String error) {
            this.executionId = executionId;
            this.status = status;
            this.error = error;
        }

        public String getExecutionId() { return executionId; }
        public String getStatus() { return status; }
        public String getError() { return error; }
    }


    /**
     * Configuration resolved by subclass, used by base to drive pipeline execution.
     */
    public static class DeploymentConfig {
        public final String githubToken;
        public final String branch;
        public final String repoUrl;
        public final String modelName;
        public final Long appTemplateId;

        public DeploymentConfig(String githubToken, String branch, String repoUrl, String modelName, Long appTemplateId) {
            this.githubToken = githubToken;
            this.branch = branch;
            this.repoUrl = repoUrl;
            this.modelName = modelName;
            this.appTemplateId = appTemplateId;
        }
    }

    /**
     * Result of preview environment deployment.
     */
    public static class DeploymentResult {
        private final String previewUrl;
        private final String imageUri;
        private final List<String> deployOutput;

        public DeploymentResult(String previewUrl, String imageUri, List<String> deployOutput) {
            this.previewUrl = previewUrl;
            this.imageUri = imageUri;
            this.deployOutput = deployOutput;
        }

        public String getPreviewUrl() { return previewUrl; }
        public String getImageUri() { return imageUri; }
        public List<String> getDeployOutput() { return deployOutput; }
    }

    /**
     * Exception thrown when deployment fails.
     */
    public static class DeploymentException extends Exception {
        private final List<String> deployOutput;

        public DeploymentException(String message) {
            super(message);
            this.deployOutput = new ArrayList<>();
        }

        public DeploymentException(String message, Throwable cause) {
            super(message, cause);
            this.deployOutput = new ArrayList<>();
        }

        public DeploymentException(String message, List<String> deployOutput, Throwable cause) {
            super(message, cause);
            this.deployOutput = deployOutput;
        }

        public List<String> getDeployOutput() { return deployOutput; }
    }
}
