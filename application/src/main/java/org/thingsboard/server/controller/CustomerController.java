/**
 * Copyright © 2016-2021 The Thingsboard Authors
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
package org.thingsboard.server.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RestController
@TbCoreComponent
@RequestMapping("/api")
public class CustomerController extends BaseController {

    public static final String CUSTOMER_ID = "customerId";
    public static final String IS_PUBLIC = "isPublic";

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/customer/{customerId}")
    public Customer getCustomerById(@PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        try {
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            Customer customer = checkCustomerId(customerId, Operation.READ);
            if(!customer.getAdditionalInfo().isNull()) {
                processDashboardIdFromAdditionalInfo((ObjectNode) customer.getAdditionalInfo(), HOME_DASHBOARD);
            }
            return customer;
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/customer/{customerId}/shortInfo")
    public JsonNode getShortCustomerInfoById(@PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        try {
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            Customer customer = checkCustomerId(customerId, Operation.READ);
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode infoObject = objectMapper.createObjectNode();
            infoObject.put("title", customer.getTitle());
            infoObject.put(IS_PUBLIC, customer.isPublic());
            return infoObject;
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @GetMapping(value = "/customer/{customerId}/title", produces = "application/text")
    public String getCustomerTitleById(@PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        try {
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            Customer customer = checkCustomerId(customerId, Operation.READ);
            return customer.getTitle();
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @PostMapping(value = "/customer")
    public Customer saveCustomer(@RequestBody Customer customer) throws ThingsboardException {
        try {
            customer.setTenantId(getCurrentUser().getTenantId());

            checkEntity(customer.getId(), customer, Resource.CUSTOMER);

            Customer savedCustomer = checkNotNull(customerService.saveCustomer(customer));

            logEntityAction(savedCustomer.getId(), savedCustomer,
                    savedCustomer.getId(),
                    customer.getId() == null ? ActionType.ADDED : ActionType.UPDATED, null);

            if (customer.getId() != null) {
                sendEntityNotificationMsg(savedCustomer.getTenantId(), savedCustomer.getId(), EdgeEventActionType.UPDATED);
            }

            return savedCustomer;
        } catch (Exception e) {

            logEntityAction(emptyId(EntityType.CUSTOMER), customer,
                    null, customer.getId() == null ? ActionType.ADDED : ActionType.UPDATED, e);

            throw handleException(e);
        }
    }

    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @DeleteMapping(value = "/customer/{customerId}")
    @ResponseStatus(value = HttpStatus.OK)
    public void deleteCustomer(@PathVariable(CUSTOMER_ID) String strCustomerId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        try {
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            Customer customer = checkCustomerId(customerId, Operation.DELETE);

            List<EdgeId> relatedEdgeIds = findRelatedEdgeIds(getTenantId(), customerId);

            customerService.deleteCustomer(getTenantId(), customerId);

            logEntityAction(customerId, customer,
                    customer.getId(),
                    ActionType.DELETED, null, strCustomerId);

            sendDeleteNotificationMsg(getTenantId(), customerId, relatedEdgeIds);
            tbClusterService.onEntityStateChange(getTenantId(), customerId, ComponentLifecycleEvent.DELETED);
        } catch (Exception e) {

            logEntityAction(emptyId(EntityType.CUSTOMER),
                    null,
                    null,
                    ActionType.DELETED, e, strCustomerId);

            throw handleException(e);
        }
    }

    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/customers", params = {"pageSize", "page"})
    public PageData<Customer> getCustomers(@RequestParam int pageSize,
                                           @RequestParam int page,
                                           @RequestParam(required = false) String textSearch,
                                           @RequestParam(required = false) String sortProperty,
                                           @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            TenantId tenantId = getCurrentUser().getTenantId();
            return checkNotNull(customerService.findCustomersByTenantId(tenantId, pageLink));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @GetMapping(value = "/tenant/customers", params = {"customerTitle"})
    public Customer getTenantCustomer(
            @RequestParam String customerTitle) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            return checkNotNull(customerService.findCustomerByTenantIdAndTitle(tenantId, customerTitle));
        } catch (Exception e) {
            throw handleException(e);
        }
    }
}
