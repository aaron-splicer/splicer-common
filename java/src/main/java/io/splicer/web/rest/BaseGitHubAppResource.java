package io.splicer.web.rest;

import io.splicer.service.BaseGitHubAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Base REST controller for GitHub App installation flow.
 * Provides install, callback, and status endpoints.
 *
 * Subclass in each consuming project and annotate with @RestController + @RequestMapping("/api/github-app").
 * Do NOT annotate this base class with @RestController.
 */
public abstract class BaseGitHubAppResource {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final BaseGitHubAppService gitHubAppService;

    protected BaseGitHubAppResource(BaseGitHubAppService gitHubAppService) {
        this.gitHubAppService = gitHubAppService;
    }

    protected BaseGitHubAppService getGitHubAppService() {
        return gitHubAppService;
    }

    /**
     * Return the redirect path after a successful callback.
     * Teamcoder returns "/", workbench returns "/app-template/{id}/edit".
     */
    protected abstract String getCallbackSuccessRedirectPath(Long appTemplateId, String username);

    /**
     * Persist the installation to the branch-specific entity (AppTemplate).
     */
    protected abstract void saveInstallation(Long appTemplateId, Long installationId,
                                             String githubUsername, String repositoryUrl);

    /**
     * Build the OAuth status response map from the branch-specific entity.
     */
    protected abstract Map<String, Object> buildOAuthStatusResponse(Long appTemplateId);

    /**
     * Save the selected branch to the AppTemplate.
     */
    protected abstract void saveBranch(Long appTemplateId, String branch);

    /**
     * GET /api/github-app/install : Start GitHub App installation flow
     */
    @GetMapping("/install")
    public RedirectView install(
        @RequestParam Long appTemplateId,
        @RequestParam(required = false) String repositoryUrl,
        @RequestParam(required = false) String origin,
        HttpServletRequest request
    ) {
        log.debug("Starting GitHub App installation for AppTemplate: {} with repo: {}, origin: {}", appTemplateId, repositoryUrl, origin);

        try {
            // Remove previous GitHub App installation if one exists (#848)
            try {
                Map<String, Object> status = buildOAuthStatusResponse(appTemplateId);
                Object existingId = status.get("installationId");
                if (existingId != null) {
                    Long oldInstallationId = ((Number) existingId).longValue();
                    log.info("Deleting previous GitHub App installation {} before reconnect", oldInstallationId);
                    gitHubAppService.deleteInstallation(oldInstallationId);
                }
            } catch (Exception e) {
                log.warn("Could not delete previous installation (may already be removed): {}", e.getMessage());
            }

            String installUrl = gitHubAppService.getInstallationUrl(appTemplateId, repositoryUrl, origin);
            return new RedirectView(installUrl);
        } catch (Exception e) {
            log.error("Error starting installation flow", e);
            String errorUrl = resolveAbsoluteUrl("/?github-auth=error&message=" + e.getMessage(), request);
            return new RedirectView(errorUrl);
        }
    }

