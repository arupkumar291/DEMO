/**
 * Copyright © 2016-2019 The Thingsboard Authors
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
package org.thingsboard.server.dao.sqlts.psql;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.thingsboard.server.dao.model.sqlts.psql.TsKvEntity;
import org.thingsboard.server.dao.sqlts.AbstractTimeseriesInsertRepository;
import org.thingsboard.server.dao.util.PsqlDao;
import org.thingsboard.server.dao.util.SqlTsDao;

import javax.persistence.Query;

@SqlTsDao
@PsqlDao
@Repository
@Transactional
public class PsqlTimeseriesInsertRepository extends AbstractTimeseriesInsertRepository<TsKvEntity> {

    private static final String ON_BOOL_VALUE_UPDATE_SET_NULLS = "str_v = null, long_v = null, dbl_v = null";
    private static final String ON_STR_VALUE_UPDATE_SET_NULLS = "bool_v = null, long_v = null, dbl_v = null";
    private static final String ON_LONG_VALUE_UPDATE_SET_NULLS = "str_v = null, bool_v = null, dbl_v = null";
    private static final String ON_DBL_VALUE_UPDATE_SET_NULLS = "str_v = null, long_v = null, bool_v = null";

    private static final String INSERT_OR_UPDATE_BOOL_STATEMENT = getInsertOrUpdateString(BOOL_V, ON_BOOL_VALUE_UPDATE_SET_NULLS);
    private static final String INSERT_OR_UPDATE_STR_STATEMENT = getInsertOrUpdateString(STR_V, ON_STR_VALUE_UPDATE_SET_NULLS);
    private static final String INSERT_OR_UPDATE_LONG_STATEMENT = getInsertOrUpdateString(LONG_V, ON_LONG_VALUE_UPDATE_SET_NULLS);
    private static final String INSERT_OR_UPDATE_DBL_STATEMENT = getInsertOrUpdateString(DBL_V, ON_DBL_VALUE_UPDATE_SET_NULLS);

    private static String getInsertOrUpdateString(String value, String nullValues) {
        return "INSERT INTO ts_kv (entity_id, key, ts, " + value + ") VALUES (:entity_id, :key, :ts, :" + value + ") ON CONFLICT (entity_id, key, ts) DO UPDATE SET " + value + " = :" + value + ", ts = :ts," + nullValues;
    }

    @Override
    public void saveOrUpdate(TsKvEntity entity) {
        processSaveOrUpdate(entity, INSERT_OR_UPDATE_BOOL_STATEMENT, INSERT_OR_UPDATE_STR_STATEMENT, INSERT_OR_UPDATE_LONG_STATEMENT, INSERT_OR_UPDATE_DBL_STATEMENT);
    }

    @Override
    protected void saveOrUpdateBoolean(TsKvEntity entity, String query) {
        entityManager.createNativeQuery(query)
                .setParameter("entity_id", entity.getEntityId())
                .setParameter("key", entity.getKey())
                .setParameter("ts", entity.getTs())
                .setParameter("bool_v", entity.getBooleanValue())
                .executeUpdate();
    }

    @Override
    protected void saveOrUpdateString(TsKvEntity entity, String query) {
        entityManager.createNativeQuery(query)
                .setParameter("entity_id", entity.getEntityId())
                .setParameter("key", entity.getKey())
                .setParameter("ts", entity.getTs())
                .setParameter("str_v", entity.getStrValue())
                .executeUpdate();
    }

    @Override
    protected void saveOrUpdateLong(TsKvEntity entity, String query) {
        entityManager.createNativeQuery(query)
                .setParameter("entity_id", entity.getEntityId())
                .setParameter("key", entity.getKey())
                .setParameter("ts", entity.getTs())
                .setParameter("long_v", entity.getLongValue())
                .executeUpdate();
    }

    @Override
    protected void saveOrUpdateDouble(TsKvEntity entity, String query) {
        entityManager.createNativeQuery(query)
                .setParameter("entity_id", entity.getEntityId())
                .setParameter("key", entity.getKey())
                .setParameter("ts", entity.getTs())
                .setParameter("dbl_v", entity.getDoubleValue())
                .executeUpdate();
    }
}