/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.neutron

import java.sql.{Connection, ResultSet, Time}

import javax.sql.DataSource

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

/** An SQL updater for the midonet_data_state table. */
class DataStateUpdater(dataSrc: DataSource) {
    private val log = LoggerFactory.getLogger(classOf[DataStateUpdater])

    /* An SQL query to update the last_processed_id in the midonet_data_state
     * table, which contains a singleton row with the fixed ID (= 1). This query
     * updates any existing rows with the specified last processed ID and the
     * current date time. It works fine since there's only 1 row as noted. */
    private val UPDATE_LAST_PROCESSED_ID =
        "UPDATE midonet_data_state SET last_processed_task_id=?, updated_at=?"

    /**
     * Updates the last_processed_id stored in the midonet_task_state table to
     * the given value.
     */
    def updateLastProcessedId(taskId: Int) {
        var con: Connection = null
        try {
            con = dataSrc.getConnection

            val stmt = con.prepareStatement(UPDATE_LAST_PROCESSED_ID)
            stmt.setInt(1, taskId)
            stmt.setTime(2, new Time(System.currentTimeMillis()))
            stmt.executeUpdate()
            stmt.close()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to update the last processed ID.", e)
        } finally {
            if (con != null) con.close()
        }
    }
}