    /**
     * GET /api/github-app/callback : GitHub App installation callback
     */
    @GetMapping("/callback")
    @SuppressWarnings("unchecked")
    public RedirectView callback(
        @RequestParam(required = false) Long installation_id,
        @RequestParam(required = false) String setup_action,
        @RequestParam(required = false) String code,
        @RequestParam String state,
        HttpServletRequest request
    ) {
        log.debug("Received GitHub App callback - installation_id: {}, setup_action: {}, has_code: {}",
            installation_id, setup_action, code != null);

        try {
            // Extract appTemplate ID and repository URL from state
            String[] stateParts = state.split(":");
            Long appTemplateId = Long.parseLong(stateParts[0]);

            // Check if callback should be forwarded to a different origin (e.g., teamcoder)
            if (stateParts.length >= 4) {
                try {
                    String origin = new String(
                        Base64.getUrlDecoder().decode(stateParts[3]),
                        StandardCharsets.UTF_8
                    );
                    String currentOrigin = request.getScheme() + "://" + request.getServerName() +
                        (request.getServerPort() != 80 && request.getServerPort() != 443 ? ":" + request.getServerPort() : "");
                    if (!origin.isEmpty() && !origin.equals(currentOrigin)) {
                        // Forward entire callback to the originating app
                        String forwardUrl = origin + "/api/github-app/callback?" + request.getQueryString();
                        log.info("Forwarding GitHub callback to origin: {}", forwardUrl);
                        return new RedirectView(forwardUrl);
                    }
                } catch (Exception e) {
                    log.warn("Could not decode origin from state, processing locally", e);
                }
            }

            // Decode repository URL from state if present (3rd part)
            String repositoryUrl = "";
            if (stateParts.length >= 3) {
                try {
                    repositoryUrl = new String(
                        Base64.getUrlDecoder().decode(stateParts[2]),
                        StandardCharsets.UTF_8
                    );
                    log.debug("Decoded repository URL from state: {}", repositoryUrl);
                } catch (Exception e) {
                    log.warn("Could not decode repository URL from state", e);
                }
            }

            Long installationId;
            String githubUsername = "unknown";

            // Check if this is an installation callback or OAuth callback
            if (installation_id != null) {
                // Direct installation callback - use installation_id from parameter
                installationId = installation_id;
                // Get the account login from the installation details
                try {
                    Map<String, Object> installation = gitHubAppService.getInstallation(installationId);
                    Map<String, Object> account = (Map<String, Object>) installation.get("account");
                    if (account != null && account.get("login") != null) {
                        githubUsername = (String) account.get("login");
                    }
                } catch (Exception e) {
                    log.warn("Could not fetch installation account: {}", e.getMessage());
                }
                log.info("Using installation_id from callback parameter: {}, account: {}", installationId, githubUsername);
            } else if (code != null) {
                // OAuth callback - exchange code for installation token
                Map<String, Object> tokenData = gitHubAppService.exchangeCodeForInstallationToken(code, state);
                String userAccessToken = (String) tokenData.get("user_access_token");
                installationId = ((Number) tokenData.get("installation_id")).longValue();

                // Get GitHub user info
                Map<String, Object> userInfo = gitHubAppService.getGitHubUser(userAccessToken);
                githubUsername = (String) userInfo.get("login");
            } else {
                throw new RuntimeException("No installation_id or code provided in callback");
            }

            // If repository URL not in state, fetch from installation
            log.info("Repository URL from state: '{}', isEmpty: {}, isNull: {}",
                repositoryUrl, repositoryUrl.isEmpty(), repositoryUrl == null);

            if (repositoryUrl == null || repositoryUrl.isEmpty()) {
                log.info("Fetching repositories for installation {}", installationId);
                try {
                    List<Map<String, Object>> repos = gitHubAppService.getInstallationRepositories(installationId);
                    if (repos != null && !repos.isEmpty()) {
                        repositoryUrl = (String) repos.get(0).get("html_url");
                        log.info("Fetched repository URL from installation: {}", repositoryUrl);
                    } else {
                        log.warn("No repositories found for installation {}", installationId);
                    }
                } catch (Exception e) {
                    log.error("Could not fetch repositories for installation {}: {}", installationId, e.getMessage(), e);
                }
            } else {
                log.info("Using repository URL from state: {}", repositoryUrl);
            }

            // Save the installation (branch is NOT set here -- it's a user-configured field on the form)
            log.info("Saving AppTemplate installation - ID: {}, InstallationID: {}, Username: {}, RepoURL: '{}'",
                appTemplateId, installationId, githubUsername, repositoryUrl);

            saveInstallation(appTemplateId, installationId, githubUsername, repositoryUrl);

            log.info("Successfully installed GitHub App for AppTemplate: {} (GitHub user: {}, installation: {}, repo: {})",
                appTemplateId, githubUsername, installationId, repositoryUrl);

            // Redirect using subclass-defined path
            String redirectUrl = resolveAbsoluteUrl(
                getCallbackSuccessRedirectPath(appTemplateId, githubUsername), request);
            return new RedirectView(redirectUrl);

        } catch (Exception e) {
            log.error("Error processing App callback", e);
            String errorUrl = resolveAbsoluteUrl("/?github-auth=error&message=" + e.getMessage(), request);
            return new RedirectView(errorUrl);
        }
    }

