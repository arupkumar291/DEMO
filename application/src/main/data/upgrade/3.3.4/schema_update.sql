--
-- Copyright © 2016-2022 The Thingsboard Authors
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

ALTER TABLE device
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE device_profile
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE rule_chain
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE rule_node
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE dashboard
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE widgets_bundle
    ADD COLUMN IF NOT EXISTS external_id UUID;
ALTER TABLE entity_view
    ADD COLUMN IF NOT EXISTS external_id UUID;

CREATE INDEX IF NOT EXISTS idx_device_external_id ON device(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_device_profile_external_id ON device_profile(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_asset_external_id ON asset(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_rule_chain_external_id ON rule_chain(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_rule_node_external_id ON rule_node(rule_chain_id, external_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_external_id ON dashboard(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_customer_external_id ON customer(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_widgets_bundle_external_id ON widgets_bundle(tenant_id, external_id);
CREATE INDEX IF NOT EXISTS idx_entity_view_external_id ON entity_view(tenant_id, external_id);

CREATE INDEX IF NOT EXISTS idx_rule_node_type ON rule_node(type);

ALTER TABLE admin_settings
    ADD COLUMN IF NOT EXISTS tenant_id uuid NOT NULL DEFAULT '13814000-1dd2-11b2-8080-808080808080';

CREATE TABLE IF NOT EXISTS queue (
    id uuid NOT NULL CONSTRAINT queue_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    tenant_id uuid,
    name varchar(255),
    topic varchar(255),
    poll_interval int,
    partitions int,
    consumer_per_partition boolean,
    pack_processing_timeout bigint,
    submit_strategy varchar(255),
    processing_strategy varchar(255),
    additional_info varchar
);

CREATE TABLE IF NOT EXISTS user_auth_settings (
    id uuid NOT NULL CONSTRAINT user_auth_settings_pkey PRIMARY KEY,
    created_time bigint NOT NULL,
    user_id uuid UNIQUE NOT NULL CONSTRAINT fk_user_auth_settings_user_id REFERENCES tb_user(id),
    two_fa_settings varchar
);

