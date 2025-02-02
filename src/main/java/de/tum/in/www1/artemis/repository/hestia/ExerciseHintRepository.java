package de.tum.in.www1.artemis.repository.hestia;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import de.tum.in.www1.artemis.domain.Exercise;
import de.tum.in.www1.artemis.domain.hestia.CodeHint;
import de.tum.in.www1.artemis.domain.hestia.ExerciseHint;
import de.tum.in.www1.artemis.web.rest.errors.EntityNotFoundException;

/**
 * Spring Data repository for the ExerciseHint entity.
 */
@SuppressWarnings("unused")
@Repository
public interface ExerciseHintRepository extends JpaRepository<ExerciseHint, Long> {

    @Query("""
            SELECT h
            FROM ExerciseHint h
            LEFT JOIN FETCH h.solutionEntries se
            WHERE h.id = :hintId
            """)
    Optional<ExerciseHint> findByIdWithRelations(Long hintId);

    @NotNull
    default ExerciseHint findByIdWithRelationsElseThrow(long hintId) throws EntityNotFoundException {
        return findByIdWithRelations(hintId).orElseThrow(() -> new EntityNotFoundException("Exercise Hint", hintId));
    }

    @NotNull
    default ExerciseHint findByIdElseThrow(long exerciseHintId) throws EntityNotFoundException {
        return findById(exerciseHintId).orElseThrow(() -> new EntityNotFoundException("Exercise Hint", exerciseHintId));
    }

    Set<ExerciseHint> findByExerciseId(Long exerciseId);

    @Query("""
            SELECT h
            FROM ExerciseHint h
            LEFT JOIN FETCH h.solutionEntries se
            WHERE h.exercise.id = :exerciseId
            """)
    Set<ExerciseHint> findByExerciseIdWithRelations(Long exerciseId);

    /**
     * Copies the hints of an exercise to a new target exercise by cloning the hint objects and saving them
     * resulting in new IDs for the copied hints. The contents stay the same. On top of that, all hints in the
     * problem statement of the target exercise get replaced by the new IDs.
     *
     * @param template The template exercise containing the hints that should be copied
     * @param target   The new target exercise, to which all hints should get copied to.
     * @return A map with the old hint id as a key and the new hint id as a value
     */
    default Map<Long, Long> copyExerciseHints(final Exercise template, final Exercise target) {
        final Map<Long, Long> hintIdMapping = new HashMap<>();
        // Copying non text hints is currently not supported
        target.setExerciseHints(template.getExerciseHints().stream().map(hint -> {
            ExerciseHint copiedHint;
            if (hint instanceof CodeHint) {
                copiedHint = new CodeHint();
            }
            else {
                copiedHint = new ExerciseHint();
            }

            copiedHint.setExercise(target);
            copiedHint.setContent(hint.getContent());
            copiedHint.setTitle(hint.getTitle());
            save(copiedHint);
            hintIdMapping.put(hint.getId(), copiedHint.getId());
            return copiedHint;
        }).collect(Collectors.toSet()));

        String patchedStatement = target.getProblemStatement();
        for (final var idMapping : hintIdMapping.entrySet()) {
            // Replace any old hint ID in the imported statement with the new hint ID
            // $1 --> everything before the old hint ID; $3 --> Everything after the old hint ID --> $1 newHintID $3
            final var replacement = "$1" + idMapping.getValue() + "$3";
            patchedStatement = patchedStatement.replaceAll("(\\{[^}]*)(" + idMapping.getKey() + ")([^}]*})", replacement);
        }
        target.setProblemStatement(patchedStatement);
        return hintIdMapping;
    }

    /**
     * Returns the title of the hint with the given id
     *
     * @param hintId the id of the hint
     * @return the name/title of the hint or null if the hint does not exist
     */
    @Query("""
            SELECT h.title
            FROM ExerciseHint h
            WHERE h.id = :hintId
            """)
    String getHintTitle(@Param("hintId") Long hintId);
}
