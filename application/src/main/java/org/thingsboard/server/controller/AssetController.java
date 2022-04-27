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
package org.thingsboard.server.controller;

import com.google.common.util.concurrent.ListenableFuture;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.EntitySubtype;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.asset.AssetInfo;
import org.thingsboard.server.common.data.asset.AssetSearchQuery;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.asset.AssetBulkImportService;
import org.thingsboard.server.service.asset.DefaultAssetServiceApplication;
import org.thingsboard.server.service.importing.BulkImportRequest;
import org.thingsboard.server.service.importing.BulkImportResult;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.thingsboard.server.controller.ControllerConstants.ASSET_ID;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_IDS;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_INFO_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_NAME_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_SORT_PROPERTY_ALLOWABLE_VALUES;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_TEXT_SEARCH_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ASSET_TYPE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ASSIGN_ASYNC_FIRST_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ASSIGN_RECEIVE_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_UNASSIGN_ASYNC_FIRST_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_UNASSIGN_RECEIVE_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_DATA_PARAMETERS;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_ALLOWABLE_VALUES;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.UUID_WIKI_LINK;
import static org.thingsboard.server.controller.EdgeController.EDGE_ID;
import static org.thingsboard.server.utils.CheckUtils.checkArrayParameter;
import static org.thingsboard.server.utils.CheckUtils.checkNotNull;
import static org.thingsboard.server.utils.CheckUtils.checkParameter;
import static org.thingsboard.server.utils.CheckUtils.createPageLink;
import static org.thingsboard.server.utils.CheckUtils.getCurrentUser;
import static org.thingsboard.server.utils.CheckUtils.getTenantId;
import static org.thingsboard.server.utils.CheckUtils.toUUID;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AssetController {
    private final AssetBulkImportService assetBulkImportService;

    private final DefaultAssetServiceApplication assetServiceApplication;

    @ApiOperation(value = "Get Asset (getAssetById)",
            notes = "Fetch the Asset object based on the provided Asset Id. " +
                    "If the user has the authority of 'Tenant Administrator', the server checks that the asset is owned by the same tenant. " +
                    "If the user has the authority of 'Customer User', the server checks that the asset is assigned to the same customer." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH
            , produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/asset/{assetId}", method = RequestMethod.GET)
    @ResponseBody
    public Asset getAssetById(@ApiParam(value = ASSET_ID_PARAM_DESCRIPTION)
                              @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(ASSET_ID, strAssetId);
        try {
            AssetId assetId = new AssetId(toUUID(strAssetId));
            return assetServiceApplication.checkAssetId(assetId, Operation.READ);
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Asset Info (getAssetInfoById)",
            notes = "Fetch the Asset Info object based on the provided Asset Id. " +
                    "If the user has the authority of 'Tenant Administrator', the server checks that the asset is owned by the same tenant. " +
                    "If the user has the authority of 'Customer User', the server checks that the asset is assigned to the same customer. "
                    + ASSET_INFO_DESCRIPTION + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/asset/info/{assetId}", method = RequestMethod.GET)
    @ResponseBody
    public AssetInfo getAssetInfoById(@ApiParam(value = ASSET_ID_PARAM_DESCRIPTION)
                                      @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(ASSET_ID, strAssetId);
        try {
            AssetId assetId = new AssetId(toUUID(strAssetId));
            return assetServiceApplication.checkAssetInfoId(assetId, Operation.READ);
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Create Or Update Asset (saveAsset)",
            notes = "Creates or Updates the Asset. When creating asset, platform generates Asset Id as " + UUID_WIKI_LINK +
                    "The newly created Asset id will be present in the response. " +
                    "Specify existing Asset id to update the asset. " +
                    "Referencing non-existing Asset Id will cause 'Not Found' error." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/asset", method = RequestMethod.POST)
    @ResponseBody
    public Asset saveAsset(@ApiParam(value = "A JSON value representing the asset.") @RequestBody Asset asset) throws ThingsboardException {
        return assetServiceApplication.saveAsset(asset, getCurrentUser());
    }

    @ApiOperation(value = "Delete asset (deleteAsset)",
            notes = "Deletes the asset and all the relations (from and to the asset). Referencing non-existing asset Id will cause an error." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/asset/{assetId}", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public void deleteAsset(@ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(ASSET_ID, strAssetId);
        assetServiceApplication.deleteAsset(new AssetId(toUUID(strAssetId)));
    }

    @ApiOperation(value = "Assign asset to customer (assignAssetToCustomer)",
            notes = "Creates assignment of the asset to customer. Customer will be able to query asset afterwards." + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/{customerId}/asset/{assetId}", method = RequestMethod.POST)
    @ResponseBody
    public Asset assignAssetToCustomer(@ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION) @PathVariable("customerId") String strCustomerId,
                                       @ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(CUSTOMER_ID, strCustomerId);
        checkParameter(ASSET_ID, strAssetId);
        return assetServiceApplication.assignAssetToCustomer(new CustomerId(toUUID(strCustomerId)), new AssetId(toUUID(strAssetId)));
    }

    @ApiOperation(value = "Unassign asset from customer (unassignAssetFromCustomer)",
            notes = "Clears assignment of the asset to customer. Customer will not be able to query asset afterwards." + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/asset/{assetId}", method = RequestMethod.DELETE)
    @ResponseBody
    public Asset unassignAssetFromCustomer(@ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(ASSET_ID, strAssetId);
        return assetServiceApplication.unassignAssetFromCustomer(new AssetId(toUUID(strAssetId)));
    }

    @ApiOperation(value = "Make asset publicly available (assignAssetToPublicCustomer)",
            notes = "Asset will be available for non-authorized (not logged-in) users. " +
                    "This is useful to create dashboards that you plan to share/embed on a publicly available website. " +
                    "However, users that are logged-in and belong to different tenant will not be able to access the asset." + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/public/asset/{assetId}", method = RequestMethod.POST)
    @ResponseBody
    public Asset assignAssetToPublicCustomer(@ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(ASSET_ID, strAssetId);
        return assetServiceApplication.assignAssetToPublicCustomer(new AssetId(toUUID(strAssetId)));
     }

    @ApiOperation(value = "Get Tenant Assets (getTenantAssets)",
            notes = "Returns a page of assets owned by tenant. " +
                    PAGE_DATA_PARAMETERS + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/assets", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Asset> getTenantAssets(
            @ApiParam(value = PAGE_SIZE_DESCRIPTION)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION)
            @RequestParam int page,
            @ApiParam(value = ASSET_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = ASSET_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = ASSET_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            TenantId tenantId = getTenantId();
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(assetServiceApplication.assetService.findAssetsByTenantIdAndType(tenantId, type, pageLink));
            } else {
                return checkNotNull(assetServiceApplication.assetService.findAssetsByTenantId(tenantId, pageLink));
            }
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Tenant Asset Infos (getTenantAssetInfos)",
            notes = "Returns a page of assets info objects owned by tenant. " +
                    PAGE_DATA_PARAMETERS + ASSET_INFO_DESCRIPTION + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/assetInfos", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<AssetInfo> getTenantAssetInfos(
            @ApiParam(value = PAGE_SIZE_DESCRIPTION)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION)
            @RequestParam int page,
            @ApiParam(value = ASSET_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = ASSET_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = ASSET_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(assetServiceApplication.assetService.findAssetInfosByTenantIdAndType(tenantId, type, pageLink));
            } else {
                return checkNotNull(assetServiceApplication.assetService.findAssetInfosByTenantId(tenantId, pageLink));
            }
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Tenant Asset (getTenantAsset)",
            notes = "Requested asset must be owned by tenant that the user belongs to. " +
                    "Asset name is an unique property of asset. So it can be used to identify the asset." + TENANT_AUTHORITY_PARAGRAPH, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/assets", params = {"assetName"}, method = RequestMethod.GET)
    @ResponseBody
    public Asset getTenantAsset(
            @ApiParam(value = ASSET_NAME_DESCRIPTION)
            @RequestParam String assetName) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            return checkNotNull(assetServiceApplication.assetService.findAssetByTenantIdAndName(tenantId, assetName));
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Customer Assets (getCustomerAssets)",
            notes = "Returns a page of assets objects assigned to customer. " +
                    PAGE_DATA_PARAMETERS, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/assets", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Asset> getCustomerAssets(
            @ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable("customerId") String strCustomerId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION)
            @RequestParam int page,
            @ApiParam(value = ASSET_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = ASSET_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = ASSET_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter("customerId", strCustomerId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            assetServiceApplication.checkCustomerId(customerId, Operation.READ);
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(assetServiceApplication.assetService.findAssetsByTenantIdAndCustomerIdAndType(tenantId, customerId, type, pageLink));
            } else {
                return checkNotNull(assetServiceApplication.assetService.findAssetsByTenantIdAndCustomerId(tenantId, customerId, pageLink));
            }
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Customer Asset Infos (getCustomerAssetInfos)",
            notes = "Returns a page of assets info objects assigned to customer. " +
                    PAGE_DATA_PARAMETERS + ASSET_INFO_DESCRIPTION, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/assetInfos", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<AssetInfo> getCustomerAssetInfos(
            @ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION)
            @PathVariable("customerId") String strCustomerId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION)
            @RequestParam int page,
            @ApiParam(value = ASSET_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = ASSET_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = ASSET_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter("customerId", strCustomerId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            assetServiceApplication.checkCustomerId(customerId, Operation.READ);
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(assetServiceApplication.assetService.findAssetInfosByTenantIdAndCustomerIdAndType(tenantId, customerId, type, pageLink));
            } else {
                return checkNotNull(assetServiceApplication.assetService.findAssetInfosByTenantIdAndCustomerId(tenantId, customerId, pageLink));
            }
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Assets By Ids (getAssetsByIds)",
            notes = "Requested assets must be owned by tenant or assigned to customer which user is performing the request. ", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/assets", params = {"assetIds"}, method = RequestMethod.GET)
    @ResponseBody
    public List<Asset> getAssetsByIds(
            @ApiParam(value = "A list of assets ids, separated by comma ','")
            @RequestParam(ASSET_IDS) String[] strAssetIds) throws ThingsboardException {
        checkArrayParameter(ASSET_IDS, strAssetIds);
        try {
            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();
            CustomerId customerId = user.getCustomerId();
            List<AssetId> assetIds = new ArrayList<>();
            for (String strAssetId : strAssetIds) {
                assetIds.add(new AssetId(toUUID(strAssetId)));
            }
            ListenableFuture<List<Asset>> assets;
            if (customerId == null || customerId.isNullUid()) {
                assets = assetServiceApplication.assetService.findAssetsByTenantIdAndIdsAsync(tenantId, assetIds);
            } else {
                assets = assetServiceApplication.assetService.findAssetsByTenantIdCustomerIdAndIdsAsync(tenantId, customerId, assetIds);
            }
            return checkNotNull(assets.get());
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Find related assets (findByQuery)",
            notes = "Returns all assets that are related to the specific entity. " +
                    "The entity id, relation type, asset types, depth of the search, and other query parameters defined using complex 'AssetSearchQuery' object. " +
                    "See 'Model' tab of the Parameters for more info.", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/assets", method = RequestMethod.POST)
    @ResponseBody
    public List<Asset> findByQuery(@RequestBody AssetSearchQuery query) throws ThingsboardException {
        checkNotNull(query);
        checkNotNull(query.getParameters());
        checkNotNull(query.getAssetTypes());
        assetServiceApplication.checkEntityId(query.getParameters().getEntityId(), Operation.READ);
        try {
            List<Asset> assets = checkNotNull(assetServiceApplication.assetService.findAssetsByQuery(getTenantId(), query).get());
            assets = assets.stream().filter(asset -> {
                try {
                    assetServiceApplication.accessControlService.checkPermission(getCurrentUser(), Resource.ASSET, Operation.READ, asset.getId(), asset);
                    return true;
                } catch (ThingsboardException e) {
                    return false;
                }
            }).collect(Collectors.toList());
            return assets;
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get Asset Types (getAssetTypes)",
            notes = "Returns a set of unique asset types based on assets that are either owned by the tenant or assigned to the customer which user is performing the request.", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/asset/types", method = RequestMethod.GET)
    @ResponseBody
    public List<EntitySubtype> getAssetTypes() throws ThingsboardException {
        try {
            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();
            ListenableFuture<List<EntitySubtype>> assetTypes = assetServiceApplication.assetService.findAssetTypesByTenantId(tenantId);
            return checkNotNull(assetTypes.get());
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Assign asset to edge (assignAssetToEdge)",
            notes = "Creates assignment of an existing asset to an instance of The Edge. " +
                    EDGE_ASSIGN_ASYNC_FIRST_STEP_DESCRIPTION +
                    "Second, remote edge service will receive a copy of assignment asset " +
                    EDGE_ASSIGN_RECEIVE_STEP_DESCRIPTION +
                    "Third, once asset will be delivered to edge service, it's going to be available for usage on remote edge instance.",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/edge/{edgeId}/asset/{assetId}", method = RequestMethod.POST)
    @ResponseBody
    public Asset assignAssetToEdge(@ApiParam(value = EDGE_ID_PARAM_DESCRIPTION) @PathVariable(EDGE_ID) String strEdgeId,
                                   @ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        checkParameter(ASSET_ID, strAssetId);
        try {
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            Edge edge = assetServiceApplication.checkEdgeId(edgeId, Operation.READ);

            AssetId assetId = new AssetId(toUUID(strAssetId));
            assetServiceApplication.checkAssetId(assetId, Operation.READ);

            Asset savedAsset = checkNotNull(assetServiceApplication.assetService.assignAssetToEdge(getTenantId(), assetId, edgeId));

            assetServiceApplication.logEntityAction(assetId, savedAsset,
                    savedAsset.getCustomerId(),
                    ActionType.ASSIGNED_TO_EDGE, null, strAssetId, strEdgeId, edge.getName());

            assetServiceApplication.sendEntityAssignToEdgeNotificationMsg(getTenantId(), edgeId, savedAsset.getId(), EdgeEventActionType.ASSIGNED_TO_EDGE);

            return savedAsset;
        } catch (Exception e) {

            assetServiceApplication.logEntityAction(assetServiceApplication.emptyId(EntityType.ASSET), null,
                    null,
                    ActionType.ASSIGNED_TO_EDGE, e, strAssetId, strEdgeId);

            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Unassign asset from edge (unassignAssetFromEdge)",
            notes = "Clears assignment of the asset to the edge. " +
                    EDGE_UNASSIGN_ASYNC_FIRST_STEP_DESCRIPTION +
                    "Second, remote edge service will receive an 'unassign' command to remove asset " +
                    EDGE_UNASSIGN_RECEIVE_STEP_DESCRIPTION +
                    "Third, once 'unassign' command will be delivered to edge service, it's going to remove asset locally.",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/edge/{edgeId}/asset/{assetId}", method = RequestMethod.DELETE)
    @ResponseBody
    public Asset unassignAssetFromEdge(@ApiParam(value = EDGE_ID_PARAM_DESCRIPTION) @PathVariable(EDGE_ID) String strEdgeId,
                                       @ApiParam(value = ASSET_ID_PARAM_DESCRIPTION) @PathVariable(ASSET_ID) String strAssetId) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        checkParameter(ASSET_ID, strAssetId);
        try {
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            Edge edge = assetServiceApplication.checkEdgeId(edgeId, Operation.READ);

            AssetId assetId = new AssetId(toUUID(strAssetId));
            Asset asset = assetServiceApplication.checkAssetId(assetId, Operation.READ);

            Asset savedAsset = checkNotNull(assetServiceApplication.assetService.unassignAssetFromEdge(getTenantId(), assetId, edgeId));

            assetServiceApplication.logEntityAction(assetId, asset,
                    asset.getCustomerId(),
                    ActionType.UNASSIGNED_FROM_EDGE, null, strAssetId, strEdgeId, edge.getName());

            assetServiceApplication.sendEntityAssignToEdgeNotificationMsg(getTenantId(), edgeId, savedAsset.getId(), EdgeEventActionType.UNASSIGNED_FROM_EDGE);

            return savedAsset;
        } catch (Exception e) {

            assetServiceApplication.logEntityAction(assetServiceApplication.emptyId(EntityType.ASSET), null,
                    null,
                    ActionType.UNASSIGNED_FROM_EDGE, e, strAssetId, strEdgeId);

            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Get assets assigned to edge (getEdgeAssets)",
            notes = "Returns a page of assets assigned to edge. " +
                    PAGE_DATA_PARAMETERS, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/edge/{edgeId}/assets", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Asset> getEdgeAssets(
            @ApiParam(value = EDGE_ID_PARAM_DESCRIPTION)
            @PathVariable(EDGE_ID) String strEdgeId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION)
            @RequestParam int page,
            @ApiParam(value = ASSET_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = ASSET_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = ASSET_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder,
            @ApiParam(value = "Timestamp. Assets with creation time before it won't be queried")
            @RequestParam(required = false) Long startTime,
            @ApiParam(value = "Timestamp. Assets with creation time after it won't be queried")
            @RequestParam(required = false) Long endTime) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            assetServiceApplication.checkEdgeId(edgeId, Operation.READ);
            TimePageLink pageLink = assetServiceApplication.createTimePageLink(pageSize, page, textSearch, sortProperty, sortOrder, startTime, endTime);
            PageData<Asset> nonFilteredResult;
            if (type != null && type.trim().length() > 0) {
                nonFilteredResult = assetServiceApplication.assetService.findAssetsByTenantIdAndEdgeIdAndType(tenantId, edgeId, type, pageLink);
            } else {
                nonFilteredResult = assetServiceApplication.assetService.findAssetsByTenantIdAndEdgeId(tenantId, edgeId, pageLink);
            }
            List<Asset> filteredAssets = nonFilteredResult.getData().stream().filter(asset -> {
                try {
                    assetServiceApplication.accessControlService.checkPermission(getCurrentUser(), Resource.ASSET, Operation.READ, asset.getId(), asset);
                    return true;
                } catch (ThingsboardException e) {
                    return false;
                }
            }).collect(Collectors.toList());
            PageData<Asset> filteredResult = new PageData<>(filteredAssets,
                    nonFilteredResult.getTotalPages(),
                    nonFilteredResult.getTotalElements(),
                    nonFilteredResult.hasNext());
            return checkNotNull(filteredResult);
        } catch (Exception e) {
            throw assetServiceApplication.handleException(e);
        }
    }

    @ApiOperation(value = "Import the bulk of assets (processAssetsBulkImport)",
            notes = "There's an ability to import the bulk of assets using the only .csv file.", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @PostMapping("/asset/bulk_import")
    public BulkImportResult<Asset> processAssetsBulkImport(@RequestBody BulkImportRequest request) throws Exception {
        SecurityUser user = getCurrentUser();
        return assetBulkImportService.processBulkImport(request, user, importedAssetInfo -> {
            onAssetCreatedOrUpdated(importedAssetInfo.getEntity(), importedAssetInfo.isUpdated(), user);
        });
    }

    private void onAssetCreatedOrUpdated(Asset asset, boolean updated, SecurityUser user) {
        try {
            assetServiceApplication.logEntityAction(user, asset.getId(), asset,
                    asset.getCustomerId(),
                    updated ? ActionType.UPDATED : ActionType.ADDED, null);
        } catch (ThingsboardException e) {
            log.error("Failed to log entity action", e);
        }

        if (updated) {
            assetServiceApplication.sendEntityNotificationMsg(asset.getTenantId(), asset.getId(), EdgeEventActionType.UPDATED);
        }
    }

}
