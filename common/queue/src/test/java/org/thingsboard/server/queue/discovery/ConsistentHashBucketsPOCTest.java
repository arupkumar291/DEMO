/**
 * Copyright © 2016-2024 The Thingsboard Authors
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
package org.thingsboard.server.queue.discovery;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
class ConsistentHashBucketsPOCTest {

    @Test
    void circleVirtualNodes() {
        ConsistentHashCircle<VNode> circle = new ConsistentHashCircle<>();
        HashFunction hashFunction = Hashing.farmHashFingerprint64();
        for (int i = 0; i < 6; i++) {
            Node node = new Node("re-" + i);
            for (int j = 0; j < 1000; j++) {
                VNode vnode = new VNode(j, node);
                circle.put(hashFunction.hashUnencodedChars(vnode.toString()).asLong(), vnode);
            }
        }

        circle.log();

    }

}
