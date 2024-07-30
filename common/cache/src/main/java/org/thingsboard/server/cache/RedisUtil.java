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
package org.thingsboard.server.cache;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public class RedisUtil {

    public static <T> List<T> getAll(RedisConnection connection, String keyPattern, Function<byte[], T> desFunction) {
        return getAll(connection, keyPattern, desFunction, null);
    }

    public static <T> List<T> getAll(RedisConnection connection, String keyPattern, Function<byte[], T> desFunction, Predicate<T> filter) {
        List<T> elements = new ArrayList<>();
        ScanOptions scanOptions = ScanOptions.scanOptions().count(100).match(keyPattern + "*").build();
        List<Cursor<byte[]>> scans = new ArrayList<>();
        if (connection instanceof RedisClusterConnection) {
            ((RedisClusterConnection) connection).clusterGetNodes().forEach(node ->
                    scans.add(((RedisClusterConnection) connection).scan(node, scanOptions)));
        } else {
            scans.add(connection.scan(scanOptions));
        }

        scans.forEach(scan -> scan.forEachRemaining(key -> {
            byte[] element = connection.get(key);
            if (element != null) {
                try {
                    T entity = desFunction.apply(element);
                    if (filter == null || filter.test(entity)) {
                        elements.add(entity);
                    }
                } catch (Exception e) {
                    log.warn("[{}] Failed to deserialize from data: {}", Hex.encodeHexString(key), Hex.encodeHexString(element), e);
                }
            }
        }));
        return elements;
    }
}
