/**
 * Copyright © 2016-2018 The Thingsboard Authors
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
package org.thingsboard.server.dao.asset;


import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.*;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.asset.AssetSearchQuery;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.page.TextPageLink;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.EntitySearchDirection;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.entityview.EntityViewService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.service.PaginatedRemover;
import org.thingsboard.server.dao.tenant.TenantDao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.thingsboard.server.common.data.CacheConstants.ASSET_CACHE;
import static org.thingsboard.server.dao.DaoUtil.toUUIDs;
import static org.thingsboard.server.dao.service.Validator.*;

@Service
@Slf4j
public class BaseAssetService extends AbstractEntityService implements AssetService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_PAGE_LINK = "Incorrect page link ";
    public static final String INCORRECT_CUSTOMER_ID = "Incorrect customerId ";
    public static final String INCORRECT_ASSET_ID = "Incorrect assetId ";
    @Autowired
    private AssetDao assetDao;

    @Autowired
    private TenantDao tenantDao;

    @Autowired
    private CustomerDao customerDao;

    @Autowired
    private EntityViewService entityViewService;

    @Autowired
    private CacheManager cacheManager;

    @Override
    public Asset findAssetById(AssetId assetId) {
        log.trace("Executing findAssetById [{}]", assetId);
        validateId(assetId, INCORRECT_ASSET_ID + assetId);
        return assetDao.findById(assetId.getId());
    }

    @Override
    public ListenableFuture<Asset> findAssetByIdAsync(AssetId assetId) {
        log.trace("Executing findAssetById [{}]", assetId);
        validateId(assetId, INCORRECT_ASSET_ID + assetId);
        return assetDao.findByIdAsync(assetId.getId());
    }

    @Cacheable(cacheNames = ASSET_CACHE, key = "{#tenantId, #name}")
    @Override
    public Asset findAssetByTenantIdAndName(TenantId tenantId, String name) {
        log.trace("Executing findAssetByTenantIdAndName [{}][{}]", tenantId, name);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        return assetDao.findAssetsByTenantIdAndName(tenantId.getId(), name)
                .orElse(null);
    }

    @CacheEvict(cacheNames = ASSET_CACHE, key = "{#asset.tenantId, #asset.name}")
    @Override
    public Asset saveAsset(Asset asset) {
        log.trace("Executing saveAsset [{}]", asset);
        assetValidator.validate(asset);
        return assetDao.save(asset);
    }

    @Override
    public Asset assignAssetToCustomer(AssetId assetId, CustomerId customerId) {
        Asset asset = findAssetById(assetId);
        Customer customer = customerDao.findById(customerId.getId());
        if (customer == null) {
            throw new DataValidationException("Can't assign asset to non-existent customer");
        }
        if (!customer.getTenantId().getId().equals(asset.getTenantId().getId())) {
            throw new DataValidationException("Can't assign asset to customer from different tenant");
        }
        if (asset.addAssignedCustomer(customer)) {
            try {
                createRelation(new EntityRelation(customerId, assetId, EntityRelation.CONTAINS_TYPE, RelationTypeGroup.ASSET));
            } catch (ExecutionException | InterruptedException e) {
                log.error("[{}] Failed to create asset relation. Customer Id: [{}]", assetId, customerId);
                throw new RuntimeException(e);
            }
            return saveAsset(asset);
        } else {
            return asset;
        }
    }

    @Override
    public Asset unassignAssetFromCustomer(AssetId assetId, CustomerId customerId) {
        Asset asset = findAssetById(assetId);
        Customer customer = customerDao.findById(customerId.getId());
        if (customer == null) {
            throw new DataValidationException("Can't assign asset to non-existent customer");
        }
        if (!customer.getTenantId().getId().equals(asset.getTenantId().getId())) {
            throw new DataValidationException("Can't assign asset to customer from different tenant");
        }
        if (asset.removeAssignedCustomer(customer)) {
            try {
                deleteRelation(new EntityRelation(customerId, assetId, EntityRelation.CONTAINS_TYPE, RelationTypeGroup.ASSET));
            } catch (ExecutionException | InterruptedException e) {
                log.warn("[{}] Failed to delete asset relation. Customer Id: [{}]", assetId, customerId);
                throw new RuntimeException(e);
            }
            return saveAsset(asset);
        } else {
            return asset;
        }
    }

    @Override
    public void deleteAsset(AssetId assetId) {
        log.trace("Executing deleteAsset [{}]", assetId);
        validateId(assetId, INCORRECT_ASSET_ID + assetId);
        deleteEntityRelations(assetId);

        Asset asset = assetDao.findById(assetId.getId());
        try {
            List<EntityView> entityViews = entityViewService.findEntityViewsByTenantIdAndEntityIdAsync(asset.getTenantId(), assetId).get();
            if (entityViews != null && !entityViews.isEmpty()) {
                throw new DataValidationException("Can't delete asset that is assigned to entity views!");
            }
        } catch (ExecutionException | InterruptedException e) {
            log.error("Exception while finding entity views for assetId [{}]", assetId, e);
            throw new RuntimeException("Exception while finding entity views for assetId [" + assetId + "]", e);
        }

        List<Object> list = new ArrayList<>();
        list.add(asset.getTenantId());
        list.add(asset.getName());
        Cache cache = cacheManager.getCache(ASSET_CACHE);
        cache.evict(list);

        assetDao.removeById(assetId.getId());
    }

    @Override
    public TextPageData<Asset> findAssetsByTenantId(TenantId tenantId, TextPageLink pageLink) {
        log.trace("Executing findAssetsByTenantId, tenantId [{}], pageLink [{}]", tenantId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Asset> assets = assetDao.findAssetsByTenantId(tenantId.getId(), pageLink);
        return new TextPageData<>(assets, pageLink);
    }

    @Override
    public TextPageData<Asset> findAssetsByTenantIdAndType(TenantId tenantId, String type, TextPageLink pageLink) {
        log.trace("Executing findAssetsByTenantIdAndType, tenantId [{}], type [{}], pageLink [{}]", tenantId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Asset> assets = assetDao.findAssetsByTenantIdAndType(tenantId.getId(), type, pageLink);
        return new TextPageData<>(assets, pageLink);
    }

    @Override
    public ListenableFuture<List<Asset>> findAssetsByTenantIdAndIdsAsync(TenantId tenantId, List<AssetId> assetIds) {
        log.trace("Executing findAssetsByTenantIdAndIdsAsync, tenantId [{}], assetIds [{}]", tenantId, assetIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateIds(assetIds, "Incorrect assetIds " + assetIds);
        return assetDao.findAssetsByTenantIdAndIdsAsync(tenantId.getId(), toUUIDs(assetIds));
    }

    @Override
    public void deleteAssetsByTenantId(TenantId tenantId) {
        log.trace("Executing deleteAssetsByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        tenantAssetsRemover.removeEntities(tenantId);
    }

    @Override
    public TextPageData<Asset> findAssetsByTenantIdAndCustomerId(TenantId tenantId, CustomerId customerId, TextPageLink pageLink) {
        log.trace("Executing findAssetsByTenantIdAndCustomerId, tenantId [{}], customerId [{}], pageLink [{}]", tenantId, customerId, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Asset> assets = assetDao.findAssetsByTenantIdAndCustomerId(tenantId.getId(), customerId.getId(), pageLink);
        return new TextPageData<>(assets, pageLink);
    }

    @Override
    public TextPageData<Asset> findAssetsByTenantIdAndCustomerIdAndType(TenantId tenantId, CustomerId customerId, String type, TextPageLink pageLink) {
        log.trace("Executing findAssetsByTenantIdAndCustomerIdAndType, tenantId [{}], customerId [{}], type [{}], pageLink [{}]", tenantId, customerId, type, pageLink);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateString(type, "Incorrect type " + type);
        validatePageLink(pageLink, INCORRECT_PAGE_LINK + pageLink);
        List<Asset> assets = assetDao.findAssetsByTenantIdAndCustomerIdAndType(tenantId.getId(), customerId.getId(), type, pageLink);
        return new TextPageData<>(assets, pageLink);
    }

    @Override
    public ListenableFuture<List<Asset>> findAssetsByTenantIdCustomerIdAndIdsAsync(TenantId tenantId, CustomerId customerId, List<AssetId> assetIds) {
        log.trace("Executing findAssetsByTenantIdAndCustomerIdAndIdsAsync, tenantId [{}], customerId [{}], assetIds [{}]", tenantId, customerId, assetIds);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        validateIds(assetIds, "Incorrect assetIds " + assetIds);
        return assetDao.findAssetsByTenantIdAndCustomerIdAndIdsAsync(tenantId.getId(), customerId.getId(), toUUIDs(assetIds));
    }

    @Override
    public void unassignCustomerAssets(TenantId tenantId, CustomerId customerId) {
        log.trace("Executing unassignCustomerAssets, tenantId [{}], customerId [{}]", tenantId, customerId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        validateId(customerId, INCORRECT_CUSTOMER_ID + customerId);
        new CustomerAssetsUnassigner(tenantId).removeEntities(customerId);
    }

    @Override
    public ListenableFuture<List<Asset>> findAssetsByQuery(AssetSearchQuery query) {
        ListenableFuture<List<EntityRelation>> relations = relationService.findByQuery(query.toEntitySearchQuery());
        ListenableFuture<List<Asset>> assets = Futures.transformAsync(relations, r -> {
            EntitySearchDirection direction = query.toEntitySearchQuery().getParameters().getDirection();
            List<ListenableFuture<Asset>> futures = new ArrayList<>();
            for (EntityRelation relation : r) {
                EntityId entityId = direction == EntitySearchDirection.FROM ? relation.getTo() : relation.getFrom();
                if (entityId.getEntityType() == EntityType.ASSET) {
                    futures.add(findAssetByIdAsync(new AssetId(entityId.getId())));
                }
            }
            return Futures.successfulAsList(futures);
        });
        assets = Futures.transform(assets, (Function<List<Asset>, List<Asset>>)assetList ->
            assetList == null ? Collections.emptyList() : assetList.stream().filter(asset -> query.getAssetTypes().contains(asset.getType())).collect(Collectors.toList())
        );
        return assets;
    }

    @Override
    public ListenableFuture<List<EntitySubtype>> findAssetTypesByTenantId(TenantId tenantId) {
        log.trace("Executing findAssetTypesByTenantId, tenantId [{}]", tenantId);
        validateId(tenantId, INCORRECT_TENANT_ID + tenantId);
        ListenableFuture<List<EntitySubtype>> tenantAssetTypes = assetDao.findTenantAssetTypesAsync(tenantId.getId());
        return Futures.transform(tenantAssetTypes,
                (Function<List<EntitySubtype>, List<EntitySubtype>>) assetTypes -> {
                    assetTypes.sort(Comparator.comparing(EntitySubtype::getType));
                    return assetTypes;
                });
    }

    private DataValidator<Asset> assetValidator =
            new DataValidator<Asset>() {

                @Override
                protected void validateCreate(Asset asset) {
                    assetDao.findAssetsByTenantIdAndName(asset.getTenantId().getId(), asset.getName()).ifPresent(
                            d -> {
                                throw new DataValidationException("Asset with such name already exists!");
                            }
                    );
                }

                @Override
                protected void validateUpdate(Asset asset) {
                    assetDao.findAssetsByTenantIdAndName(asset.getTenantId().getId(), asset.getName()).ifPresent(
                            d -> {
                                if (!d.getId().equals(asset.getId())) {
                                    throw new DataValidationException("Asset with such name already exists!");
                                }
                            }
                    );
                }

                @Override
                protected void validateDataImpl(Asset asset) {
                    if (StringUtils.isEmpty(asset.getType())) {
                        throw new DataValidationException("Asset type should be specified!");
                    }
                    if (StringUtils.isEmpty(asset.getName())) {
                        throw new DataValidationException("Asset name should be specified!");
                    }
                    if (asset.getTenantId() == null) {
                        throw new DataValidationException("Asset should be assigned to tenant!");
                    } else {
                        Tenant tenant = tenantDao.findById(asset.getTenantId().getId());
                        if (tenant == null) {
                            throw new DataValidationException("Asset is referencing to non-existent tenant!");
                        }
                    }
                    if (!asset.getAssignedCustomers().isEmpty()) {
                        for (ShortCustomerInfo customerInfo : asset.getAssignedCustomers()) {
                            Customer customer = customerDao.findById(customerInfo.getCustomerId().getId());
                            if (customer == null) {
                                throw new DataValidationException("Can't assign asset to non-existent customer!");
                            }
                            if (!customer.getTenantId().equals(asset.getTenantId())) {
                                throw new DataValidationException("Can't assign asset to customer from different tenant!");
                            }
                        }
                    }
                }
            };

    private PaginatedRemover<TenantId, Asset> tenantAssetsRemover =
            new PaginatedRemover<TenantId, Asset>() {

                @Override
                protected List<Asset> findEntities(TenantId id, TextPageLink pageLink) {
                    return assetDao.findAssetsByTenantId(id.getId(), pageLink);
                }

                @Override
                protected void removeEntity(Asset entity) {
                    deleteAsset(new AssetId(entity.getId().getId()));
                }
            };

    class CustomerAssetsUnassigner extends PaginatedRemover<CustomerId, Asset> {

        private TenantId tenantId;

        CustomerAssetsUnassigner(TenantId tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        protected List<Asset> findEntities(CustomerId id, TextPageLink pageLink) {
            return assetDao.findAssetsByTenantIdAndCustomerId(tenantId.getId(), id.getId(), pageLink);
        }

        @Override
        protected void removeEntity(Asset entity) {
            for (ShortCustomerInfo customerInfo : entity.getAssignedCustomers()) {
                unassignAssetFromCustomer(new AssetId(entity.getId().getId()), customerInfo.getCustomerId());
            }
        }
    }
}
