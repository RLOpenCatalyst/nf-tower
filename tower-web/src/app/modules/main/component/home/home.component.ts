/*
 * Copyright (c) 2019, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

import {Component, OnInit} from '@angular/core';
import {User} from "../../entity/user/user";
import {AuthService} from "../../service/auth.service";
import {Router} from "@angular/router";
import {Workflow} from "../../entity/workflow/workflow";
import {WorkflowService} from "../../service/workflow.service";
import {ServerSentEventsService} from "../../service/server-sent-events.service";
import {SseError} from "../../entity/sse/sse-error";
import {Subscription} from "rxjs";
import {NotificationService} from "../../service/notification.service";
import {SseHeartbeat} from "../../entity/sse/sse-heartbeat";
import {FilteringParams} from "../../util/filtering-params";
import {concat, differenceBy, intersectionBy, orderBy} from "lodash";
import {environment} from "../../../../../environments/environment";
import {SseErrorType} from "../../entity/sse/sse-error-type";

@Component({
  selector: 'wt-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss']
})
export class HomeComponent implements OnInit {

  user: User;
  workflows: Workflow[];
  private liveEventsSubscription: Subscription;

  shouldShowLandingPage: boolean;

  searchingText: string;
  offset: number = 0;
  isSearchTriggered: boolean;
  isNextPageLoadTriggered: boolean;
  version: string;
  commitId: string;

  constructor(private authService: AuthService,
              private workflowService: WorkflowService,
              private serverSentEventsWorkflowService: ServerSentEventsService,
              private notificationService: NotificationService,
              private router: Router) {
  }


  ngOnInit() {
    this.version = environment.version;
    this.commitId = environment.commitId;

    this.authService.user$.subscribe(
      (user: User) => {
        this.user = user;
        if (!this.user) {
          this.shouldShowLandingPage = this.isAtRoot;
          return;
        }

        this.workflowService.workflows$.subscribe((workflows: Workflow[]) => {
          this.receiveWorkflows(workflows);
          this.subscribeToUserLiveEvents();
        });
      }
    )
  }

  private receiveWorkflows(emittedWorkflows: Workflow[]): void {
    this.workflows = this.isWorkflowsInitiatied ? this.workflows : [];
    const newWorkflows: Workflow[] = differenceBy(emittedWorkflows, this.workflows, (w: Workflow) => w.id);

    //Paginating event: concat the newly received workflows to the current ones
    if (this.isNextPageLoadTriggered) {
      this.workflows = concat(this.workflows, newWorkflows);
    }
    //Searching event: replace the workflows with the newly received ones from server
    else if (this.isSearchTriggered) {
      this.workflows = emittedWorkflows;
    }
    //Search is currently active: keep the filtered workflows, drop the ones no longer present (delete event) and ignore the new ones (live update event)
    else if (this.isSearchActive) {
      this.workflows = intersectionBy(this.workflows, emittedWorkflows, (workflow: Workflow) => workflow.id);
    }
    //No search currently active (initialization event, live update event, delete event)
    else {
      this.workflows = emittedWorkflows;
    }
    this.workflows = orderBy(this.workflows, [(w: Workflow) => w.data.start], ['desc']);
    this.offset = this.workflows.length;

    this.isSearchTriggered = false;
    this.isNextPageLoadTriggered = false;
  }

  private subscribeToUserLiveEvents(): void {
    if (this.liveEventsSubscription) {
      return;
    }

    this.liveEventsSubscription = this.serverSentEventsWorkflowService.connectToUserLiveStream(this.user).subscribe(
      (data: Workflow | SseHeartbeat) => this.reactToDataEvent(data),
      (error: SseError) => this.reactToErrorEvent(error)
    );
  }

  private reactToDataEvent(data: Workflow | SseHeartbeat) {
    if (data instanceof Workflow) {
      console.log('Live user event received', data);
      this.workflowService.updateWorkflow(data);
    } else if (data instanceof SseHeartbeat) {
      console.log('Heartbeat event received', data);
    }
  }

  private reactToErrorEvent(error: SseError) {
    console.log('Live user error event received', error);
    if (error.type == SseErrorType.TIMEOUT) {
      this.liveEventsSubscription = null;
      this.subscribeToUserLiveEvents();
    }
  }

  private get isAtRoot(): boolean {
    return (this.router.url == '/');
  }

  get shouldShowSidebar(): boolean {
    return (this.user && this.isAtWorkflowRelatedScreen && (this.isSearchTriggered || this.isSearchActive || this.isSomeWorkflows));
  }

  get shouldShowWelcomeMessage(): boolean {
    return (this.user && this.isAtRoot && (!this.isSearchTriggered || !this.isSearchActive || !this.isSomeWorkflows) );
  }

  get shouldShowLoadingScreen(): boolean {
    return (!this.shouldShowLandingPage && this.isAtWorkflowRelatedScreen && !this.isWorkflowsInitiatied);
  }

  get isAtWorkflowRelatedScreen() {
    return (this.isAtRoot || this.router.url.startsWith('/watch'));
  }

  get isWorkflowsInitiatied(): boolean {
    return (this.workflows != undefined);
  }

  get isSomeWorkflows(): boolean {
    return (this.isWorkflowsInitiatied && this.workflows.length > 0);
  }

  private get isSearchActive(): boolean {
    return (this.searchingText != null  && this.searchingText.length > 0);
  }

  deleteWorkflow(workflowToDelete: Workflow) {
    this.workflowService.deleteWorkflow(workflowToDelete).subscribe(
       () => {},
       (errorMessage: string) => this.notificationService.showErrorNotification(errorMessage)
    );
  }

  searchWorkflows(searchText: string) {
    this.searchingText = searchText;
    this.isSearchTriggered = true;

    this.workflowService.emitWorkflowsFromServer(new FilteringParams(10, 0, searchText), true);
  }

  onSidebarScroll(event) {
    //Check if the end of the container has been reached: https://stackoverflow.com/a/50038429
    const isScrollEndReached = (event.target.offsetHeight + event.target.scrollTop >= event.target.scrollHeight);
    if (!isScrollEndReached) {
      return;
    }

    console.log('Sidebar end reached');
    this.loadNewPage();
  }

  private loadNewPage(): void {
    if (this.isNextPageLoadTriggered) {
      return;
    }

    this.isNextPageLoadTriggered = true;
    this.workflowService.emitWorkflowsFromServer(new FilteringParams(10, this.offset, this.searchingText))
  }

}
