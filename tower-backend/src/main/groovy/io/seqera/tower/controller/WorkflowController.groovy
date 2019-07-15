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

package io.seqera.tower.controller

import grails.gorm.PagedResultList
import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j
import io.micronaut.http.HttpParameters
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.seqera.tower.domain.Task
import io.seqera.tower.domain.Workflow
import io.seqera.tower.exchange.task.TaskGet
import io.seqera.tower.exchange.task.TaskList
import io.seqera.tower.exchange.workflow.WorkflowGet
import io.seqera.tower.exchange.workflow.WorkflowList
import io.seqera.tower.service.ProgressService
import io.seqera.tower.service.TaskService
import io.seqera.tower.service.UserService
import io.seqera.tower.service.WorkflowService

import javax.inject.Inject

/**
 * Implements the `workflow` API
 */
@Controller("/workflow")
@Slf4j
class WorkflowController {

    WorkflowService workflowService
    TaskService taskService
    ProgressService progressService
    UserService userService

    @Inject
    WorkflowController(WorkflowService workflowService, TaskService taskService, ProgressService progressService, UserService userService) {
        this.workflowService = workflowService
        this.taskService = taskService
        this.progressService = progressService
        this.userService = userService
    }


    @Get("/list")
    @Transactional
    @Secured(['ROLE_USER'])
    HttpResponse<WorkflowList> list(Authentication authentication) {
        List<Workflow> workflows = workflowService.list(userService.getFromAuthData(authentication))

        List<WorkflowGet> result = workflows.collect { Workflow workflow ->
            WorkflowGet.of(workflow)
        }
        HttpResponse.ok(WorkflowList.of(result))
    }

    @Get("/{id}")
    @Transactional
    @Secured(SecurityRule.IS_ANONYMOUS)
    HttpResponse<WorkflowGet> get(Long id) {
        Workflow workflow = workflowService.get(id)

        if (!workflow) {
            return HttpResponse.notFound()
        }
        HttpResponse.ok(progressService.buildWorkflowGet(workflow))
    }

    @Get("/{workflowId}/tasks")
    @Transactional
    @Secured(SecurityRule.IS_ANONYMOUS)
    HttpResponse<TaskList> tasks(Long workflowId, HttpParameters filterParams) {
        Long max = filterParams.getFirst('length', Long.class, 10l)
        Long offset = filterParams.getFirst('start', Long.class, 0l)
        String orderProperty = filterParams.getFirst('order[0][column]', String.class, 'taskId')
        String orderDir = filterParams.getFirst('order[0][dir]', String.class, 'asc')

        String search = filterParams.getFirst('search', String.class, '')
        String searchRegex = search.contains('*') ? search.replaceAll(/\*/, '%') : "${search}%"

        PagedResultList<Task> taskPagedResultList = taskService.findTasks(workflowId, max, offset, orderProperty, orderDir, searchRegex)

        List<TaskGet> result = taskPagedResultList.collect {
            TaskGet.of(it)
        }
        HttpResponse.ok(TaskList.of(result, taskPagedResultList.totalCount))
    }

}