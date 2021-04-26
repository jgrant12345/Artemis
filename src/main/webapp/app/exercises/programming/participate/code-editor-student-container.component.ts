import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { Observable, of } from 'rxjs';
import { Subscription } from 'rxjs/Subscription';
import { catchError, flatMap, map, switchMap, tap } from 'rxjs/operators';
import * as moment from 'moment';
import { ProgrammingExerciseParticipationService } from 'app/exercises/programming/manage/services/programming-exercise-participation.service';
import { GuidedTourService } from 'app/guided-tour/guided-tour.service';
import { codeEditorTour } from 'app/guided-tour/tours/code-editor-tour';
import { ButtonSize } from 'app/shared/components/button.component';
import { ResultService } from 'app/exercises/shared/result/result.service';
import { DomainService } from 'app/exercises/programming/shared/code-editor/service/code-editor-domain.service';
import { ExerciseType, IncludedInOverallScore } from 'app/entities/exercise.model';
import { AssessmentType } from 'app/entities/assessment-type.model';
import { StudentParticipation } from 'app/entities/participation/student-participation.model';
import { Result } from 'app/entities/result.model';
import { Feedback, FeedbackType } from 'app/entities/feedback.model';
import { ProgrammingExercise } from 'app/entities/programming-exercise.model';
import { DomainType } from 'app/exercises/programming/shared/code-editor/model/code-editor.model';
import { ExerciseHint } from 'app/entities/exercise-hint.model';
import { ExerciseHintService } from 'app/exercises/shared/exercise-hint/manage/exercise-hint.service';
import { ActivatedRoute } from '@angular/router';
import { CodeEditorContainerComponent } from 'app/exercises/programming/shared/code-editor/container/code-editor-container.component';
import { ComponentCanDeactivate } from 'app/shared/guard/can-deactivate.model';
import { ProgrammingExerciseStudentParticipation } from 'app/entities/participation/programming-exercise-student-participation.model';
import { getUnreferencedFeedback } from 'app/exercises/shared/result/result-utils';
import { SubmissionType } from 'app/entities/submission.model';

@Component({
    selector: 'jhi-code-editor-student',
    templateUrl: './code-editor-student-container.component.html',
})
export class CodeEditorStudentContainerComponent implements OnInit, OnDestroy, ComponentCanDeactivate {
    @ViewChild(CodeEditorContainerComponent, { static: false }) codeEditorContainer: CodeEditorContainerComponent;
    readonly IncludedInOverallScore = IncludedInOverallScore;

    ButtonSize = ButtonSize;
    PROGRAMMING = ExerciseType.PROGRAMMING;

    paramSub: Subscription;
    participation: StudentParticipation;
    exercise: ProgrammingExercise;

    // Fatal error state: when the participation can't be retrieved, the code editor is unusable for the student
    loadingParticipation = false;
    participationCouldNotBeFetched = false;
    repositoryIsLocked = false;
    showEditorInstructions = true;
    latestResult: Result | undefined;
    hasTutorAssessment = false;
    isIllegalSubmission = false;

    constructor(
        private resultService: ResultService,
        private domainService: DomainService,
        private programmingExerciseParticipationService: ProgrammingExerciseParticipationService,
        private guidedTourService: GuidedTourService,
        private exerciseHintService: ExerciseHintService,
        private route: ActivatedRoute,
    ) {}

