/*
 * Copyright 2017 Midokura SARL
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
#include <limits>
#include <nativeFlowController.h>
#include <nativeFlowExpirationIndexer.h>

FlowExpirationIndexer::FlowExpirationIndexer() {}

void FlowExpirationIndexer::enqueue_flow_expiration(FlowId id, long long expiration, int expiration_type) {
  m_expiration_queues[expiration_type].push(std::make_pair(id, expiration));
}

FlowId FlowExpirationIndexer::poll_for_expired(long long now) {
  int type = 0;
  while (type < MAX_EXPIRATION_TYPE &&
          (m_expiration_queues[type].empty() ||
            now < m_expiration_queues[type].front().second)) {
              type++;
  }
  if (type < MAX_EXPIRATION_TYPE) {
    FlowId flow_id = m_expiration_queues[type].front().first;
    m_expiration_queues[type].pop();
    return flow_id;
  } else {
    return NULL_ID;
  }
}

FlowId FlowExpirationIndexer::evict_flow() {
  return poll_for_expired(std::numeric_limits<long long>::max());
}
