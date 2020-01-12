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

import javax.inject.Inject

import grails.gorm.transactions.Transactional
import io.micronaut.context.annotation.Property
import io.micronaut.test.annotation.MicronautTest
import io.seqera.tower.Application
import io.seqera.tower.domain.Task
import io.seqera.tower.exchange.trace.TraceProgressData
import io.seqera.tower.exchange.trace.TraceProgressDetail
import io.seqera.tower.exchange.trace.TraceRecordRequest
import io.seqera.tower.service.progress.ProgressService
import io.seqera.tower.service.progress.ProgressStore
import io.seqera.tower.util.AbstractContainerBaseTest
import io.seqera.tower.util.DomainCreator
import io.seqera.tower.util.DomainHelper
import io.seqera.tower.util.TaskTraceSnapshotStatus
import io.seqera.tower.util.TracesJsonBank
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Property(name = "trace.tasks.buffer.time", value = "100ms")
@Transactional
@MicronautTest(application = Application.class)
class TraceServiceTest2 extends AbstractContainerBaseTest {

    @Inject ProgressStore progressStore
    @Inject ProgressService progressService
    @Inject TaskService taskService
    
    // under test
    @Inject TraceServiceImpl traceService

    void 'process a task trace and progress data' () {
        given:
        def domain = new DomainCreator()
        def WORKFLOW_ID = 'xyz'
        def session = UUID.randomUUID().toString()
        // see file `src/test/resources/workflow_success/2_task_1_submitted.json`
        def HASH1 = '2e/a112fb'
        def TASK1 = 1L

        // ----------------------------------------
        // request for a task with SUBMITTED status
        // ----------------------------------------
        def wf = domain.createWorkflow(id: WORKFLOW_ID, sessionId: session)
        and:
        def req = TracesJsonBank.extractTraceRecord('success', TASK1, wf.id, TaskTraceSnapshotStatus.SUBMITTED)
        def prog = new TraceProgressData(running: 1, succeeded: 2, processes: [new TraceProgressDetail(index: 1, name: 'foo', succeeded: 1), new TraceProgressDetail(index: 2, name:'bar', running: 1) ])
        req.progress = prog
        and:
        def taskProcessorTest = (traceService).taskProcessor.test()

        when:
        traceService.handleTaskTrace(req)
        then:
        progressStore.getTraceData(WORKFLOW_ID) == req.progress
        and:
        taskProcessorTest.assertValueCount(1)
        taskProcessorTest.values()[0].task.hash == HASH1
        taskProcessorTest.values()[0].task.taskId == TASK1
        taskProcessorTest.values()[0].workflow.id == WORKFLOW_ID

        and:
        // task was saved
        sleep 300
        Task.withNewTransaction {Task.findByWorkflowAndTaskId(wf, TASK1)}.hash == HASH1

        and:
        with(progressStore.getWorkflowLoad(WORKFLOW_ID)) {
            executors == ['aws-batch']
            // are all zero because the task status was not completed
            cpus == 0
            cpuLoad == 0
            memoryRss == 0
            memoryRss == 0
            readBytes == 0
            writeBytes == 0
            volCtxSwitch == 0
            invCtxSwitch == 0
        }

        and:
        with(progressService.getProgressData(WORKFLOW_ID).workflowProgress) {
            running == prog.running
            pending == prog.pending
            submitted == prog.submitted
            succeeded == prog.succeeded
            failed == prog.failed
            cached == prog.cached
            loadCpus == prog.loadCpus
            loadMemory == prog.loadMemory
        }
    }

    def 'should aggregate complete tasks' () {
        given:
        def session = UUID.randomUUID().toString()
        def domain = new DomainCreator()
        def HASH1 = '2e/a112fb'
        def HASH2 = '56/c2d5c7'
        def TASK1 = 1L
        def TASK2 = 2L

        and:
        def req = DomainHelper.mapper.readValue(new File('src/test/resources/trace/trace_service.json'), TraceRecordRequest)
        and:
        def WORKFLOW_ID = req.workflowId
        def wf = domain.createWorkflow(id: WORKFLOW_ID, sessionId: session)
        and:
        def prog = new TraceProgressData(running: 0, succeeded: 3, processes: [new TraceProgressDetail(index: 1, name: 'foo', succeeded: 1), new TraceProgressDetail(index: 2, name:'bar', succeeded: 2) ])
        req.progress = prog
        req.workflowId = WORKFLOW_ID
        assert req.tasks.size() == 2
        and:
        def taskProcessorTest = (traceService).taskProcessor.test()

        // ----------------------------------------
        // request for a task with SUCCEEDED status
        // ----------------------------------------
        when:
        traceService.handleTaskTrace(req)

        then:
        taskProcessorTest.valueCount() == 2

        and:
        with(progressStore.getTraceData(WORKFLOW_ID)) {
            running == 0
            succeeded == 3
        }
        and:
        with(progressStore.getTraceData(WORKFLOW_ID).processes[0]) {
            index == 1
            name == 'foo'
            succeeded == 1
        }
        and:
        with(progressStore.getTraceData(WORKFLOW_ID).processes[1]) {
            index == 2
            name == 'bar'
            succeeded == 2
        }

        when:
        sleep 300
        then:
        Task.withNewTransaction {Task.findByWorkflowAndTaskId(wf, TASK1)}.hash == HASH1
        Task.withNewTransaction {Task.findByWorkflowAndTaskId(wf, TASK2)}.hash == HASH2

        when:
        sleep 300
        then:
        with(progressStore.getWorkflowLoad(WORKFLOW_ID)) {
            executors == ['aws-batch']
            // are all zero because the task status was not completed
            cpus == req.tasks*.cpus.sum()
            cpuTime == req.tasks.collect { Task task -> return task.cpus*task.realtime }.sum()
            cpuLoad == req.tasks.collect { Task task -> return (task.pcpu/100 as float) * task.realtime as long }.sum()
            memoryReq == req.tasks*.memory.sum()
            memoryRss == req.tasks*.peakRss.sum()
            readBytes == req.tasks*.rchar.sum()
            writeBytes == req.tasks*.wchar.sum()
            volCtxSwitch == req.tasks*.volCtxt.sum()
            invCtxSwitch == req.tasks*.invCtxt.sum()
        }

    }

}
