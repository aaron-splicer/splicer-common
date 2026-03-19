package io.splicer.service;

import io.splicer.web.rest.errors.BadRequestAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/**
 * Base GitHub App service for installation flow and token management.
 * Provides OAuth flow, JWT authentication, and installation token generation.
 *
 * Subclass in each consuming project and annotate with @Service.
 * Do NOT annotate this base class with @Service.
 */
public class BaseGitHubAppService {

    private final Logger log = LoggerFactory.getLogger(BaseGitHubAppService.class);

    @Value("${github.app.id:}")
    private String appId;

    @Value("${github.app.client-id:}")
    private String clientId;

    @Value("${github.app.client-secret:}")
    private String clientSecret;

    @Value("${github.app.private-key-path:}")
    private String privateKeyPath;

    @Value("${github.app.private-key:}")
    private String privateKeyContent;

    @Value("${github.app.redirect-uri:}")
    private String redirectUri;

    @Value("${github.app.slug:splicer-workbench-local-dev-app}")
    private String appSlug;

    protected static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String GITHUB_OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token";

    /**
     * Parse appGroup and appName from a GitHub repository URL.
     * Returns {appGroup, appName} or null if URL is null/empty/unparseable.
     *
     * @param repositoryUrl e.g. "https://github.com/owner/repo" or "https://github.com/owner/repo.git"
     * @return String array {appGroup, appName} or null
     */
    public static String[] parseAppNameFromRepo(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isEmpty()) {
            return null;
        }
        String url = repositoryUrl
            .replace("https://github.com/", "")
            .replace("http://github.com/", "")
            .replaceAll("\\.git$", "");
        String[] parts = url.split("/");
        if (parts.length >= 2) {
            return new String[]{parts[0], parts[1]};
        }
        return null;
    }

    /**
     * Generate GitHub App installation URL.
     *
     * @param appTemplateId AppTemplate ID for state parameter
     * @param repositoryUrl Repository URL to suggest during installation
     * @param origin Origin URL for redirect after installation
     * @return Installation URL
     */
    public String getInstallationUrl(Long appTemplateId, String repositoryUrl, String origin) {
        validateAppConfig();

        // Format: appTemplateId:UUID:base64(repoUrl):base64(origin)
        String state = appTemplateId + ":" + UUID.randomUUID().toString();
        if (repositoryUrl != null && !repositoryUrl.isEmpty()) {
            String encodedRepo = Base64.getUrlEncoder().encodeToString(repositoryUrl.getBytes(StandardCharsets.UTF_8));
            state = state + ":" + encodedRepo;
        } else {
            state = state + ":";
        }
        if (origin != null && !origin.isEmpty()) {
            String encodedOrigin = Base64.getUrlEncoder().encodeToString(origin.getBytes(StandardCharsets.UTF_8));
            state = state + ":" + encodedOrigin;
        }

        return String.format("https://github.com/apps/%s/installations/new?state=%s",
            this.appSlug, state);
    }

    /**
     * Exchange OAuth code for access token, then get installation access token.
     *
     * @param code OAuth code from GitHub callback
     * @param state State parameter for CSRF validation
     * @return Map containing access_token, installation_id, user_access_token, scope
     */
    public Map<String, Object> exchangeCodeForInstallationToken(String code, String state) {
        validateAppConfig();

        try {
            RestTemplate restTemplate = new RestTemplate();

            // Step 1: Exchange code for user access token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("client_id", clientId);
            requestBody.put("client_secret", clientSecret);
            requestBody.put("code", code);
            requestBody.put("redirect_uri", redirectUri);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                GITHUB_OAUTH_TOKEN_URL, request, Map.class);

            Map<String, Object> tokenData = response.getBody();
            if (tokenData == null || !tokenData.containsKey("access_token")) {
                throw new BadRequestAlertException("Failed to get access token from GitHub", "gitHubApp", "noaccesstoken");
            }

            String accessToken = (String) tokenData.get("access_token");

            // Step 2: Get user's installations
            HttpHeaders apiHeaders = new HttpHeaders();
            apiHeaders.set("Authorization", "Bearer " + accessToken);
            apiHeaders.set("Accept", "application/vnd.github+json");

            HttpEntity<Void> apiRequest = new HttpEntity<>(apiHeaders);
            ResponseEntity<Map> installationsResponse = restTemplate.exchange(
                GITHUB_API_BASE + "/user/installations",
                HttpMethod.GET, apiRequest, Map.class);

            Map<String, Object> installationsData = installationsResponse.getBody();
            List<Map<String, Object>> installations = (List<Map<String, Object>>) installationsData.get("installations");

            if (installations == null || installations.isEmpty()) {
                throw new BadRequestAlertException("No installations found. Please install the GitHub App first.", "gitHubApp", "noinstallations");
            }

            // Get the first installation
            Map<String, Object> installation = installations.get(0);
            Long installationId = ((Number) installation.get("id")).longValue();

            // Step 3: Generate installation access token using JWT
            String installationToken = generateInstallationToken(installationId);

            Map<String, Object> result = new HashMap<>();
            result.put("access_token", installationToken);
            result.put("installation_id", installationId);
            result.put("user_access_token", accessToken);
            result.put("scope", tokenData.get("scope"));

            return result;

        } catch (BadRequestAlertException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error exchanging code for installation token", e);
            throw new BadRequestAlertException("Failed to exchange code for installation token: " + e.getMessage(), "gitHubApp", "tokenexchangefailed");
        }
    }

    /**
     * Generate installation access token using JWT authentication.
     *
     * @param installationId Installation ID
     * @return Installation access token
     */
    protected String generateInstallationToken(Long installationId) {
        try {
            String jwt = generateJWT();

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + jwt);
            headers.set("Accept", "application/vnd.github+json");

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                GITHUB_API_BASE + "/app/installations/" + installationId + "/access_tokens",
                request, Map.class);

            Map<String, Object> tokenData = response.getBody();
            return (String) tokenData.get("token");

        } catch (Exception e) {
            log.error("Error generating installation token", e);
            throw new BadRequestAlertException("Failed to generate installation token: " + e.getMessage(), "gitHubApp", "installtokenfailed");
        }
    }

    private String generateJWT() {
        try {
            PrivateKey privateKey = loadPrivateKey();

            long nowMillis = System.currentTimeMillis();
            Date now = new Date(nowMillis);
            Date expiration = new Date(nowMillis + 600000); // 10 minutes

            return Jwts.builder()
                .setIssuedAt(now)
                .setExpiration(expiration)
                .setIssuer(appId)
                .signWith(SignatureAlgorithm.RS256, privateKey)
                .compact();

        } catch (Exception e) {
            log.error("Error generating JWT", e);
            throw new BadRequestAlertException("Failed to generate JWT: " + e.getMessage(), "gitHubApp", "jwtfailed");
        }
    }

    private PrivateKey loadPrivateKey() {
        try {
            String keyContent;

            if (privateKeyContent != null && !privateKeyContent.isEmpty()) {
                keyContent = privateKeyContent.replace("\\n", "\n");
            } else if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                keyContent = new String(Files.readAllBytes(new File(privateKeyPath).toPath()));
            } else {
                throw new BadRequestAlertException("Private key not configured", "gitHubApp", "noprivatekey");
            }

            String privateKeyPEM = keyContent
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

            byte[] decoded = Base64.getDecoder().decode(privateKeyPEM);

            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);

        } catch (BadRequestAlertException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error loading private key", e);
            throw new BadRequestAlertException("Failed to load private key: " + e.getMessage(), "gitHubApp", "privatekeyfailed");
        }
    }

    /**
     * Get GitHub user info using access token.
     *
     * @param accessToken Access token
     * @return User info map
     */
    public Map<String, Object> getGitHubUser(String accessToken) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.set("Accept", "application/vnd.github+json");

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                GITHUB_API_BASE + "/user",
                HttpMethod.GET, request, Map.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("Error getting GitHub user", e);
            throw new BadRequestAlertException("Failed to get GitHub user: " + e.getMessage(), "gitHubApp", "getuserfailed");
        }
    }

    /**
     * Get installation access token for a specific installation ID.
     *
     * @param installationId GitHub App installation ID
     * @return Installation access token
     */
    public String getInstallationToken(Long installationId) {
        if (installationId == null) {
            throw new IllegalArgumentException("Installation ID cannot be null");
        }

        validateAppConfigMinimal();
        return generateInstallationToken(installationId);
    }

    /**
     * Get repositories accessible by an installation.
     *
     * @param installationId GitHub App installation ID
     * @return List of repository maps
     */
    public List<Map<String, Object>> getInstallationRepositories(Long installationId) {
        String token = getInstallationToken(installationId);

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/vnd.github+json");

        ResponseEntity<Map> response = restTemplate.exchange(
            GITHUB_API_BASE + "/installation/repositories",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        List<Map<String, Object>> repositories = (List<Map<String, Object>>) response.getBody().get("repositories");
        log.info("Fetched {} repositories for installation {}", repositories != null ? repositories.size() : 0, installationId);
        if (repositories != null && !repositories.isEmpty()) {
            log.info("First repository: {}", repositories.get(0));
        }
        return repositories;
    }

    /**
     * Validate minimal GitHub App configuration (for JWT-based auth only).
     */
    protected void validateAppConfigMinimal() {
        if (appId == null || appId.isEmpty()) {
            throw new BadRequestAlertException("GitHub App ID not configured. Set GITHUB_APP_ID environment variable.", "gitHubApp", "noappid");
        }
        if ((privateKeyPath == null || privateKeyPath.isEmpty()) &&
            (privateKeyContent == null || privateKeyContent.isEmpty())) {
            throw new BadRequestAlertException("GitHub App private key not configured. Set GITHUB_APP_PRIVATE_KEY or GITHUB_APP_PRIVATE_KEY_PATH.", "gitHubApp", "noprivatekey");
        }
    }

    /**
     * Validate full GitHub App configuration.
     */
    protected void validateAppConfig() {
        if (appId == null || appId.isEmpty()) {
            throw new BadRequestAlertException("GitHub App ID not configured. Set GITHUB_APP_ID environment variable.", "gitHubApp", "noappid");
        }
        if (clientId == null || clientId.isEmpty()) {
            throw new BadRequestAlertException("GitHub App client ID not configured. Set GITHUB_APP_CLIENT_ID environment variable.", "gitHubApp", "noclientid");
        }
        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new BadRequestAlertException("GitHub App client secret not configured. Set GITHUB_APP_CLIENT_SECRET environment variable.", "gitHubApp", "noclientsecret");
        }
        if ((privateKeyPath == null || privateKeyPath.isEmpty()) &&
            (privateKeyContent == null || privateKeyContent.isEmpty())) {
            throw new BadRequestAlertException("GitHub App private key not configured. Set GITHUB_APP_PRIVATE_KEY or GITHUB_APP_PRIVATE_KEY_PATH.", "gitHubApp", "noprivatekey");
        }
    }

    // ---- Git/GitHub helper methods ----

    /**
     * Run a git command, capturing stderr. Throws BadRequestAlertException on failure.
     */
    protected void runGit(String workDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                // Strip tokens from error message before exposing
                String safeError = stderr.replaceAll("https://[^@]+@", "https://***@");
                log.error("git command failed (exit {}): {}", exitCode, safeError);
                throw new BadRequestAlertException("Git operation failed: " + safeError.trim(), "appTemplate", "gitfailed");
            }
            if (!stderr.isEmpty()) {
                log.debug("git stderr: {}", stderr.replaceAll("https://[^@]+@", "https://***@"));
            }
        } catch (BadRequestAlertException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new BadRequestAlertException("Git operation failed: " + e.getMessage(), "appTemplate", "gitfailed");
        }
    }

    /**
     * Check if a branch exists in a repository.
     */
    protected boolean branchExistsInRepo(RestTemplate restTemplate, String token,
                                          String owner, String repo, String branch) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            headers.set("Accept", "application/vnd.github+json");

            restTemplate.exchange(
                GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete a branch from a repository.
     */
    protected void deleteSourceBranch(RestTemplate restTemplate, String token,
                                       String owner, String repo, String branch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/vnd.github+json");

        restTemplate.exchange(
            GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branch,
            HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }
}
