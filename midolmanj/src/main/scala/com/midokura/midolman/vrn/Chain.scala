/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.vrn

import java.util.UUID
import com.midokura.midolman.rules.Rule

class Chain(val id: UUID, val rules: List[Rule],
            val jumpTargets: Map[UUID, Chain]) {

}
