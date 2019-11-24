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

package io.seqera.tower.service.progress

import java.time.Duration

import io.seqera.tower.domain.Workflow
import io.seqera.tower.exchange.progress.ProgressData
/**
 * Defines the contract for th execution progress logic
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface ProgressService extends ProgressOperations {

    @Deprecated
    ProgressData getProgressQuery(Workflow workflow)

    void checkForExpiredWorkflow(Duration expireTimeout, Duration zombieTimeout)

}
