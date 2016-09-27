package de.tum.in.www1.exerciseapp.service;

import de.tum.in.www1.exerciseapp.domain.Participation;
import de.tum.in.www1.exerciseapp.exception.BitbucketException;
import de.tum.in.www1.exerciseapp.web.rest.util.HeaderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@Service
@Transactional
@Profile("bitbucket")
public class BitbucketService implements VersionControlService {

    private final Logger log = LoggerFactory.getLogger(BitbucketService.class);

    @Value("${exerciseapp.bitbucket.url}")
    private URL BITBUCKET_URL;

    @Value("${exerciseapp.bitbucket.user}")
    private String BITBUCKET_USER;

    @Value("${exerciseapp.bitbucket.password}")
    private String BITBUCKET_PASSWORD;

    @Override
    public URL copyRepository(URL baseRepositoryUrl, String username) {
        Map<String, String> result = this.forkRepository(getProjectKeyFromUrl(baseRepositoryUrl), getRepositorySlugFromUrl(baseRepositoryUrl), username);
        try {
            return new URL(result.get("cloneUrl"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void configureRepository(URL repositoryUrl, String username) {
        this.giveWritePermission(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl), username);
    }

    @Override
    public void deleteRepository(URL repositoryUrl) {
        this.deleteRepositoryImpl(getProjectKeyFromUrl(repositoryUrl), getRepositorySlugFromUrl(repositoryUrl));
    }

    @Override
    public URL getRepositoryWebUrl(Participation participation) {
        try {
            return new URL(BITBUCKET_URL +
                "/projects/" + getProjectKeyFromUrl(participation.getRepositoryUrlAsUrl()) +
                "/repos/" + getRepositorySlugFromUrl(participation.getRepositoryUrlAsUrl()) + "/browse");
        } catch (MalformedURLException e) {
            log.error("Couldn't construct repository web URL");
        }
        return BITBUCKET_URL;
    }

    private String getProjectKeyFromUrl(URL repositoryUrl) {
        // https://ga42xab@repobruegge.in.tum.de/scm/EIST2016RME/RMEXERCISE-ga42xab.git
        return repositoryUrl.getFile().split("/")[2];
    }

    private String getRepositorySlugFromUrl(URL repositoryUrl) {
        // https://ga42xab@repobruegge.in.tum.de/scm/EIST2016RME/RMEXERCISE-ga42xab.git
        String repositorySlug = repositoryUrl.getFile().split("/")[3];
        if (repositorySlug.endsWith(".git")) {
            repositorySlug = repositorySlug.substring(0, repositorySlug.length() - 4);
        }
        return repositorySlug;
    }

    /**
     * Uses the configured Bitbucket account to fork the given repository inside the project.
     *
     * @param baseProjectKey     The project key of the base project.
     * @param baseRepositorySlug The repository slug of the base repository.
     * @param username           The user for whom the repository is being forked.
     * @return The slug of the forked repository (i.e. its identifier).
     */
    private Map<String, String> forkRepository(String baseProjectKey, String baseRepositorySlug, String username) throws BitbucketException {
        String forkName = String.format("%s-%s", baseRepositorySlug, username);
        Map<String, Object> body = new HashMap<>();
        body.put("name", forkName);
        body.put("project", new HashMap<>());
        ((Map) body.get("project")).put("key", baseProjectKey);
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(body, headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map> response = null;
        try {
            response = restTemplate.exchange(
                BITBUCKET_URL + "/rest/api/1.0/projects/" + baseProjectKey + "/repos/" + baseRepositorySlug,
                HttpMethod.POST,
                entity,
                Map.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().equals(HttpStatus.CONFLICT)) {
                log.info("Repository already exists. Going to recover repository information...");
                Map<String, String> result = new HashMap<>();
                result.put("slug", forkName);
                result.put("cloneUrl", buildCloneUrl(baseProjectKey, forkName, username).toString());
                return result;
            } else {
                throw e;
            }
        } catch (Exception e) {
            log.error("Could not fork base repository", e);
            throw new BitbucketException("Error while forking repository");
        }
        if (response != null && response.getStatusCode().equals(HttpStatus.CREATED)) {
            String slug = (String) response.getBody().get("slug");
            String cloneUrl = buildCloneUrl(baseProjectKey, forkName, username).toString();
            Map<String, String> result = new HashMap<>();
            result.put("slug", slug);
            result.put("cloneUrl", cloneUrl);
            return result;
        }
        return null;
    }

    /**
     * Gives user write permissions for a repository.
     *
     * @param projectKey     The project key of the repository's project.
     * @param repositorySlug The repository's slug.
     * @param username       The user whom to give write permissions.
     */
    private void giveWritePermission(String projectKey, String repositorySlug, String username) throws BitbucketException {
        String baseUrl = BITBUCKET_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug + "/permissions/users?name=";//NAME&PERMISSION
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        try {
            restTemplate.exchange(
                baseUrl + username + "&permission=REPO_WRITE",
                HttpMethod.PUT,
                entity, Map.class);
        } catch (Exception e) {
            log.error("Could not give write permission", e);
            throw new BitbucketException("Error while giving repository permissions");
        }
    }

    /**
     * Deletes the given repository from Bitbucket.
     *
     * @param projectKey     The project key of the repository's project.
     * @param repositorySlug The repository's slug.
     */
    private void deleteRepositoryImpl(String projectKey, String repositorySlug) {
        String baseUrl = BITBUCKET_URL + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repositorySlug;
        HttpHeaders headers = HeaderUtil.createAuthorization(BITBUCKET_USER, BITBUCKET_PASSWORD);
        HttpEntity<?> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = new RestTemplate();
        try {
            restTemplate.exchange(baseUrl, HttpMethod.DELETE, entity, Map.class);
        } catch (Exception e) {
            log.error("Could not delete repository", e);
        }
    }

    private URL buildCloneUrl(String projectKey, String repositorySlug, String username) {
        URL cloneUrl = null;
        try {
            cloneUrl = new URL(BITBUCKET_URL.getProtocol() + "://" + username + "@" + BITBUCKET_URL.getAuthority() + BITBUCKET_URL.getPath() + "/scm/" + projectKey + "/" + repositorySlug + ".git");
        } catch (MalformedURLException e) {
            log.error("Could not build clone URL", e);
        }
        return cloneUrl;
    }
}
