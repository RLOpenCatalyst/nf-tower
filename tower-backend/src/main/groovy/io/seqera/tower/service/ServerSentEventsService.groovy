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

import io.micronaut.http.sse.Event
import io.reactivex.Flowable

import java.time.Duration

interface ServerSentEventsService {

    void createFlowable(String key, Duration idleTimeout)

    void publishEvent(String key, Event data)

    Flowable getThrottledFlowable(String key, Duration throttleTime)

    Flowable generateHeartbeatFlowable(Duration interval, Closure<Event> heartbeatGenerator)

    void completeFlowable(String key)


}