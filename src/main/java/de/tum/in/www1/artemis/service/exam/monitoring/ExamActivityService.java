package de.tum.in.www1.artemis.service.exam.monitoring;

import org.springframework.stereotype.Service;

import de.tum.in.www1.artemis.domain.exam.monitoring.ExamActivity;
import de.tum.in.www1.artemis.repository.ExamActivityRepository;

@Service
public class ExamActivityService {

    private final ExamActivityRepository examActivityRepository;

    public ExamActivityService(ExamActivityRepository examActivityRepository) {
        this.examActivityRepository = examActivityRepository;
    }

    public ExamActivity save(ExamActivity examActivity) {
        return this.examActivityRepository.save(examActivity);
    }
}
