package de.tum.in.www1.artemis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;

import de.tum.in.www1.artemis.domain.Course;
import de.tum.in.www1.artemis.domain.TextExercise;
import de.tum.in.www1.artemis.domain.plagiarism.PlagiarismComparison;
import de.tum.in.www1.artemis.domain.plagiarism.PlagiarismStatus;
import de.tum.in.www1.artemis.domain.plagiarism.text.TextPlagiarismResult;
import de.tum.in.www1.artemis.domain.plagiarism.text.TextSubmissionElement;
import de.tum.in.www1.artemis.repository.*;
import de.tum.in.www1.artemis.repository.plagiarism.PlagiarismComparisonRepository;

public class PlagiarismIntegrationTest extends AbstractSpringIntegrationBambooBitbucketJiraTest {

    @Autowired
    private PlagiarismComparisonRepository plagiarismComparisonRepository;

    @Autowired
    private TextExerciseRepository textExerciseRepository;

    @BeforeEach
    public void initTestCase() {
        database.addUsers(1, 1, 1, 1);
    }

    @AfterEach
    public void tearDown() {
        database.resetDatabase();
    }

    @Test
    @WithMockUser(username = "user1", roles = "User")
    public void testUpdatePlagiarismComparisonStatus_forbidden_student() {

    }

    @Test
    @WithMockUser(username = "tutor1", roles = "TA")
    public void testUpdatePlagiarismComparisonStatus_forbidden_tutor() {
    }

    @Test
    @WithMockUser(username = "user1", roles = "Editor")
    public void testUpdatePlagiarismComparisonStatus() throws Exception {
    }

    @Test
    @WithMockUser(username = "user1", roles = "User")
    public void testGetPlagiarismComparisonsFoSplitView_student() throws Exception {
        Course course = database.addCourseWithOneFinishedTextExercise();
        TextExercise textExercise = textExerciseRepository.findByCourseIdWithCategories(course.getId()).get(0);
        TextPlagiarismResult textPlagiarismResult = database.createTextPlagiarismResultForExercise(textExercise);
        PlagiarismComparison<TextSubmissionElement> plagiarismComparison1 = new PlagiarismComparison<>();
        plagiarismComparison1.setPlagiarismResult(textPlagiarismResult);
        plagiarismComparison1.setStatus(PlagiarismStatus.CONFIRMED);
        var savedComparison = plagiarismComparisonRepository.save(plagiarismComparison1);
        var comparison = request.get("/api/courses/" + course.getId() + "/plagiarism-comparisons/" + savedComparison.getId() + "/for-split-view", HttpStatus.OK,
                plagiarismComparison1.getClass());
        assertThat(comparison.getPlagiarismResult()).isEqualTo(textPlagiarismResult);
    }

    @Test
    @WithMockUser(username = "user1", roles = "User")
    public void testGetPlagiarismComparisonsFoSplitView_editor() throws Exception {
        Course course = database.addCourseWithOneFinishedTextExercise();
        TextExercise textExercise = textExerciseRepository.findByCourseIdWithCategories(course.getId()).get(0);
        TextPlagiarismResult textPlagiarismResult = database.createTextPlagiarismResultForExercise(textExercise);
        PlagiarismComparison<TextSubmissionElement> plagiarismComparison1 = new PlagiarismComparison<>();
        plagiarismComparison1.setPlagiarismResult(textPlagiarismResult);
        plagiarismComparison1.setStatus(PlagiarismStatus.CONFIRMED);
        var savedComparison = plagiarismComparisonRepository.save(plagiarismComparison1);

        var comparison = request.get("/api/courses/" + course.getId() + "/plagiarism-comparisons/" + savedComparison.getId() + "/for-split-view", HttpStatus.OK,
                plagiarismComparison1.getClass());
        assertThat(comparison.getPlagiarismResult()).isEqualTo(textPlagiarismResult);
    }
}
