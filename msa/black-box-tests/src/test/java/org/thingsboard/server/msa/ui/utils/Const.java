/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.msa.ui.utils;

import org.thingsboard.server.msa.ui.base.Base;

public class Const extends Base {
    public static final String URL = "http://localhost/";
    public static final String TENANT_EMAIL = "tenant@thingsboard.org";
    public static final String TENANT_PASSWORD = "tenant";
    public static final String ENTITY_NAME = "Az!@#$%^&*()_-+=~`" + getRandomNumber();
    public static final String ROOT_RULE_CHAIN_NAME = "Root Rule Chain";
    public static final String IMPORT_RULE_CHAIN_NAME = "Rule Chain from Import";
    public static final String IMPORT_RULE_CHAIN_FILE_NAME = "forImport.json";
    public static final String IMPORT_TXT_FILE_NAME = "forImport.txt";
    public static final String EMPTY_IMPORT_MESSAGE = "No file selected";
}