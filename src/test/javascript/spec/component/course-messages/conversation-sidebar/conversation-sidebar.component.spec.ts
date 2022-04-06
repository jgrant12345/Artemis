import { ComponentFixture, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { MockComponent, MockDirective, MockPipe, MockProvider } from 'ng-mocks';
import { Course } from 'app/entities/course.model';
import { CourseManagementService } from 'app/course/manage/course-management.service';
import { ArtemisTranslatePipe } from 'app/shared/pipes/artemis-translate.pipe';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { TranslateService } from '@ngx-translate/core';
import { LocalStorageService, SessionStorageService } from 'ngx-webstorage';
import { FaIconComponent } from '@fortawesome/angular-fontawesome';
import { DataTableComponent } from 'app/shared/data-table/data-table.component';
import { NgxDatatableModule } from '@swimlane/ngx-datatable';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { MockTranslateService } from '../../../helpers/mocks/service/mock-translate.service';
import { MockRouter } from '../../../helpers/mocks/mock-router';
import { MockLocalStorageService } from '../../../helpers/mocks/service/mock-local-storage.service';
import { ConversationService } from 'app/shared/metis/conversation.service';
import { MockConversationService } from '../../../helpers/mocks/service/mock-conversation.service';
import { CourseMessagesService } from 'app/shared/metis/course.messages.service';
import { ConversationSidebarComponent } from 'app/overview/course-messages/conversation-sidebar/conversation-sidebar.component';

import { conversationBetweenUser1User2, conversationsOfUser1, metisCourse, metisTutor, metisUser2 } from '../../../helpers/sample/metis-sample-data';

describe('ConversationSidebarComponent', () => {
    let component: ConversationSidebarComponent;
    let fixture: ComponentFixture<ConversationSidebarComponent>;
    let courseManagementService: CourseManagementService;
    let emitActiveConversationSpy: jest.SpyInstance;

    const id = metisCourse.id;
    const parentRoute = {
        parent: {
            params: of({ id }),
            queryParams: of({ searchText: '' }),
        },
    } as any as ActivatedRoute;
    const route = { parent: parentRoute } as any as ActivatedRoute;

    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [HttpClientTestingModule, NgxDatatableModule],
            declarations: [
                ConversationSidebarComponent,
                MockComponent(FaIconComponent),
                MockComponent(DataTableComponent),
                MockPipe(ArtemisTranslatePipe),
                MockDirective(NgbTooltip),
            ],
            providers: [
                FormBuilder,
                MockProvider(SessionStorageService),
                { provide: CourseMessagesService, useClass: CourseMessagesService },
                { provide: ConversationService, useClass: MockConversationService },
                { provide: ActivatedRoute, useValue: route },
                { provide: TranslateService, useClass: MockTranslateService },
                { provide: Router, useClass: MockRouter },
                { provide: LocalStorageService, useClass: MockLocalStorageService },
                { provide: DataTableComponent, useClass: DataTableComponent },
            ],
        })
            .compileComponents()
            .then(() => {
                courseManagementService = TestBed.inject(CourseManagementService);
                jest.spyOn(courseManagementService, 'findOneForDashboard').mockReturnValue(of({ body: metisCourse }) as Observable<HttpResponse<Course>>);
                fixture = TestBed.createComponent(ConversationSidebarComponent);
                component = fixture.componentInstance;
                emitActiveConversationSpy = jest.spyOn(component.selectConversation, 'emit');
            });
    });

    afterEach(() => {
        jest.restoreAllMocks();
    });

    it('should set course and user conversation, activeConversation on initialization and emitActiveConversation for other components', fakeAsync(() => {
        component.ngOnInit();
        tick();
        expect(component.course).toBe(metisCourse);
        expect(component.conversations).toBe(conversationsOfUser1);
        expect(component.activeConversation).toBe(conversationsOfUser1.first());
        expect(emitActiveConversationSpy).toBeCalledTimes(1);
        expect(emitActiveConversationSpy).toBeCalledWith(conversationsOfUser1.first());
    }));

    it('should search for other users via the searchbar', () => {
        const userServiceSpy = jest.spyOn(courseManagementService, 'searchOtherUsersInCourse').mockReturnValue(of(new HttpResponse({ body: [metisUser2] })));
        fixture.detectChanges();

        const search = component.searchUsersWithinCourse(of({ text: metisUser2.name!, entities: [metisUser2] }));
        fixture.detectChanges();

        // Check if the observable output matches our expectancies
        search.subscribe((a) => {
            expect(a).toEqual([{ id: metisUser2.id, login: metisUser2.login }]);
            expect(component.searchNoResults).toBeFalse();
            expect(component.searchFailed).toBeFalse();
        });

        expect(userServiceSpy).toHaveBeenCalledTimes(1);
    });

    it('should return empty if search with less than 3 characters', () => {
        const userServiceSpy = jest.spyOn(courseManagementService, 'searchOtherUsersInCourse');
        fixture.detectChanges();

        const search = component.searchUsersWithinCourse(of({ text: 'ab', entities: [] }));
        fixture.detectChanges();

        // Check if the observable output matches our expectancies
        search.subscribe((a) => {
            expect(a).toEqual([]);
            expect(component.searchNoResults).toBeFalse();
            expect(component.searchFailed).toBeFalse();
        });

        expect(userServiceSpy).toHaveBeenCalledTimes(0);
    });

    it('should set searchNoResults true when no user found while searching', () => {
        const userServiceSpy = jest.spyOn(courseManagementService, 'searchOtherUsersInCourse').mockReturnValue(of(new HttpResponse({ body: [] })));
        fixture.detectChanges();

        const search = component.searchUsersWithinCourse(of({ text: 'non-existing user name', entities: [] }));
        fixture.detectChanges();

        // Check if the observable output matches our expectancies
        search.subscribe((a) => {
            expect(a).toEqual([]);
            expect(component.searchNoResults).toBeTrue();
            expect(component.searchFailed).toBeFalse();
        });

        expect(userServiceSpy).toHaveBeenCalledTimes(1);
    });

    it('should find if there is existing conversation with searched user', fakeAsync(() => {
        component.ngOnInit();
        tick();

        const usersConversations = component.findConversationWithUser(metisUser2);
        expect(usersConversations).toEqual(conversationBetweenUser1User2);
    }));

    it('should return undefined if there is no existing conversation with searched user', fakeAsync(() => {
        component.ngOnInit();
        tick();

        const usersConversations = component.findConversationWithUser(metisTutor);
        expect(usersConversations).toBeUndefined();
    }));

    it('should handle selection of a user from the search list with existing conversation', () => {
        const spy = jest.spyOn(component, 'findConversationWithUser');

        component.conversations = conversationsOfUser1;

        // conversation already exists, activeConversation will be set and emitted for other postOverview component
        component.onAutocompleteSelect(metisUser2);
        fixture.detectChanges();

        expect(spy).toBeCalledTimes(1);
        expect(spy).toBeCalledWith(metisUser2);
        expect(component.activeConversation).toBe(component.findConversationWithUser(metisUser2));
        expect(emitActiveConversationSpy).toBeCalledTimes(1);
    });

    it('should handle selection of a user from the search list without existing conversation', () => {
        const spy = jest.spyOn(component, 'findConversationWithUser');
        component.conversations = conversationsOfUser1;

        // conversation doesn't exist, so it will be created and added to the beginning of the conversations
        component.onAutocompleteSelect(metisTutor);
        fixture.detectChanges();

        expect(spy).toBeCalledTimes(1);
        expect(spy).toBeCalledWith(metisTutor);
        expect(component.activeConversation).toBe(component.findConversationWithUser(metisTutor));
        expect(emitActiveConversationSpy).toBeCalledTimes(1);
    });

    it('should format search result', () => {
        const resultString = component.searchResultFormatter(metisUser2);
        expect(resultString).toEqual(metisUser2.name);
    });

    it('should clear search text from user', () => {
        const resultString = component.clearUserSearchBar();
        expect(resultString).toEqual('');
    });

    it('should create and initialize conversation correctly', fakeAsync(() => {
        component.ngOnInit();
        tick();

        const newConversationWithUser = component.createNewConversationWithUser(metisUser2);
        expect(newConversationWithUser.course).toBe(metisCourse);
        expect(newConversationWithUser.conversationParticipants!.first()?.user).toBe(metisUser2);
    }));
});
