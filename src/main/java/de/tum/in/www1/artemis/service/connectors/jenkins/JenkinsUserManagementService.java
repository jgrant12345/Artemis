package de.tum.in.www1.artemis.service.connectors.jenkins;

import java.io.IOException;
import java.net.URL;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.tum.in.www1.artemis.domain.User;
import de.tum.in.www1.artemis.repository.ProgrammingExerciseRepository;
import de.tum.in.www1.artemis.service.UserService;
import de.tum.in.www1.artemis.service.connectors.CIUserManagementService;
import de.tum.in.www1.artemis.service.connectors.jenkins.dto.JenkinsUpdateUserDTO;
import de.tum.in.www1.artemis.service.connectors.jenkins.dto.JenkinsUserDTO;

@Service
@Profile("jenkins")
public class JenkinsUserManagementService implements CIUserManagementService {

    private final Logger log = LoggerFactory.getLogger(JenkinsUserManagementService.class);

    @Value("${artemis.continuous-integration.url}")
    private URL jenkinsServerUrl;

    private final RestTemplate restTemplate;

    private final JenkinsJobPermissionsService jenkinsJobPermissionsService;

    private UserService userService;

    private final ProgrammingExerciseRepository programmingExerciseRepository;

    public JenkinsUserManagementService(@Qualifier("jenkinsRestTemplate") RestTemplate restTemplate, JenkinsJobPermissionsService jenkinsJobPermissionsService,
            ProgrammingExerciseRepository programmingExerciseRepository) {
        this.restTemplate = restTemplate;
        this.jenkinsJobPermissionsService = jenkinsJobPermissionsService;
        this.programmingExerciseRepository = programmingExerciseRepository;
    }

    @Autowired // break the cycle
    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    /**
     * Creates a user in Jenkins. Note that the user login acts as
     * a unique identifier in Jenkins.
     *
     * @param user The user to create
     */
    @Override
    public void createUser(User user) {
        // Only create a user if it doesn't already exist.
        if (getUser(user.getLogin()) != null) {
            throw new JenkinsException("Cannot create user: " + user.getLogin() + " because the login already exists");
        }

        // Make sure the user login contains legal characters.
        if (!isUserLoginLegal(user)) {
            throw new JenkinsException("Cannot create user: " + user.getLogin() + " because the login contains illegal characters");
        }

        try {
            // Create the Jenkins user
            var uri = UriComponentsBuilder.fromHttpUrl(jenkinsServerUrl.toString()).pathSegment("securityRealm", "createAccountByAdmin").build().toUri();
            restTemplate.exchange(uri, HttpMethod.POST, getCreateUserFormHttpEntity(user), Void.class);

            // Adds the user to groups of existing programming exercises
            addUserToGroups(user, user.getGroups());
        }
        catch (RestClientException e) {
            throw new JenkinsException("Cannot create user: " + user.getLogin(), e);
        }
    }

    /**
     * Creates an HttpEntity containing the form data required by the POST request for creating a
     * new Jenkins user.
     *
     * @param user the user to create
     * @return http entity with the user encoded as the form data.
     */
    private HttpEntity<MultiValueMap<String, String>> getCreateUserFormHttpEntity(User user) {
        var password = userService.decryptPassword(user);

        var formData = new LinkedMultiValueMap<String, String>();
        formData.add("username", user.getLogin());
        formData.add("password1", password);
        formData.add("password2", password);
        formData.add("fullname", user.getName());
        formData.add("email", user.getEmail());

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(formData, headers);
    }

    /**
     * Deletes the user from Jenkins.
     *
     * @param userLogin the login of the user to delete
     */
    @Override
    public void deleteUser(String userLogin) {
        // Only delete a user if it exists.
        if (getUser(userLogin) == null) {
            return;
        }

        try {
            var uri = UriComponentsBuilder.fromHttpUrl(jenkinsServerUrl.toString()).pathSegment("user", userLogin, "doDelete").build().toUri();
            restTemplate.exchange(uri, HttpMethod.POST, null, Void.class);
        }
        catch (RestClientException e) {
            throw new JenkinsException("Cannot delete user: " + userLogin, e);
        }
    }

