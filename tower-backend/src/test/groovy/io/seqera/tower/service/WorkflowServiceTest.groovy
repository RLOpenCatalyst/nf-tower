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

package io.seqera.tower.service

import grails.gorm.transactions.Transactional
import io.micronaut.test.annotation.MicronautTest
import io.seqera.tower.Application
import io.seqera.tower.domain.SummaryEntry
import io.seqera.tower.domain.User
import io.seqera.tower.domain.Workflow
import io.seqera.tower.exceptions.NonExistingWorkflowException
import io.seqera.tower.exchange.trace.TraceWorkflowRequest
import io.seqera.tower.util.AbstractContainerBaseTest
import io.seqera.tower.util.DomainCreator
import io.seqera.tower.util.TracesJsonBank
import io.seqera.tower.util.WorkflowTraceSnapshotStatus

import javax.inject.Inject

@MicronautTest(application = Application.class)
@Transactional
class WorkflowServiceTest extends AbstractContainerBaseTest {

    @Inject
    WorkflowService workflowService


    void "start a workflow given a started trace"() {
        given: "a workflow JSON started trace"
        TraceWorkflowRequest workflowStartedTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', null, WorkflowTraceSnapshotStatus.STARTED)

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflow
        Workflow.withNewTransaction {
            workflow = workflowService.processTraceWorkflowRequest(workflowStartedTraceJson, owner)
        }

        then: "the workflow has been correctly saved"
        workflow.id
        workflow.owner
        workflow.checkIsStarted()
        workflow.submit
        !workflow.complete
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }

