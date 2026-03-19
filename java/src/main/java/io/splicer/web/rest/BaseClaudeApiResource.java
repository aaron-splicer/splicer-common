package io.splicer.web.rest;

import io.splicer.service.BaseGitHubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base REST controller for proxying requests to Claude API.
 * Acts as a backend proxy to avoid CORS issues when calling the Anthropic API from the browser.
 *
 * Subclass in each consuming project and annotate with @RestController + @RequestMapping("/api/claude").
 * Do NOT annotate this base class with @RestController.
 *
 * Subclasses must implement lookupAndResolve() which handles entity-specific logic
 * (loading AppTemplate, ownership check, agenticCoding validation, OAuth vs non-OAuth config resolution).
 */
public abstract class BaseClaudeApiResource {

    private final Logger log = LoggerFactory.getLogger(getClass());
    protected final BaseGitHubService gitHubService;
    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // Cache CLAUDE.md per repo+branch to avoid fetching on every API call (#831)
    private final Map<String, String> claudeMdCache = new ConcurrentHashMap<>();
    private static final String CLAUDE_MD_NOT_FOUND = "__NOT_FOUND__";

    /**
     * Holds resolved GitHub credentials + target for a coding session.
     * Both sendMessage() and executeTool() consume this via lookupAndResolve().
     */
    public static class GitHubConfig {
        public final String token;
        public final String repoUrl;
        public final String branch;

        public GitHubConfig(String token, String repoUrl, String branch) {
            this.token = token;
            this.repoUrl = repoUrl;
            this.branch = branch;
        }
    }

