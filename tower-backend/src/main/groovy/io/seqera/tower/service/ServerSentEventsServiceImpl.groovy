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

import javax.inject.Singleton
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Value
import io.micronaut.http.sse.Event
import io.reactivex.Flowable
import io.reactivex.functions.Consumer
import io.reactivex.processors.PublishProcessor
import io.seqera.tower.exchange.trace.sse.TraceSseResponse

@Singleton
@Slf4j
class ServerSentEventsServiceImpl implements ServerSentEventsService {

    private final Map<String, PublishProcessor<Event>> flowableByKeyCache = new ConcurrentHashMap(20)

    private final Map<PublishProcessor<Event>, Flowable> heartbeats = new ConcurrentHashMap<>(20)


    @Value('${sse.time.heartbeat.user:1m}')
    Duration heartbeatUserFlowableInterval


    String getKeyForEntity(Class entityClass, def entityId) {
        "${entityClass.simpleName}-${entityId}"
    }

    Flowable getOrCreate(String key, Duration idleTimeout, Closure<Event> idleTimeoutLastEvent, Duration throttleTime) {
        synchronized (flowableByKeyCache) {
            if(flowableByKeyCache.containsKey(key)) {
                log.info("Getting flowable: ${key}")
                return flowableByKeyCache[key]
            }

            log.info("Creating flowable: ${key}")
            Flowable<Event> flowable = PublishProcessor.<Event>create()
            flowableByKeyCache[key] = flowable

            scheduleFlowableIdleTimeout(key, idleTimeout, idleTimeoutLastEvent)
            if (throttleTime) {
                flowable = flowable.throttleLatest(throttleTime.toMillis(), TimeUnit.MILLISECONDS, true)
            }

            return flowable
        }
    }

    private void scheduleFlowableIdleTimeout(String key, Duration idleTimeout, Closure<Event> idleTimeoutLastEventPayload) {
        Flowable flowable = flowableByKeyCache[key]

        Flowable timeoutFlowable = flowable.timeout(idleTimeout.toMillis(), TimeUnit.MILLISECONDS)
        timeoutFlowable.subscribe(
            {
                log.info("Data published for flowable: ${key}")
            } as Consumer,
            { Throwable t ->
                if (t instanceof TimeoutException) {
                    log.info("Idle timeout reached for flowable: ${key}")
                    if (idleTimeoutLastEventPayload) {
                        tryPublish(key, idleTimeoutLastEventPayload)
                    }
                    tryComplete(key)
                } else {
                    log.info("Unexpected error happened for id: ${key} | ${t.message}")
                }
            } as Consumer
        )
    }

    void tryPublish(String key, Closure<Event> payload) {
        Flowable flowable = flowableByKeyCache[key]
        if (flowable) {
            log.info("Publishing event for flowable: ${key}")
            flowable.onNext(payload.call())
        }
    }

    void tryComplete(String key) {
        PublishProcessor flowable = flowableByKeyCache[key]
        if (flowable) {
            log.info("Completing flowable: ${key}")
            flowable.onComplete()
            flowableByKeyCache.remove(key)
            heartbeats.remove(flowable)
        }
    }

    Flowable generateHeartbeatFlowable(Duration interval, Closure<Event> heartbeatEventGenerator) {
        Flowable.interval(interval.toMillis(), TimeUnit.MILLISECONDS)
                .map(heartbeatEventGenerator)
    }

    protected String getPublisherKey(PublishProcessor<Event> p) {
        for( Map.Entry entry : flowableByKeyCache ){
            if( entry.value == p )
                return entry.key
        }
        return null
    }

    Flowable getHeartbeatForPublisher(PublishProcessor<Event> publisher) {
        final publisherKey=getPublisherKey(publisher)

        if( heartbeats.containsKey(publisher) )
            return heartbeats.get(publisher)

        synchronized (heartbeats) {
            if( heartbeats.containsKey(publisher) )
                return heartbeats.get(publisher)

            Flowable flowable = generateHeartbeatFlowable(heartbeatUserFlowableInterval, {
                log.info("Server heartbeat ${it} generated for flowable: ${publisherKey}")
                Event.of(TraceSseResponse.ofHeartbeat("Server heartbeat [${publisherKey}]"))
            })

            final result = publisher
                    .mergeWith(flowable)
                    .takeUntil(publisher.takeLast(1))

            heartbeats.put(publisher, result)
            return result
        }
    }

}