        and: "there is no progress info"
        !workflow.tasksProgress
    }

    void "start a workflow given a started trace, then complete the workflow given a succeeded trace"() {
        given: "a workflow JSON started trace"
        TraceWorkflowRequest workflowStartedTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', null, WorkflowTraceSnapshotStatus.STARTED)

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflowStarted
        Workflow.withNewTransaction {
            workflowStarted = workflowService.processTraceWorkflowRequest(workflowStartedTraceJson, owner)
        }

        then: "the workflow has been correctly saved"
        workflowStarted.id
        workflowStarted.owner
        workflowStarted.checkIsStarted()
        workflowStarted.submit
        !workflowStarted.complete

        when: "given a workflow succeeded trace, unmarshall the succeeded JSON to a workflow"
        TraceWorkflowRequest workflowSucceededTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', workflowStarted.id, WorkflowTraceSnapshotStatus.SUCCEEDED)
        Workflow workflowSucceeded
        Workflow.withNewTransaction {
            workflowSucceeded = workflowService.processTraceWorkflowRequest(workflowSucceededTraceJson, owner)
        }

        then: "the workflow has been completed"
        workflowSucceeded.id == workflowStarted.id
        workflowSucceeded.checkIsSucceeded()
        workflowSucceeded.submit
        workflowSucceeded.complete
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }

        and: "there is summary info"
        workflowSucceeded.summaryEntries.size() == 1
        workflowSucceeded.summaryEntries.first().process == 'sayHello'
        workflowSucceeded.summaryEntries.first().cpu
        workflowSucceeded.summaryEntries.first().time
        workflowSucceeded.summaryEntries.first().reads
        workflowSucceeded.summaryEntries.first().writes
        workflowSucceeded.summaryEntries.first().cpuUsage
        SummaryEntry.withNewTransaction {
            SummaryEntry.count() == 1
        }

        and: "the tasks progress info has been computed"
        workflowSucceeded.tasksProgress.running == 0
        workflowSucceeded.tasksProgress.submitted == 0
        workflowSucceeded.tasksProgress.failed == 0
        workflowSucceeded.tasksProgress.pending == 0
        workflowSucceeded.tasksProgress.succeeded == 0
        workflowSucceeded.tasksProgress.cached == 0
    }

    void "start a workflow given a started trace, then complete the workflow given a failed trace"() {
        given: "a workflow JSON started trace"
        TraceWorkflowRequest workflowStartedTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', null, WorkflowTraceSnapshotStatus.STARTED)

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflowStarted
        Workflow.withNewTransaction {
            workflowStarted = workflowService.processTraceWorkflowRequest(workflowStartedTraceJson, owner)
        }

        then: "the workflow has been correctly saved"
        workflowStarted.id
        workflowStarted.owner
        workflowStarted.checkIsStarted()
        workflowStarted.submit
        !workflowStarted.complete
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }

        when: "given a workflow failed trace, unmarshall the failed JSON to a workflow"
        TraceWorkflowRequest workflowFailedTraceJson = TracesJsonBank.extractWorkflowJsonTrace('failed', workflowStarted.id, WorkflowTraceSnapshotStatus.FAILED)
        Workflow workflowFailed
        Workflow.withNewTransaction {
            workflowFailed = workflowService.processTraceWorkflowRequest(workflowFailedTraceJson, owner)
        }

        then: "the workflow has been completed"
        workflowFailed.id == workflowStarted.id
        workflowFailed.checkIsFailed()
        workflowFailed.submit
        workflowFailed.complete
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }

        and: "there is summary info"
        workflowFailed.summaryEntries.size() == 1
        workflowFailed.summaryEntries.first().process == 'sayHello'
        workflowFailed.summaryEntries.first().cpu
        workflowFailed.summaryEntries.first().time
        workflowFailed.summaryEntries.first().reads
        workflowFailed.summaryEntries.first().writes
        workflowFailed.summaryEntries.first().cpuUsage
        SummaryEntry.withNewTransaction {
            SummaryEntry.count() == 1
        }

        and: "the progress info has been computed"
        workflowFailed.tasksProgress.running == 0
        workflowFailed.tasksProgress.submitted == 0
        workflowFailed.tasksProgress.failed == 0
        workflowFailed.tasksProgress.pending == 0
        workflowFailed.tasksProgress.succeeded == 0
        workflowFailed.tasksProgress.cached == 0
    }

    void "start a workflow given a started trace, then try to start the same one"() {
        given: "a workflow JSON started trace"
        TraceWorkflowRequest workflowStarted1TraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', null, WorkflowTraceSnapshotStatus.STARTED)

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflowStarted1
        Workflow.withNewTransaction {
            workflowStarted1 = workflowService.processTraceWorkflowRequest(workflowStarted1TraceJson, owner)
        }

        then: "the workflow has been correctly saved"
        workflowStarted1.id
        workflowStarted1.owner
        workflowStarted1.checkIsStarted()
        workflowStarted1.submit
        !workflowStarted1.complete
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }

        when: "given a workflow started trace with the same workflowId, unmarshall the started JSON to a second workflow"
        TraceWorkflowRequest workflowStarted2TraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', workflowStarted1.id, WorkflowTraceSnapshotStatus.STARTED)
        Workflow workflowStarted2
        Workflow.withNewTransaction {
            workflowStarted2 = workflowService.processTraceWorkflowRequest(workflowStarted2TraceJson, owner)
        }

        then: "the second workflow is treated as a new one, and sessionId/runName combination cannot be repeated"
        workflowStarted2.errors.getFieldError('sessionId').code == 'unique'
        Workflow.withNewTransaction {
            Workflow.count() == 1
        }
    }

    void "try to start a workflow given a started trace without sessionId"() {
        given: "a workflow JSON started trace without sessionId"
        TraceWorkflowRequest workflowStartedTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', null, WorkflowTraceSnapshotStatus.STARTED)
        workflowStartedTraceJson.workflow.sessionId = null

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflowStarted
        Workflow.withNewTransaction {
            workflowStarted = workflowService.processTraceWorkflowRequest(workflowStartedTraceJson, owner)
        }

        then: "the workflow has validation errors"
        workflowStarted.hasErrors()
        workflowStarted.errors.getFieldError('sessionId').code == 'nullable'
        Workflow.withNewTransaction {
            Workflow.count() == 0
        }
    }

    void "try to complete a workflow given a succeeded trace for a non existing workflow"() {
        given: "a workflow JSON started trace"
        TraceWorkflowRequest workflowSucceededTraceJson = TracesJsonBank.extractWorkflowJsonTrace('success', 123, WorkflowTraceSnapshotStatus.SUCCEEDED)

        and: 'a user owner for the workflow'
        User owner = new DomainCreator().createUser()

        when: "unmarshall the JSON to a workflow"
        Workflow workflowSucceeded
        Workflow.withNewTransaction {
            workflowSucceeded = workflowService.processTraceWorkflowRequest(workflowSucceededTraceJson, owner)
        }

        then: "the workflow has been correctly saved"
        thrown(NonExistingWorkflowException)
    }

    void 'delete a workflow'() {
        given: 'a workflow with some summary entries'
        DomainCreator domainCreator = new DomainCreator()
        Workflow workflow = domainCreator.createWorkflow(summaryEntries: [domainCreator.createSummaryEntry(), domainCreator.createSummaryEntry()])

        and: 'some tasks associated with the workflow'
        (1..3).each {
            domainCreator.createTask(taskId: it, workflow: workflow)
        }

        when: 'delete the workflow'
        Workflow.withNewTransaction {
            workflowService.delete(workflow.refresh())
        }

        then: 'the workflow is no longer in the database'
        Workflow.withNewTransaction {
            Workflow.count() == 0
        }
    }

}