    /**
     * On init set up the route param subscription.
     * Will load the participation according to participation Id with the latest result and result details.
     */
    ngOnInit(): void {
        this.paramSub = this.route!.params.subscribe((params) => {
            this.loadingParticipation = true;
            this.participationCouldNotBeFetched = false;
            const participationId = Number(params['participationId']);
            this.loadParticipationWithLatestResult(participationId)
                .pipe(
                    tap((participationWithResults) => {
                        this.domainService.setDomain([DomainType.PARTICIPATION, participationWithResults]);
                        this.participation = participationWithResults;
                        this.exercise = this.participation.exercise as ProgrammingExercise;
                        // We lock the repository when the buildAndTestAfterDueDate is set and the due date has passed or if they require manual assessment.
                        // (this should match ProgrammingExerciseParticipation.isLocked on the server-side)
                        const dueDateHasPassed = !this.exercise.dueDate || moment(this.exercise.dueDate).isBefore(moment());
                        const isEditingAfterDueAllowed = !this.exercise.buildAndTestStudentSubmissionsAfterDueDate && this.exercise.assessmentType === AssessmentType.AUTOMATIC;
                        this.repositoryIsLocked = !isEditingAfterDueAllowed && !!this.exercise.dueDate && dueDateHasPassed;
                        this.latestResult = this.participation.results ? this.participation.results[0] : undefined;
                        this.isIllegalSubmission = this.latestResult?.submission?.type === SubmissionType.ILLEGAL;
                        this.checkForTutorAssessment(dueDateHasPassed);
                    }),
                    switchMap(() => {
                        return this.loadExerciseHints();
                    }),
                )
                .subscribe(
                    (exerciseHints: ExerciseHint[]) => {
                        this.exercise.exerciseHints = exerciseHints;
                        this.loadingParticipation = false;
                        this.guidedTourService.enableTourForExercise(this.exercise, codeEditorTour, true);
                    },
                    () => {
                        this.participationCouldNotBeFetched = true;
                        this.loadingParticipation = false;
                    },
                );
        });
    }

    /**
     * If a subscription exists for paramSub, unsubscribe
     */
    ngOnDestroy() {
        if (this.paramSub) {
            this.paramSub.unsubscribe();
        }
    }

    /**
     * Load exercise hints. Take them from the exercise if available.
     */
    private loadExerciseHints() {
        if (!this.exercise.exerciseHints) {
            return this.exerciseHintService.findByExerciseId(this.exercise.id!).pipe(map(({ body }) => body || []));
        }
        return of(this.exercise.exerciseHints);
    }

    /**
     * Load the participation from server with the latest result.
     * @param participationId
     */
    loadParticipationWithLatestResult(participationId: number): Observable<StudentParticipation> {
        return this.programmingExerciseParticipationService.getStudentParticipationWithLatestResult(participationId).pipe(
            flatMap((participation: ProgrammingExerciseStudentParticipation) =>
                participation.results?.length
                    ? this.loadResultDetails(participation.results[0]).pipe(
                          map((feedbacks) => {
                              participation.results![0].feedbacks = feedbacks;
                              return participation;
                          }),
                          catchError(() => Observable.of(participation)),
                      )
                    : Observable.of(participation),
            ),
        );
    }

    /**
     * Fetches details for the result (if we received one) and attach them to the result.
     * Mutates the input parameter result.
     */
    loadResultDetails(result: Result): Observable<Feedback[]> {
        return this.resultService.getFeedbackDetailsForResult(result.id!).pipe(
            map((res) => {
                return res.body || [];
            }),
        );
    }

    canDeactivate() {
        return this.codeEditorContainer.canDeactivate();
    }

    checkForTutorAssessment(dueDateHasPassed: boolean) {
        let isManualResult = false;
        let hasTutorFeedback = false;
        if (!!this.latestResult) {
            // latest result is the first element of results, see loadParticipationWithLatestResult
            isManualResult = Result.isManualResult(this.latestResult);
            if (isManualResult) {
                hasTutorFeedback = this.latestResult.feedbacks!.some((feedback) => feedback.type === FeedbackType.MANUAL);
            }
        }
        // Also check for assessment due date to never show manual feedback before the deadline
        this.hasTutorAssessment = dueDateHasPassed && isManualResult && hasTutorFeedback;
    }

    /**
     * Check whether or not a latestResult exists and if, returns the unreferenced feedback of it
     */
    get unreferencedFeedback(): Feedback[] {
        if (this.latestResult) {
            return getUnreferencedFeedback(this.latestResult.feedbacks) ?? [];
        }
        return [];
    }
}