package io.splicer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BaseGitHubAppService#parseAppNameFromRepo(String)}.
 * Pure static method — no Spring context or mocking needed.
 */
class BaseGitHubAppServiceTest {

    @Test
    void parseAppNameFromRepo_httpsUrl() {
        String[] result = BaseGitHubAppService.parseAppNameFromRepo("https://github.com/owner/repo");
        assertNotNull(result);
        assertEquals("owner", result[0]);
        assertEquals("repo", result[1]);
    }

    @Test
    void parseAppNameFromRepo_httpsUrlWithDotGit() {
        String[] result = BaseGitHubAppService.parseAppNameFromRepo("https://github.com/owner/repo.git");
        assertNotNull(result);
        assertEquals("owner", result[0]);
        assertEquals("repo", result[1]);
    }

    @Test
    void parseAppNameFromRepo_httpUrl() {
        String[] result = BaseGitHubAppService.parseAppNameFromRepo("http://github.com/owner/repo");
        assertNotNull(result);
        assertEquals("owner", result[0]);
        assertEquals("repo", result[1]);
    }

    @Test
    void parseAppNameFromRepo_null() {
        assertNull(BaseGitHubAppService.parseAppNameFromRepo(null));
    }

    @Test
    void parseAppNameFromRepo_empty() {
        assertNull(BaseGitHubAppService.parseAppNameFromRepo(""));
    }

    @Test
    void parseAppNameFromRepo_singleSegment() {
        assertNull(BaseGitHubAppService.parseAppNameFromRepo("not-a-url"));
    }
}
