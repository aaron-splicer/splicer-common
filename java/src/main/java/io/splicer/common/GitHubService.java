package io.splicer.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service for interacting with GitHub API.
 * Provides methods to read, write, and list files in a GitHub repository.
 */
@Service
public class GitHubService {

    private final Logger log = LoggerFactory.getLogger(GitHubService.class);
    private static final String GITHUB_API_BASE = "https://api.github.com";

    /**
     * Get the contents of a file from a GitHub repository
     *
     * @param token GitHub access token
     * @param owner Repository owner
     * @param repo Repository name
     * @param path File path in repository
     * @param branch Branch name
     * @return File content and metadata
     */
    public Map<String, Object> getFileContent(String token, String owner, String repo, String path, String branch) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = String.format("%s/repos/%s/%s/contents/%s?ref=%s",
                GITHUB_API_BASE, owner, repo, path, branch);

            HttpHeaders headers = createHeaders(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            if (responseBody != null && responseBody.containsKey("content")) {
                String encodedContent = (String) responseBody.get("content");
                String decodedContent = new String(
                    Base64.getDecoder().decode(encodedContent.replaceAll("\\s", "")),
                    StandardCharsets.UTF_8
                );
                responseBody.put("decodedContent", decodedContent);
            }

            return responseBody;
        } catch (HttpClientErrorException e) {
            log.error("Error getting file from GitHub: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String errorMsg = "Failed to get file: " + e.getStatusText();
            if (e.getStatusCode().value() == 404) {
                errorMsg += ". File not found or insufficient permissions.";
            } else if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                errorMsg += ". GitHub token is invalid or lacks required permissions. Required: 'repo' scope.";
            }
            throw new RuntimeException(errorMsg);
        } catch (Exception e) {
            log.error("Unexpected error getting file from GitHub", e);
            throw new RuntimeException("Failed to get file: " + e.getMessage());
        }
    }

    /**
     * Create a branch from the base branch
     */
    public void createBranch(String token, String owner, String repo, String newBranch, String baseBranch) {
        try {
            RestTemplate restTemplate = new RestTemplate();

            // First, get the SHA of the base branch
            String baseBranchUrl = String.format("%s/repos/%s/%s/git/ref/heads/%s",
                GITHUB_API_BASE, owner, repo, baseBranch);

            HttpHeaders headers = createHeaders(token);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> baseBranchResponse = restTemplate.exchange(
                baseBranchUrl, HttpMethod.GET, entity, Map.class);

            Map<String, Object> branchObject = (Map<String, Object>) baseBranchResponse.getBody().get("object");
            String baseSha = (String) branchObject.get("sha");

            log.debug("Base branch '{}' SHA: {}", baseBranch, baseSha);

            // Create the new branch
            String createBranchUrl = String.format("%s/repos/%s/%s/git/refs",
                GITHUB_API_BASE, owner, repo);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("ref", "refs/heads/" + newBranch);
            requestBody.put("sha", baseSha);

            HttpEntity<Map<String, Object>> createEntity = new HttpEntity<>(requestBody, headers);
            restTemplate.exchange(createBranchUrl, HttpMethod.POST, createEntity, Map.class);

            log.info("Created new branch '{}' from '{}'", newBranch, baseBranch);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 422) {
                log.debug("Branch '{}' already exists", newBranch);
                // Branch already exists, this is fine
                return;
            }
            log.error("Error creating branch in GitHub: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to create branch: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Unexpected error creating branch in GitHub", e);
            throw new RuntimeException("Failed to create branch: " + e.getMessage());
        }
    }

    /**
     * Create or update a file in a GitHub repository
     *
     * @param token GitHub access token
     * @param owner Repository owner
     * @param repo Repository name
     * @param path File path in repository
     * @param content File content
     * @param message Commit message
     * @param branch Branch name
     * @param sha SHA of existing file (required for updates, null for new files)
     * @return Response from GitHub API
     */
    public Map<String, Object> createOrUpdateFile(String token, String owner, String repo, String path,
                                                   String content, String message, String branch, String sha) {
        // Validate required parameters
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null. Please provide the file content.");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Commit message cannot be null or empty.");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty.");
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = String.format("%s/repos/%s/%s/contents/%s",
                GITHUB_API_BASE, owner, repo, path);

            HttpHeaders headers = createHeaders(token);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("message", message);
            requestBody.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
            requestBody.put("branch", branch);
            if (sha != null && !sha.isEmpty()) {
                requestBody.put("sha", sha);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Error creating/updating file in GitHub: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String errorMsg = "Failed to create/update file: " + e.getStatusText();
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                errorMsg += ". GitHub token lacks required permissions. Required: 'repo' scope for write access.";
            } else if (e.getStatusCode().value() == 404) {
                errorMsg += ". Branch '" + branch + "' not found. The branch must exist before writing files to it.";
            } else if (e.getStatusCode().value() == 409) {
                errorMsg += ". File SHA mismatch - file may have been modified.";
            }
            throw new RuntimeException(errorMsg);
        } catch (Exception e) {
            log.error("Unexpected error creating/updating file in GitHub", e);
            throw new RuntimeException("Failed to create/update file: " + e.getMessage());
        }
    }

    /**
     * List contents of a directory in a GitHub repository
     *
     * @param token GitHub access token
     * @param owner Repository owner
     * @param repo Repository name
     * @param path Directory path in repository
     * @param branch Branch name
     * @return List of files and directories
     */
    public List<Map<String, Object>> listDirectory(String token, String owner, String repo, String path, String branch) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = String.format("%s/repos/%s/%s/contents/%s?ref=%s",
                GITHUB_API_BASE, owner, repo, path, branch);

            HttpHeaders headers = createHeaders(token);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, entity, List.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Error listing directory in GitHub: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String errorMsg = "Failed to list directory: " + e.getStatusText();
            if (e.getStatusCode().value() == 404) {
                errorMsg += ". Directory not found or insufficient permissions.";
            } else if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                errorMsg += ". GitHub token is invalid or lacks required permissions. Required: 'repo' or 'public_repo' scope.";
            }
            throw new RuntimeException(errorMsg);
        } catch (Exception e) {
            log.error("Unexpected error listing directory in GitHub", e);
            throw new RuntimeException("Failed to list directory: " + e.getMessage());
        }
    }

    /**
     * Get repository tree (recursive listing)
     *
     * @param token GitHub access token
     * @param owner Repository owner
     * @param repo Repository name
     * @param branch Branch name
     * @return Repository tree
     */
    public Map<String, Object> getRepositoryTree(String token, String owner, String repo, String branch) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = createHeaders(token);

            // First, get the commit SHA for the branch
            String branchUrl = String.format("%s/repos/%s/%s/branches/%s",
                GITHUB_API_BASE, owner, repo, branch);

            HttpEntity<String> branchEntity = new HttpEntity<>(headers);
            ResponseEntity<Map> branchResponse = restTemplate.exchange(branchUrl, HttpMethod.GET, branchEntity, Map.class);

            Map<String, Object> branchData = branchResponse.getBody();
            if (branchData == null || !branchData.containsKey("commit")) {
                throw new RuntimeException("Failed to get branch information");
            }

            Map<String, Object> commitData = (Map<String, Object>) branchData.get("commit");
            String commitSha = (String) commitData.get("sha");

            // Now get the tree using the commit SHA
            String treeUrl = String.format("%s/repos/%s/%s/git/trees/%s?recursive=1",
                GITHUB_API_BASE, owner, repo, commitSha);

            HttpEntity<String> treeEntity = new HttpEntity<>(headers);
            ResponseEntity<Map> treeResponse = restTemplate.exchange(treeUrl, HttpMethod.GET, treeEntity, Map.class);

            return treeResponse.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Error getting repository tree from GitHub: {}", e.getResponseBodyAsString());
            throw new RuntimeException("Failed to get repository tree: " + e.getStatusText() +
                ". This may be due to insufficient permissions on the GitHub token. " +
                "Required scopes: 'repo' or 'public_repo' (for public repos).");
        } catch (Exception e) {
            log.error("Unexpected error getting repository tree from GitHub", e);
            throw new RuntimeException("Failed to get repository tree: " + e.getMessage());
        }
    }

    /**
     * Generic GitHub API call
     */
    public Object githubApi(String token, String method, String path, Map<String, Object> body) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = GITHUB_API_BASE + path;

            HttpHeaders headers = createHeaders(token);
            HttpEntity<?> entity = body != null ? new HttpEntity<>(body, headers) : new HttpEntity<>(headers);

            HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
            ResponseEntity<Object> response = restTemplate.exchange(url, httpMethod, entity, Object.class);
            return response.getBody();
        } catch (HttpClientErrorException e) {
            log.error("GitHub API error: {} {} - {} - {}", method, path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("GitHub API " + method + " " + path + " failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Unexpected error calling GitHub API: {} {}", method, path, e);
            throw new RuntimeException("GitHub API " + method + " " + path + " failed: " + e.getMessage());
        }
    }

    /**
     * Parse repository URL to extract owner and repo name
     *
     * @param repoUrl GitHub repository URL
     * @return Map containing owner and repo
     */
    public Map<String, String> parseRepoUrl(String repoUrl) {
        // Handle formats like:
        // https://github.com/owner/repo
        // https://github.com/owner/repo.git
        // github.com/owner/repo
        // owner/repo

        String cleaned = repoUrl.replaceAll("https?://", "")
                                .replaceAll("github\\.com/", "")
                                .replaceAll("\\.git$", "");

        String[] parts = cleaned.split("/");
        if (parts.length >= 2) {
            Map<String, String> result = new HashMap<>();
            result.put("owner", parts[0]);
            result.put("repo", parts[1]);
            return result;
        }

        throw new IllegalArgumentException("Invalid GitHub repository URL: " + repoUrl);
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/vnd.github.v3+json");
        return headers;
    }
}