    protected BaseClaudeApiResource(BaseGitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    /**
     * Look up the application entity by ID, verify ownership/authorization, and resolve GitHub config.
     * Returns GitHubConfig on success, or a ResponseEntity error on failure.
     *
     * Subclasses implement this with entity-specific logic (AppTemplate lookup, ownership check,
     * agenticCoding validation, OAuth vs non-OAuth config resolution).
     */
    protected abstract Object lookupAndResolve(Object appTemplateIdObj);

    // ── POST /api/claude/messages ───────────────────────────────────────

    /**
     * POST /api/claude/messages : Send a message to Claude API
     *
     * @param apiKey the Anthropic API key from the client
     * @param requestBody the message request body
     * @return the response from Claude API
     */
    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(
        @RequestHeader("X-API-Key") String apiKey,
        @RequestBody Map<String, Object> requestBody
    ) {
        log.debug("REST request to proxy Claude API message");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "API key is required",
                             "message", "API key is required"));
        }

        // Look up AppTemplate, verify ownership, resolve GitHub config
        Object resolved = lookupAndResolve(requestBody.get("appTemplateId"));
        if (resolved instanceof ResponseEntity) {
            return (ResponseEntity<?>) resolved;
        }
        GitHubConfig ghConfig = (GitHubConfig) resolved;

        try {
            RestTemplate restTemplate = new RestTemplate();

            // Prepare the request body for Claude API (remove our fields from the body)
            Map<String, Object> claudeRequestBody = new HashMap<>(requestBody);
            claudeRequestBody.remove("githubConfig");
            claudeRequestBody.remove("appTemplateId");

            // GitHub config resolved — add tools and system message
            {
                String repoUrl = ghConfig.repoUrl;
                String branch = ghConfig.branch;

                log.debug("GitHub configuration received - Repo: {}, Branch: {}", repoUrl, branch);

                // Pre-fetch CLAUDE.md from repo (cached per repo+branch) (#831)
                Map<String, String> repoInfo = gitHubService.parseRepoUrl(repoUrl);
                String owner = repoInfo.get("owner");
                String repo = repoInfo.get("repo");
                String claudeMdContent = fetchClaudeMd(ghConfig.token, owner, repo, branch);

                // Add GitHub tools to the request
                claudeRequestBody.put("tools", createGitHubTools());

                // Build the project instructions section
                String projectInstructions;
                if (claudeMdContent != null) {
                    projectInstructions =
                        "PROJECT INSTRUCTIONS (from CLAUDE.md — already loaded, do NOT read it again):\n" +
                        claudeMdContent;
                } else {
                    projectInstructions =
                        "PROJECT CONTEXT:\n" +
                        "This is a JHipster-generated Spring Boot + Angular monolith.\n" +
                        "Backend: Spring Boot (Java), Maven (./mvnw). Frontend: Angular, npm. DB migrations: Liquibase.\n" +
                        "Package: io.splicer. Entities in domain/ are AUTO-GENERATED — never edit them directly.";
                }

                // Add system parameter with GitHub context
                String systemPrompt = String.format(
                    "You have full GitHub REST API access via the github_api tool.\n\n" +
                    "REPOSITORY CONFIGURATION:\n" +
                    "- Owner: %s\n" +
                    "- Repo: %s\n" +
                    "- Branch: %s\n" +
                    "- Full URL: %s\n\n" +
                    "GITHUB_API TOOL USAGE:\n" +
                    "Use the github_api tool with method, path, and optional body. All paths start with /repos/%s/%s/...\n\n" +
                    "COMMON OPERATIONS:\n" +
                    "- Read file: GET /repos/%s/%s/contents/{path}?ref=%s\n" +
                    "- Write file: PUT /repos/%s/%s/contents/{path} with body {\"message\": \"...\", \"content\": \"<base64>\", \"branch\": \"%s\", \"sha\": \"<existing sha or omit for new>\"}\n" +
                    "- List directory: GET /repos/%s/%s/contents/{path}?ref=%s\n" +
                    "- Recent commits: GET /repos/%s/%s/commits?sha=%s&per_page=5\n" +
                    "- Commit details: GET /repos/%s/%s/commits/{sha}\n\n" +
                    "IMPORTANT: When writing files, you MUST base64-encode the content in the body. " +
                    "To update an existing file, first read it to get its SHA, then include that SHA in the PUT body.\n\n" +
                    "RESTRICTIONS (enforced server-side — do NOT attempt these):\n" +
                    "- You are LOCKED to branch '%s' — all operations use this branch only\n" +
                    "- You CANNOT list, create, switch, or delete branches\n" +
                    "- You CANNOT access other repositories\n" +
                    "- Write operations are limited to file contents (PUT /repos/.../contents/...)\n" +
                    "- Do NOT waste calls trying to work around these restrictions\n\n" +
                    "CRITICAL INSTRUCTIONS:\n" +
                    "- You have read/write access to files and can view commit history\n" +
                    "- Go directly to files you need — do NOT waste calls exploring the repo structure\n\n" +
                    "%s\n\n" +
                    "STANDARD JHIPSTER FILE PATHS (use these directly):\n" +
                    "- src/main/webapp/content/scss/vendor.scss — Bootswatch theme import (e.g. flatly/darkly)\n" +
                    "- src/main/webapp/content/scss/global.scss — Global custom styles\n" +
                    "- src/main/webapp/app/ — Angular source (components, modules, routing)\n" +
                    "- src/main/java/io/splicer/web/rest/ — REST controllers\n" +
                    "- src/main/java/io/splicer/service/ — Business logic services\n" +
                    "- src/main/java/io/splicer/config/ — Spring configuration\n" +
                    "- src/main/resources/config/application-dev.yml — Dev profile config\n" +
                    "- src/main/resources/config/application-prod.yml — Prod profile config",
                    owner, repo, branch, repoUrl,
                    owner, repo,
                    owner, repo, branch,
                    owner, repo, branch,
                    owner, repo, branch,
                    owner, repo, branch,
                    owner, repo,
                    branch,
                    projectInstructions
                );

                claudeRequestBody.put("system", systemPrompt);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(claudeRequestBody, headers);

            // Retry logic for overloaded errors (529)
            int maxRetries = 3;
            int retryDelayMs = 1000; // Start with 1 second
            ResponseEntity<Map> response = null;
            Exception lastException = null;

            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    response = restTemplate.exchange(
                        ANTHROPIC_API_URL,
                        HttpMethod.POST,
                        entity,
                        Map.class
                    );
                    // Success - break out of retry loop
                    break;
                } catch (HttpStatusCodeException e) {
                    lastException = e;
                    // Check for 529 (overloaded) or 503 (service unavailable)
                    if (e.getRawStatusCode() == 529 || e.getRawStatusCode() == 503) {
                        if (attempt < maxRetries - 1) {
                            log.warn("Claude API overloaded ({}), retrying in {}ms (attempt {}/{})",
                                e.getRawStatusCode(), retryDelayMs, attempt + 1, maxRetries);
                            try {
                                Thread.sleep(retryDelayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Retry interrupted", ie);
                            }
                            // Exponential backoff
                            retryDelayMs *= 2;
                        } else {
                            log.error("Claude API still overloaded after {} retries", maxRetries);
                        }
                    } else {
                        // For other errors, don't retry
                        throw e;
                    }
                }
            }

            if (response == null) {
                // All retries failed
                if (lastException instanceof HttpStatusCodeException) {
                    HttpStatusCodeException hsce = (HttpStatusCodeException) lastException;
                    String overloadMsg = "Claude API is overloaded. Please try again in a few moments.";
                    return ResponseEntity.status(hsce.getRawStatusCode())
                        .body(Map.of("error", overloadMsg,
                                     "message", overloadMsg));
                }
                throw new RuntimeException("Failed to get response from Claude API after " + maxRetries + " attempts");
            }

            return ResponseEntity.ok(response.getBody());

        } catch (HttpClientErrorException e) {
            log.error("Error calling Claude API: {}", e.getMessage());
            String apiMsg = "Claude API error: " + e.getStatusCode();
            return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", apiMsg, "message", apiMsg));
        } catch (Exception e) {
            log.error("Unexpected error calling Claude API", e);
            String errMsg = "Failed to communicate with Claude API: " + e.getMessage();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", errMsg, "message", errMsg));
        }
    }

    // ── POST /api/claude/tool-execute ───────────────────────────────────

    /**
     * POST /api/claude/tool-execute : Execute a GitHub tool
     *
     * @param requestBody containing toolName, toolInput, and githubConfig
     * @return the result of tool execution
     */
    @PostMapping("/tool-execute")
    public ResponseEntity<?> executeTool(@RequestBody Map<String, Object> requestBody) {
        log.debug("REST request to execute GitHub tool");

        try {
            String toolName = (String) requestBody.get("toolName");
            Map<String, Object> toolInput = (Map<String, Object>) requestBody.get("toolInput");
            Map<String, Object> githubConfig = (Map<String, Object>) requestBody.get("githubConfig");

            if (githubConfig == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "GitHub configuration is required",
                                 "message", "GitHub configuration is required"));
            }

            // Look up AppTemplate, verify ownership, resolve GitHub config
            Object resolved = lookupAndResolve(githubConfig.get("appTemplateId"));
            if (resolved instanceof ResponseEntity) {
                return (ResponseEntity<?>) resolved;
            }
            GitHubConfig ghConfig = (GitHubConfig) resolved;

            String token = ghConfig.token;
            String repoUrl = ghConfig.repoUrl;
            String branch = ghConfig.branch;

            Map<String, String> repoInfo = gitHubService.parseRepoUrl(repoUrl);
            String owner = repoInfo.get("owner");
            String repo = repoInfo.get("repo");

            Object result;

            if ("github_api".equals(toolName)) {
                String method = (String) toolInput.get("method");
                String path = (String) toolInput.get("path");
                Map<String, Object> body = (Map<String, Object>) toolInput.get("body");

                if (method == null || path == null) {
                    return ResponseEntity.badRequest()
                        .body(Map.of("error", "method and path are required for github_api tool",
                                     "message", "method and path are required for github_api tool"));
                }

                // Security: enforce owner/repo — rewrite path prefix to configured values
                String repoPrefix = "/repos/" + owner + "/" + repo;
                if (path.startsWith("/repos/")) {
                    // Strip whatever owner/repo Claude used, replace with configured
                    String[] pathParts = path.split("/", 5); // ["", "repos", "owner", "repo", "rest..."]
                    if (pathParts.length >= 5) {
                        path = repoPrefix + "/" + pathParts[4];
                    } else if (pathParts.length == 4) {
                        path = repoPrefix;
                    } else {
                        return ResponseEntity.ok(Map.of("result",
                            "Error: Invalid path format. Use /repos/{owner}/{repo}/..."));
                    }
                } else {
                    return ResponseEntity.ok(Map.of("result",
                        "Error: Path must start with /repos/. You are restricted to " + repoPrefix));
                }

                // Security: block branch-related endpoints
                String pathLower = path.toLowerCase();
                if (pathLower.contains("/branches") || pathLower.contains("/git/refs") ||
                    pathLower.contains("/git/ref")) {
                    return ResponseEntity.ok(Map.of("result",
                        "Error: Branch operations are not allowed. You are restricted to branch: " + branch));
                }

                // Security: force configured branch in ALL requests
                // Replace or append ref= param
                if (path.contains("ref=")) {
                    path = path.replaceAll("ref=[^&]*", "ref=" + branch);
                } else if (path.contains("/contents/") || path.contains("/contents?")) {
                    path += (path.contains("?") ? "&" : "?") + "ref=" + branch;
                }
                // For commits endpoint, force sha= to configured branch
                if (path.contains("/commits")) {
                    if (path.contains("sha=")) {
                        path = path.replaceAll("sha=[^&]*", "sha=" + branch);
                    } else if (!path.matches(".*/commits/[a-f0-9]+.*")) {
                        // Listing commits (not a specific SHA) — force branch
                        path += (path.contains("?") ? "&" : "?") + "sha=" + branch;
                    }
                }

                // Security: block non-GET methods that aren't to contents (file write)
                if (!"GET".equalsIgnoreCase(method) && !path.contains("/contents/")) {
                    return ResponseEntity.ok(Map.of("result",
                        "Error: Write operations are only allowed on file contents. Use PUT /repos/{owner}/{repo}/contents/{path}"));
                }

                // Security: for PUT to contents, force branch in body
                if ("PUT".equalsIgnoreCase(method) && path.contains("/contents/") && body != null) {
                    body.put("branch", branch);
                }

                log.debug("github_api: {} {} (enforced owner={}, repo={}, branch={})", method, path, owner, repo, branch);
                result = gitHubService.githubApi(token, method, path, body);
            } else {
                String unknownMsg = "Unknown tool: " + toolName;
                return ResponseEntity.badRequest()
                    .body(Map.of("error", unknownMsg, "message", unknownMsg));
            }

            return ResponseEntity.ok(Map.of("result", result));

        } catch (Exception e) {
            log.error("Error executing GitHub tool", e);
            // Return 200 with error in result so frontend sends it back to Claude as a tool_result
            // instead of breaking the tool_use/tool_result sequence
            return ResponseEntity.ok(Map.of("result", "Error: " + e.getMessage()));
        }
    }

    // ── Protected helpers ───────────────────────────────────────────────

    /**
     * Create GitHub tool definitions for Claude API.
     * Protected so subclasses can override to add additional tools.
     */
    protected List<Map<String, Object>> createGitHubTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        Map<String, Object> githubApiTool = new HashMap<>();
        githubApiTool.put("name", "github_api");
        githubApiTool.put("description",
            "Call any GitHub REST API endpoint. The repository owner and repo are already configured — " +
            "use them in your paths. Examples:\n" +
            "- GET /repos/{owner}/{repo}/contents/{path}?ref={branch} — read a file\n" +
            "- PUT /repos/{owner}/{repo}/contents/{path} — create/update a file (body: {message, content (base64), branch, sha})\n" +
            "- GET /repos/{owner}/{repo}/contents/{path}?ref={branch} — list a directory\n" +
            "- GET /repos/{owner}/{repo}/commits?sha={branch}&per_page=5 — list recent commits\n" +
            "- GET /repos/{owner}/{repo}/commits/{sha} — get commit details with diffs\n" +
            "- GET /repos/{owner}/{repo}/branches — list branches\n" +
            "See https://docs.github.com/en/rest for all available endpoints.");
        Map<String, Object> apiSchema = new HashMap<>();
        apiSchema.put("type", "object");
        Map<String, Object> apiProperties = new HashMap<>();
        apiProperties.put("method", Map.of(
            "type", "string",
            "description", "HTTP method: GET, POST, PUT, PATCH, DELETE"
        ));
        apiProperties.put("path", Map.of(
            "type", "string",
            "description", "API path starting with /repos/ (e.g., /repos/owner/repo/contents/src/main/App.java?ref=main)"
        ));
        apiProperties.put("body", Map.of(
            "type", "object",
            "description", "Request body for POST/PUT/PATCH requests (optional)"
        ));
        apiSchema.put("properties", apiProperties);
        apiSchema.put("required", List.of("method", "path"));
        githubApiTool.put("input_schema", apiSchema);
        tools.add(githubApiTool);

        return tools;
    }

    /**
     * Fetch CLAUDE.md from the repository, with caching per repo+branch (#831).
     * Returns the file content as a string, or null if not found.
     */
    protected String fetchClaudeMd(String token, String owner, String repo, String branch) {
        String cacheKey = owner + "/" + repo + "/" + branch;
        String cached = claudeMdCache.get(cacheKey);
        if (cached != null) {
            return CLAUDE_MD_NOT_FOUND.equals(cached) ? null : cached;
        }

        try {
            Map<String, Object> fileResult = gitHubService.getFileContent(token, owner, repo, "CLAUDE.md", branch);
            String content = (String) fileResult.get("content");
            if (content != null) {
                // GitHub API returns base64-encoded content
                if (fileResult.get("encoding") != null && "base64".equals(fileResult.get("encoding"))) {
                    content = new String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8);
                }
                claudeMdCache.put(cacheKey, content);
                log.info("Loaded CLAUDE.md from {}/{} branch {} ({} chars)", owner, repo, branch, content.length());
                return content;
            }
        } catch (Exception e) {
            log.debug("CLAUDE.md not found in {}/{} branch {}: {}", owner, repo, branch, e.getMessage());
        }

        claudeMdCache.put(cacheKey, CLAUDE_MD_NOT_FOUND);
        return null;
    }
}
