--
-- Copyright © 2016-2024 The Thingsboard Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- UPDATE PUBLIC CUSTOMERS START

ALTER TABLE customer ADD COLUMN IF NOT EXISTS is_public boolean DEFAULT false;
UPDATE customer SET is_public = true WHERE title = 'Public';

-- UPDATE PUBLIC CUSTOMERS END

-- UPDATE CUSTOMERS WITH SAME TITLE START

-- Check for postgres version to install pgcrypto
-- if version less then 130000 to have ability use gen_random_uuid();

DO
$$
    BEGIN
        IF (SELECT current_setting('server_version_num')::int) < 130000 THEN
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
        END IF;
    END
$$;

CREATE OR REPLACE PROCEDURE update_customers_with_the_same_title()
    LANGUAGE plpgsql
AS
$$
DECLARE
    customer_record  RECORD;
    dashboard_record RECORD;
    new_title        TEXT;
    updated_json     JSONB;
BEGIN
    RAISE NOTICE 'Starting the customer and dashboard update process.';

    FOR customer_record IN
        SELECT id, tenant_id, title
        FROM customer
        WHERE id IN (SELECT c1.id
                     FROM customer c1
                              JOIN customer c2 ON c1.tenant_id = c2.tenant_id AND c1.title = c2.title
                     WHERE c1.id > c2.id)
        LOOP
            new_title := customer_record.title || '_' || gen_random_uuid();
            UPDATE customer
            SET title = new_title
            WHERE id = customer_record.id;
            RAISE NOTICE 'Updated customer with id: % with new title: %', customer_record.id, new_title;

            -- Find and update related dashboards for the customer
            FOR dashboard_record IN
                SELECT d.id, d.assigned_customers
                FROM dashboard d
                         JOIN relation r ON d.id = r.to_id
                WHERE r.from_id = customer_record.id
                  AND r.to_type = 'DASHBOARD'
                  AND r.relation_type_group = 'DASHBOARD'
                  AND r.relation_type = 'Contains'
                LOOP
                    -- Update each assigned_customers entry where the customerId matches
                    updated_json := (SELECT jsonb_agg(
                                                    CASE
                                                        WHEN (value -> 'customerId' ->> 'id')::uuid = customer_record.id
                                                            THEN jsonb_set(value, '{title}', ('"' || new_title || '"')::jsonb)
                                                        ELSE value
                                                        END
                                                )
                                     FROM jsonb_array_elements(dashboard_record.assigned_customers::jsonb));

                    UPDATE dashboard
                    SET assigned_customers = updated_json
                    WHERE id = dashboard_record.id;
                    RAISE NOTICE 'Updated dashboard with id: % with new assigned_customers: %', dashboard_record.id, updated_json;
                END LOOP;
        END LOOP;
    RAISE NOTICE 'Customers and dashboards update process completed successfully!';
END;
$$;

call update_customers_with_the_same_title();

DROP PROCEDURE IF EXISTS update_customers_with_the_same_title;

-- UPDATE CUSTOMERS WITH SAME TITLE END

-- CUSTOMER UNIQUE CONSTRAINT UPDATE START

ALTER TABLE customer DROP CONSTRAINT IF EXISTS customer_title_unq_key;
ALTER TABLE customer ADD CONSTRAINT customer_title_unq_key UNIQUE (tenant_id, title);

-- CUSTOMER UNIQUE CONSTRAINT UPDATE END

-- create new attribute_kv table schema
DO
$$
    BEGIN
        -- in case of running the upgrade script a second time:
        IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name = 'attribute_kv' and column_name='entity_type') THEN
            DROP VIEW IF EXISTS device_info_view;
            DROP VIEW IF EXISTS device_info_active_attribute_view;
            ALTER INDEX IF EXISTS idx_attribute_kv_by_key_and_last_update_ts RENAME TO idx_attribute_kv_by_key_and_last_update_ts_old;
            IF EXISTS(SELECT 1 FROM pg_constraint WHERE conname = 'attribute_kv_pkey') THEN
                ALTER TABLE attribute_kv RENAME CONSTRAINT attribute_kv_pkey TO attribute_kv_pkey_old;
            END IF;
            ALTER TABLE attribute_kv RENAME TO attribute_kv_old;
            CREATE TABLE IF NOT EXISTS attribute_kv
            (
                entity_id uuid,
                attribute_type int,
                attribute_key int,
                bool_v boolean,
                str_v varchar(10000000),
                long_v bigint,
                dbl_v double precision,
                json_v json,
                last_update_ts bigint,
                CONSTRAINT attribute_kv_pkey PRIMARY KEY (entity_id, attribute_type, attribute_key)
            );
        END IF;
    END;
$$;

-- rename ts_kv_dictionary table to key_dictionary or create table if not exists
DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'ts_kv_dictionary') THEN
            ALTER TABLE ts_kv_dictionary RENAME CONSTRAINT ts_key_id_pkey TO key_dictionary_id_pkey;
            ALTER TABLE ts_kv_dictionary RENAME TO key_dictionary;
        ELSE CREATE TABLE IF NOT EXISTS key_dictionary(
                key    varchar(255) NOT NULL,
                key_id serial UNIQUE,
                CONSTRAINT key_dictionary_id_pkey PRIMARY KEY (key)
                );
        END IF;
    END;
$$;

-- insert keys into key_dictionary
DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'attribute_kv_old') THEN
            INSERT INTO key_dictionary(key) SELECT DISTINCT attribute_key FROM attribute_kv_old ON CONFLICT DO NOTHING;
        END IF;
    END;
$$;

-- migrate attributes from attribute_kv_old to attribute_kv
DO
$$
DECLARE
    row_num_old integer;
    row_num integer;
BEGIN
    IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_name = 'attribute_kv_old') THEN
        INSERT INTO attribute_kv(entity_id, attribute_type, attribute_key, bool_v, str_v, long_v, dbl_v, json_v, last_update_ts)
            SELECT a.entity_id, CASE
                        WHEN a.attribute_type = 'CLIENT_SCOPE' THEN 1
                        WHEN a.attribute_type = 'SERVER_SCOPE' THEN 2
                        WHEN a.attribute_type = 'SHARED_SCOPE' THEN 3
                        ELSE 0
                        END,
                k.key_id,  a.bool_v, a.str_v, a.long_v, a.dbl_v, a.json_v, a.last_update_ts
                FROM attribute_kv_old a INNER JOIN key_dictionary k ON (a.attribute_key = k.key);
        SELECT COUNT(*) INTO row_num_old FROM attribute_kv_old;
        SELECT COUNT(*) INTO row_num FROM attribute_kv;
        RAISE NOTICE 'Migrated % of % rows', row_num, row_num_old;

        IF row_num != 0 THEN
            DROP TABLE IF EXISTS attribute_kv_old;
        ELSE
           RAISE EXCEPTION 'Table attribute_kv is empty';
        END IF;

        CREATE INDEX IF NOT EXISTS idx_attribute_kv_by_key_and_last_update_ts ON attribute_kv(entity_id, attribute_key, last_update_ts desc);
    END IF;
EXCEPTION
    WHEN others THEN
        ROLLBACK;
        RAISE EXCEPTION 'Error during COPY: %', SQLERRM;
END
$$;