    /**
     * GET /api/github-app/status : Get GitHub OAuth status for an AppTemplate
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOAuthStatus(@RequestParam Long appTemplateId) {
        log.debug("REST request to get GitHub OAuth status for AppTemplate: {}", appTemplateId);
        return ResponseEntity.ok(buildOAuthStatusResponse(appTemplateId));
    }

    /**
     * GET /api/github-app/branches : List branches for the AppTemplate's repository
     */
    @GetMapping("/branches")
    @SuppressWarnings("unchecked")
    public ResponseEntity<List<String>> listBranches(@RequestParam Long appTemplateId) {
        log.debug("REST request to list branches for AppTemplate: {}", appTemplateId);

        Map<String, Object> status = buildOAuthStatusResponse(appTemplateId);
        String repositoryUrl = (String) status.get("repositoryUrl");
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            throw new io.splicer.web.rest.errors.BadRequestAlertException(
                "No repository URL configured", "gitHubApp", "norepourl");
        }

        String[] parsed = BaseGitHubAppService.parseAppNameFromRepo(repositoryUrl);
        if (parsed == null) {
            throw new io.splicer.web.rest.errors.BadRequestAlertException(
                "Cannot parse repository URL: " + repositoryUrl, "gitHubApp", "badrepourl");
        }

        // Get installationId from the status — subclass must include it, or we look it up
        // We need installationId; re-read from the AppTemplate via a second status field
        Long installationId = status.get("installationId") != null
            ? ((Number) status.get("installationId")).longValue() : null;
        if (installationId == null) {
            throw new io.splicer.web.rest.errors.BadRequestAlertException(
                "No GitHub installation found for this AppTemplate", "gitHubApp", "noinstallation");
        }

        List<String> branches = gitHubAppService.listRepositoryBranches(installationId, parsed[0], parsed[1]);
        return ResponseEntity.ok(branches);
    }

    /**
     * PUT /api/github-app/branch : Set the develop branch for an AppTemplate
     */
    @PutMapping("/branch")
    public ResponseEntity<Void> setBranch(@RequestBody Map<String, Object> body) {
        Long appTemplateId = ((Number) body.get("appTemplateId")).longValue();
        String branch = (String) body.get("branch");
        log.debug("REST request to set branch '{}' for AppTemplate: {}", branch, appTemplateId);

        saveBranch(appTemplateId, branch);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/github-app/revoke : Revoke OAuth — delete GitHub installation and clear AppTemplate fields
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/revoke")
    public ResponseEntity<Void> revoke(@RequestParam Long appTemplateId) {
        log.info("REST request to revoke GitHub OAuth for AppTemplate: {}", appTemplateId);

        Map<String, Object> status = buildOAuthStatusResponse(appTemplateId);
        Object existingId = status.get("installationId");
        if (existingId != null) {
            Long installationId = ((Number) existingId).longValue();
            try {
                gitHubAppService.deleteInstallation(installationId);
                log.info("Deleted GitHub App installation {}", installationId);
            } catch (Exception e) {
                log.warn("Could not delete GitHub installation {}: {}", installationId, e.getMessage());
            }
        }

        clearInstallation(appTemplateId);
        return ResponseEntity.ok().build();
    }

    /**
     * Clear all GitHub OAuth fields on the AppTemplate.
     */
    protected abstract void clearInstallation(Long appTemplateId);

    /**
     * Resolve a relative URL to an absolute URL using the request's scheme/host/port.
     */
    protected String resolveAbsoluteUrl(String url, HttpServletRequest request) {
        if (url.startsWith("/") && !url.startsWith("//")) {
            String baseUrl = request.getScheme() + "://" + request.getServerName() +
                (request.getServerPort() != 80 && request.getServerPort() != 443 ? ":" + request.getServerPort() : "");
            return baseUrl + url;
        }
        return url;
    }
}
