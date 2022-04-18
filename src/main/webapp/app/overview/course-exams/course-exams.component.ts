import { Component, OnInit, OnDestroy } from '@angular/core';
import { Course } from 'app/entities/course.model';
import { CourseScoreCalculationService } from 'app/overview/course-score-calculation.service';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { Exam } from 'app/entities/exam.model';
import dayjs from 'dayjs/esm';
import { CourseManagementService } from 'app/course/manage/course-management.service';
import { ArtemisServerDateService } from 'app/shared/server-date.service';
import { StudentExam } from 'app/entities/student-exam.model';
import { ExamParticipationService } from 'app/exam/participate/exam-participation.service';

@Component({
    selector: 'jhi-course-exams',
    templateUrl: './course-exams.component.html',
})
export class CourseExamsComponent implements OnInit, OnDestroy {
    courseId: number;
    public course?: Course;
    private paramSubscription?: Subscription;
    private courseUpdatesSubscription?: Subscription;
    private studentExamTestExamUpdateSubscription?: Subscription;
    private studentExams: StudentExam[];

    constructor(
        private route: ActivatedRoute,
        private courseCalculationService: CourseScoreCalculationService,
        private courseManagementService: CourseManagementService,
        private serverDateService: ArtemisServerDateService,
        private examParticipationService: ExamParticipationService,
    ) {}

    /**
     * subscribe to changes in the course and fetch course by the path parameter
     */
    ngOnInit(): void {
        this.paramSubscription = this.route.parent!.params.subscribe((params) => {
            this.courseId = parseInt(params['courseId'], 10);
        });

        this.course = this.courseCalculationService.getCourse(this.courseId);

        this.courseUpdatesSubscription = this.courseManagementService.getCourseUpdates(this.courseId).subscribe((course: Course) => {
            this.courseCalculationService.updateCourse(course);
            this.course = this.courseCalculationService.getCourse(this.courseId);
        });

        this.studentExamTestExamUpdateSubscription = this.examParticipationService
            .loadStudentExamsForTestExamsPerCourseAndPerUserForOverviewPage(this.courseId)
            .subscribe((response: StudentExam[]) => {
                this.studentExams = response!;
            });
    }

    /**
     * unsubscribe from all unsubscriptions
     */
    ngOnDestroy(): void {
        if (this.paramSubscription) {
            this.paramSubscription.unsubscribe();
        }
        if (this.courseUpdatesSubscription) {
            this.courseUpdatesSubscription.unsubscribe();
        }
        if (this.studentExamTestExamUpdateSubscription) {
            this.studentExamTestExamUpdateSubscription.unsubscribe();
        }
    }

    /**
     * check for given exam if it is visible
     * @param {Exam} exam
     */
    isVisible(exam: Exam): boolean {
        return exam.visibleDate ? dayjs(exam.visibleDate).isBefore(this.serverDateService.now()) : false;
    }

    /**
     * Returns if the given exam is a TestExam
     * @param exam the exam to be checked
     */
    isTestExam(exam: Exam): boolean {
        return exam.testExam ? exam.testExam : false;
    }

    /**
     * Filters the studentExams for the examId and sorts them according to the studentExam.id in reverse order
     * @param examId the examId for which the StudentExams should be retrieved
     */
    getStudentExamForExamIdOrderedByIdReverse(examId: number): StudentExam[] {
        if (!this.studentExams) {
            return [];
        }
        return this.studentExams
            .filter(function (studentExam) {
                return studentExam.exam?.id && studentExam.startedDate && studentExam.exam.id === examId && studentExam.startedDate;
            })
            .sort((se1, se2) => {
                if (se1.id! > se2.id!) {
                    return -1;
                }
                if (se1.id! < se2.id!) {
                    return 1;
                }
                return 0;
            });
    }
}