    /**
     * Adds the Artemis user to a group in Jenkins. Jenkins does not support
     * groups so this function fetches all programming exercises belonging to
     * the groups and assigns the user permissions to them.
     *
     * @param user   The Artemis user to add to the group
     * @param groups The groups to add the user to
     */
    @Override
    public void addUserToGroups(User user, Set<String> groups) {
        var exercises = programmingExerciseRepository.findAllByInstructorOrTAGroupNameIn(groups);
        exercises.forEach(exercise -> {
            // The exercise's project key is also the name of the Jenkins job that groups all build plans
            // for students, solution, and template.
            var jobName = exercise.getProjectKey();
            var userLogin = user.getLogin();

            var course = exercise.getCourseViaExerciseGroupOrCourseMember();
            var instructorGroup = course.getInstructorGroupName();
            if (groups.contains(instructorGroup)) {
                try {
                    jenkinsJobPermissionsService.assignUserInstructorPermissionsForJob(userLogin, jobName);
                    return;
                }
                catch (IOException e) {
                    throw new JenkinsException("Cannot assign instructor permissions to user: " + userLogin, e);
                }
            }

            var teachingAssistantGroup = course.getTeachingAssistantGroupName();
            if (groups.contains(teachingAssistantGroup)) {
                try {
                    jenkinsJobPermissionsService.assignUserTeachingAssistantPermissionsForJob(userLogin, jobName);
                }
                catch (IOException e) {
                    throw new JenkinsException("Cannot assign teaching assistant permissions to user: " + userLogin, e);
                }
            }
        });
    }

    /**
     * Removes the Artemis user from the specified groups. Jenkins doesn't support groups so this function fetches
     * all programming exercises belonging to the groups, and revokes the user's permissions from them.
     *
     * @param user   The Artemis user to remove from the group
     * @param groups The groups to remove the user from
     */
    @Override
    public void removeUserFromGroups(User user, Set<String> groups) {
        var exercises = programmingExerciseRepository.findAllByInstructorOrTAGroupNameIn(groups);
        exercises.forEach(exercise -> {
            // The exercise's project key is also the name of the Jenkins job that groups all build plans
            // for students, solution, and template.
            var jobName = exercise.getProjectKey();
            var userLogin = user.getLogin();

            var course = exercise.getCourseViaExerciseGroupOrCourseMember();
            var instructorGroup = course.getInstructorGroupName();
            if (groups.contains(instructorGroup)) {
                try {
                    jenkinsJobPermissionsService.revokeUserInstructorPermissionsForJob(userLogin, jobName);
                    return;
                }
                catch (IOException e) {
                    throw new JenkinsException("Cannot revoke instructor permissions from user: " + userLogin, e);
                }
            }

            var teachingAssistantGroup = course.getTeachingAssistantGroupName();
            if (groups.contains(teachingAssistantGroup)) {
                try {
                    jenkinsJobPermissionsService.revokeUserTeachingAssistantPermissionsForJob(userLogin, jobName);
                }
                catch (IOException e) {
                    throw new JenkinsException("Cannot revoke teaching assistant permissions from user: " + userLogin, e);
                }
            }
        });

        // The same user can belong to a TA and instructor group. Adding the user to an instructor group
        // automatically overwrites the TA permissions. If the user is removed from the instructor group,
        // we need to re-apply TA permissions.
        addUserToGroups(user, user.getGroups());
    }

    @Override
    public void updateOrCreateUser(User user) {
        if (getUser(user.getLogin()) == null) {
            createUser(user);
        }
        else {
            updateUser(user);
        }
    }

