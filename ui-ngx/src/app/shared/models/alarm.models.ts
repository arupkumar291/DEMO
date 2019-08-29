///
/// Copyright © 2016-2019 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { BaseData } from '@shared/models/base-data';
import { AssetId } from '@shared/models/id/asset-id';
import { TenantId } from '@shared/models/id/tenant-id';
import { CustomerId } from '@shared/models/id/customer-id';
import { AlarmId } from '@shared/models/id/alarm-id';
import { EntityId } from '@shared/models/id/entity-id';
import { ActionStatus } from '@shared/models/audit-log.models';
import { TimePageLink } from '@shared/models/page/page-link';

export enum AlarmSeverity {
  CRITICAL = 'CRITICAL',
  MAJOR = 'MAJOR',
  MINOR = 'MINOR',
  WARNING = 'WARNING',
  INDETERMINATE = 'INDETERMINATE'
}

export enum AlarmStatus {
  ACTIVE_UNACK = 'ACTIVE_UNACK',
  ACTIVE_ACK = 'ACTIVE_ACK',
  CLEARED_UNACK = 'CLEARED_UNACK',
  CLEARED_ACK = 'CLEARED_ACK'
}

export enum AlarmSearchStatus {
  ANY = 'ANY',
  ACTIVE = 'ACTIVE',
  CLEARED = 'CLEARED',
  ACK = 'ACK',
  UNACK = 'UNACK'
}

export const alarmSeverityTranslations = new Map<AlarmSeverity, string>(
  [
    [AlarmSeverity.CRITICAL, 'alarm.severity-critical'],
    [AlarmSeverity.MAJOR, 'alarm.severity-major'],
    [AlarmSeverity.MINOR, 'alarm.severity-minor'],
    [AlarmSeverity.WARNING, 'alarm.severity-warning'],
    [AlarmSeverity.INDETERMINATE, 'alarm.severity-indeterminate']
  ]
);

export const alarmStatusTranslations = new Map<AlarmStatus, string>(
  [
    [AlarmStatus.ACTIVE_UNACK, 'alarm.display-status.ACTIVE_UNACK'],
    [AlarmStatus.ACTIVE_ACK, 'alarm.display-status.ACTIVE_ACK'],
    [AlarmStatus.CLEARED_UNACK, 'alarm.display-status.CLEARED_UNACK'],
    [AlarmStatus.CLEARED_ACK, 'alarm.display-status.CLEARED_ACK'],
  ]
);

export const alarmSearchStatusTranslations = new Map<AlarmSearchStatus, string>(
  [
    [AlarmSearchStatus.ANY, 'alarm.search-status.ANY'],
    [AlarmSearchStatus.ACTIVE, 'alarm.search-status.ACTIVE'],
    [AlarmSearchStatus.CLEARED, 'alarm.search-status.CLEARED'],
    [AlarmSearchStatus.ACK, 'alarm.search-status.ACK'],
    [AlarmSearchStatus.UNACK, 'alarm.search-status.UNACK']
  ]
);

export const alarmSeverityColors = new Map<AlarmSeverity, string>(
  [
    [AlarmSeverity.CRITICAL, 'red'],
    [AlarmSeverity.MAJOR, 'orange'],
    [AlarmSeverity.MINOR, '#ffca3d'],
    [AlarmSeverity.WARNING, '#abab00'],
    [AlarmSeverity.INDETERMINATE, 'green']
  ]
);

export interface Alarm extends BaseData<AlarmId> {
  tenantId: TenantId;
  type: string;
  originator: EntityId;
  severity: AlarmSeverity;
  status: AlarmStatus;
  startTs: number;
  endTs: number;
  ackTs: number;
  clearTs: number;
  propagate: boolean;
  originatorName?: string;
  details?: any;
}

export interface AlarmInfo extends Alarm {
  originatorName: string;
}

export class AlarmQuery {

  affectedEntityId: EntityId;
  pageLink: TimePageLink;
  searchStatus: AlarmSearchStatus;
  status: AlarmStatus;
  fetchOriginator: boolean;

  constructor(entityId: EntityId, pageLink: TimePageLink,
              searchStatus: AlarmSearchStatus, status: AlarmStatus,
              fetchOriginator: boolean) {
    this.affectedEntityId = entityId;
    this.pageLink = pageLink;
    this.searchStatus = searchStatus;
    this.status = status;
    this.fetchOriginator = fetchOriginator;
  }

  public toQuery(): string {
    let query = `/${this.affectedEntityId.entityType}/${this.affectedEntityId.id}`;
    query += this.pageLink.toQuery();
    if (this.searchStatus) {
      query += `&searchStatus=${this.searchStatus}`;
    } else if (this.status) {
      query += `&status=${this.status}`;
    }
    if (typeof this.fetchOriginator !== 'undefined' && this.fetchOriginator !== null) {
      query += `&fetchOriginator=${this.fetchOriginator}`;
    }
    return query;
  }

}