    /**
     * Updates the user in Jenkins with the user data from Artemis.
     * <p>
     * Note that it's not possible to change the username of the Jenkins user.
     *
     * @param user The user to update.
     */
    @Override
    public void updateUser(User user) {
        // Only update a user if it exists.
        if (getUser(user.getLogin()) == null) {
            throw new JenkinsException("Cannot update user: " + user.getLogin() + " because it doesn't exist.");
        }

        var uri = UriComponentsBuilder.fromHttpUrl(jenkinsServerUrl.toString()).pathSegment("user", user.getLogin(), "configSubmit").build().toUri();
        try {
            restTemplate.exchange(uri, HttpMethod.POST, getUpdateUserFormHttpEntity(user), String.class);

        }
        catch (RestClientException | JsonProcessingException e) {
            throw new JenkinsException("Cannot update user: " + user.getLogin(), e);
        }
    }

    @Override
    public void updateUserAndGroups(User user, Set<String> groupsToAdd, Set<String> groupsToRemove) {
        updateUser(user);
        addUserToGroups(user, groupsToAdd);
        removeUserFromGroups(user, groupsToRemove);
    }

    /**
     * Creates an HttpEntity containing the form data required by the POST request for updating an
     * existing Jenkins user.
     * <p>
     * Note: This will overwrite various fields like "description, primary view, ..."
     * <p>
     * TODO: https://stackoverflow.com/questions/17716242/creating-user-in-jenkins-via-api this might help to update users correctly.
     *
     * @param user The user to update
     * @return http entity with the user encoded as the form data
     * @throws JsonProcessingException if the user can't be parsed into json.
     */
    private HttpEntity<MultiValueMap<String, String>> getUpdateUserFormHttpEntity(User user) throws JsonProcessingException {
        var password = userService.decryptPassword(user);

        var formData = new LinkedMultiValueMap<String, String>();
        formData.add("user.password", password);
        formData.add("user.password2", password);
        formData.add("_.fullName", user.getName());
        formData.add("email.address", user.getEmail());
        formData.add("_.description", "");
        formData.add("_.primaryViewName", "");
        formData.add("providerId", "default");
        formData.add("_.authorizedKeys", "");
        formData.add("insensitiveSearch", "on");
        formData.add("_.timeZoneName", "");
        formData.add("core:apply", "true");
        formData.add("json", getUpdateUserJson(user));

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return new HttpEntity<>(formData, headers);
    }

    /**
     * Returns json containing information about the user to update in Jenkins.
     * This is required in addition to the form data.
     *
     * @param user The user to update
     * @return Json for Jenkins
     * @throws JsonProcessingException when something goes wrong writing the json content.
     */
    private String getUpdateUserJson(User user) throws JsonProcessingException {
        var updateUserDto = new JenkinsUpdateUserDTO();
        updateUserDto.setFullName(user.getName());
        updateUserDto.setDescription("");
        updateUserDto.setAddress(user.getEmail());
        updateUserDto.setPrimaryViewName("");
        updateUserDto.setProviderId("default");
        updateUserDto.setPassword(user.getPassword());
        updateUserDto.setAuthorizedKeys("");
        updateUserDto.setInsensitiveSearch(true);
        updateUserDto.setTimeZoneName("");
        return new ObjectMapper().writeValueAsString(updateUserDto);
    }

    /**
     * Gets a Jenkins user or returns empty if the user wasn't found.
     *
     * @param userLogin the username of the user to look up
     * @return the user or null if the user doesn't exist
     */
    private JenkinsUserDTO getUser(String userLogin) {
        try {
            var uri = UriComponentsBuilder.fromHttpUrl(jenkinsServerUrl.toString()).pathSegment("user", userLogin, "api", "json").build().toUri();
            return restTemplate.exchange(uri, HttpMethod.GET, null, JenkinsUserDTO.class).getBody();
        }
        catch (HttpClientErrorException e) {
            if (e.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                return null;
            }

            var errorMessage = "Could not get user " + userLogin;
            log.error(errorMessage + ": " + e);
            throw new JenkinsException(errorMessage, e);
        }
    }

    /**
     * The Jenkins username acts as a unique identifier and
     * can only contain alphanumeric characters, underscore and dash
     *
     * @param user The user
     * @return whether the user login is legal or not
     */
    private boolean isUserLoginLegal(User user) {
        String regex = "^[a-zA-Z0-9_-]*$";
        return user.getLogin().matches(regex);
    }
}