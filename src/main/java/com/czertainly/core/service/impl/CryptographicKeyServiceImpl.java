package com.czertainly.core.service.impl;

import com.czertainly.api.clients.cryptography.KeyManagementApiClient;
import com.czertainly.api.exception.*;
import com.czertainly.api.model.client.attribute.RequestAttributeDto;
import com.czertainly.api.model.client.certificate.SearchRequestDto;
import com.czertainly.api.model.client.cryptography.CryptographicKeyResponseDto;
import com.czertainly.api.model.client.cryptography.key.*;
import com.czertainly.api.model.common.NameAndUuidDto;
import com.czertainly.api.model.common.attribute.v2.AttributeType;
import com.czertainly.api.model.common.attribute.v2.BaseAttribute;
import com.czertainly.api.model.common.attribute.v2.DataAttribute;
import com.czertainly.api.model.connector.cryptography.enums.CryptographicAlgorithm;
import com.czertainly.api.model.connector.cryptography.enums.KeyFormat;
import com.czertainly.api.model.connector.cryptography.enums.KeyType;
import com.czertainly.api.model.connector.cryptography.key.CreateKeyRequestDto;
import com.czertainly.api.model.connector.cryptography.key.KeyData;
import com.czertainly.api.model.connector.cryptography.key.KeyDataResponseDto;
import com.czertainly.api.model.connector.cryptography.key.KeyPairDataResponseDto;
import com.czertainly.api.model.core.audit.ObjectType;
import com.czertainly.api.model.core.audit.OperationType;
import com.czertainly.api.model.core.auth.Resource;
import com.czertainly.api.model.core.connector.ConnectorDto;
import com.czertainly.api.model.core.cryptography.key.*;
import com.czertainly.api.model.core.cryptography.tokenprofile.TokenProfileDetailDto;
import com.czertainly.api.model.core.search.SearchFieldDataByGroupDto;
import com.czertainly.api.model.core.search.SearchFieldDataDto;
import com.czertainly.api.model.core.search.SearchGroup;
import com.czertainly.core.aop.AuditLogged;
import com.czertainly.core.comparator.SearchFieldDataComparator;
import com.czertainly.core.dao.entity.*;
import com.czertainly.core.dao.repository.*;
import com.czertainly.core.enums.SearchFieldNameEnum;
import com.czertainly.core.model.SearchFieldObject;
import com.czertainly.core.model.auth.ResourceAction;
import com.czertainly.core.security.authz.ExternalAuthorization;
import com.czertainly.core.security.authz.SecuredParentUUID;
import com.czertainly.core.security.authz.SecuredUUID;
import com.czertainly.core.security.authz.SecurityFilter;
import com.czertainly.core.service.*;
import com.czertainly.core.util.AttributeDefinitionUtils;
import com.czertainly.core.util.CertificateUtil;
import com.czertainly.core.util.RequestValidatorHelper;
import com.czertainly.core.util.SearchHelper;
import com.czertainly.core.util.converter.Sql2PredicateConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class CryptographicKeyServiceImpl implements CryptographicKeyService {

    private static final Logger logger = LoggerFactory.getLogger(CryptographicKeyServiceImpl.class);

    @PersistenceContext
    private EntityManager entityManager;

    // --------------------------------------------------------------------------------
    // Services & API Clients
    // --------------------------------------------------------------------------------
    private AttributeService attributeService;
    private MetadataService metadataService;
    private KeyManagementApiClient keyManagementApiClient;
    private TokenInstanceService tokenInstanceService;
    private CryptographicKeyEventHistoryService keyEventHistoryService;
    private PermissionEvaluator permissionEvaluator;
    private CertificateService certificateService;
    // --------------------------------------------------------------------------------
    // Repositories
    // --------------------------------------------------------------------------------
    private CryptographicKeyRepository cryptographicKeyRepository;
    private CryptographicKeyItemRepository cryptographicKeyItemRepository;
    private TokenProfileRepository tokenProfileRepository;
    private TokenInstanceReferenceRepository tokenInstanceReferenceRepository;
    private GroupRepository groupRepository;

    @Autowired
    private AttributeContentRepository attributeContentRepository;

    // Permitted usages for the keys
    private static final Map<KeyType, KeyUsage[]> PERMITTED_USAGES = new HashMap() {{
        put(KeyType.PRIVATE_KEY, new KeyUsage[]{KeyUsage.SIGN, KeyUsage.DECRYPT, KeyUsage.UNWRAP});
        put(KeyType.PUBLIC_KEY, new KeyUsage[]{KeyUsage.VERIFY, KeyUsage.ENCRYPT, KeyUsage.WRAP});
        put(KeyType.SECRET_KEY, KeyUsage.values());
        put(KeyType.SPLIT_KEY, KeyUsage.values());
    }};


    @Autowired
    public void setAttributeService(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @Autowired
    public void setMetadataService(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Autowired
    public void setKeyManagementApiClient(KeyManagementApiClient keyManagementApiClient) {
        this.keyManagementApiClient = keyManagementApiClient;
    }

    @Autowired
    public void setTokenInstanceService(TokenInstanceService tokenInstanceService) {
        this.tokenInstanceService = tokenInstanceService;
    }

    @Autowired
    public void setKeyEventHistoryService(CryptographicKeyEventHistoryService keyEventHistoryService) {
        this.keyEventHistoryService = keyEventHistoryService;
    }

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Autowired
    public void setPermissionEvaluator(PermissionEvaluator permissionEvaluator) {
        this.permissionEvaluator = permissionEvaluator;
    }

    @Autowired
    public void setCryptographicKeyItemRepository(CryptographicKeyItemRepository cryptographicKeyItemRepository) {
        this.cryptographicKeyItemRepository = cryptographicKeyItemRepository;
    }

    @Autowired
    public void setCryptographicKeyRepository(CryptographicKeyRepository cryptographicKeyRepository) {
        this.cryptographicKeyRepository = cryptographicKeyRepository;
    }

    @Autowired
    public void setCryptographicKeyContentRepository(CryptographicKeyItemRepository cryptographicKeyItemRepository) {
        this.cryptographicKeyItemRepository = cryptographicKeyItemRepository;
    }

    @Autowired
    public void setTokenProfileRepository(TokenProfileRepository tokenProfileRepository) {
        this.tokenProfileRepository = tokenProfileRepository;
    }

    @Autowired
    public void setGroupRepository(GroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    @Autowired
    public void setTokenInstanceReferenceRepository(TokenInstanceReferenceRepository tokenInstanceReferenceRepository) {
        this.tokenInstanceReferenceRepository = tokenInstanceReferenceRepository;
    }

    // ----------------------------------------------------------------------------------------------
    // Service Implementations
    // ----------------------------------------------------------------------------------------------

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.LIST, parentResource = Resource.TOKEN_PROFILE, parentAction = ResourceAction.LIST)
    public CryptographicKeyResponseDto listCryptographicKeys(SecurityFilter filter, SearchRequestDto request) {
        filter.setParentRefProperty("tokenInstanceReferenceUuid");
        RequestValidatorHelper.revalidateSearchRequestDto(request);

        final List<UUID> objectUUIDs = new ArrayList<>();
        if (!request.getFilters().isEmpty()) {
            final List<SearchFieldObject> searchFieldObjects = new ArrayList<>();
            searchFieldObjects.addAll(getSearchFieldObjectForMetadata());
            searchFieldObjects.addAll(getSearchFieldObjectForCustomAttributes());

            final Sql2PredicateConverter.CriteriaQueryDataObject criteriaQueryDataObject = Sql2PredicateConverter.prepareQueryToSearchIntoAttributes(searchFieldObjects, request.getFilters(), entityManager.getCriteriaBuilder(), Resource.CRYPTOGRAPHIC_KEY);
            objectUUIDs.addAll(cryptographicKeyRepository.findUsingSecurityFilterByCustomCriteriaQuery(filter, criteriaQueryDataObject.getRoot(), criteriaQueryDataObject.getCriteriaQuery(), criteriaQueryDataObject.getPredicate()));
        }

        final Pageable p = PageRequest.of(request.getPageNumber() - 1, request.getItemsPerPage());
        final List<KeyItemDto> listedKeyDtos = cryptographicKeyItemRepository.findUsingSecurityFilter(filter, (root, cb) -> Sql2PredicateConverter.mapSearchFilter2Predicates(request.getFilters(), cb, root, objectUUIDs), p, (root, cb) -> cb.desc(root.get("cryptographicKey").get("created")))
                .stream()
                .map(CryptographicKeyItem::mapToSummaryDto)
                .collect(Collectors.toList());

        final Long maxItems = cryptographicKeyItemRepository.countUsingSecurityFilter(filter, (root, cb) -> Sql2PredicateConverter.mapSearchFilter2Predicates(request.getFilters(), cb, root));
        final CryptographicKeyResponseDto responseDto = new CryptographicKeyResponseDto();
        responseDto.setCryptographicKeys(listedKeyDtos);
        responseDto.setItemsPerPage(request.getItemsPerPage());
        responseDto.setPageNumber(request.getPageNumber());
        responseDto.setTotalItems(maxItems);
        responseDto.setTotalPages((int) Math.ceil((double) maxItems / request.getItemsPerPage()));
        return responseDto;
    }

    @Override
    public List<SearchFieldDataByGroupDto> getSearchableFieldInformation() {
        return getSearchableFieldsMap();
    }


    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.LIST, parentResource = Resource.TOKEN, parentAction = ResourceAction.LIST)
    public List<KeyDto> listKeyPairs(Optional<String> tokenProfileUuid, SecurityFilter filter) {
        logger.info("Requesting key list for Token profile with UUID {}", tokenProfileUuid);
        filter.setParentRefProperty("tokenInstanceReferenceUuid");
        List<KeyDto> response = cryptographicKeyRepository.findUsingSecurityFilter(filter, null, null, (root, cb) -> cb.desc(root.get("created")))
                .stream()
                .map(CryptographicKey::mapToDto)
                .collect(Collectors.toList()
                );
        if (tokenProfileUuid.isPresent() && !tokenProfileUuid.get().isEmpty()) {
            response = response.stream().filter(e -> e.getTokenProfileUuid() != null && e.getTokenProfileUuid().equals(tokenProfileUuid.get())).collect(Collectors.toList());
        }
        response = response
                .stream()
                .filter(
                        e -> e.getItems().size() == 2
                ).filter(
                        e -> e.getItems().stream().filter(i -> i.getState().equals(KeyState.ACTIVE)).count() == 2
                )
                .filter(
                        e -> {
                            List<KeyType> keyTypes = e.getItems().stream().map(KeyItemDto::getType).collect(Collectors.toList());
                            keyTypes.removeAll(List.of(KeyType.PUBLIC_KEY, KeyType.PRIVATE_KEY));
                            return keyTypes.isEmpty();
                        }
                ).collect(Collectors.toList());
        return response;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DETAIL, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public KeyDetailDto getKey(SecuredParentUUID tokenInstanceUuid, String uuid) throws NotFoundException {
        logger.info("Requesting details of the Key with UUID {} for Token profile {}", uuid, tokenInstanceUuid);
        CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(uuid));
        KeyDetailDto dto = key.mapToDetailDto();
        logger.debug("Key details: {}", dto);
        dto.setCustomAttributes(
                attributeService.getCustomAttributesWithValues(
                        key.getUuid(),
                        Resource.CRYPTOGRAPHIC_KEY
                )
        );
        dto.getItems().forEach(k -> k.setMetadata(
                metadataService.getFullMetadata(
                        UUID.fromString(k.getUuid()),
                        Resource.CRYPTOGRAPHIC_KEY
                )
        ));
        logger.debug("Key details with attributes {}", dto);
        return dto;
    }


    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DETAIL, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public KeyItemDetailDto getKeyItem(SecuredParentUUID tokenInstanceUuid, String uuid, String keyItemUuid) throws NotFoundException {
        logger.info("Requesting details of the Key Item {} with UUID {} for Token profile {}", keyItemUuid, uuid, tokenInstanceUuid);
        CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(uuid));
        CryptographicKeyItem item = cryptographicKeyItemRepository.findByUuidAndCryptographicKey(
                UUID.fromString(keyItemUuid),
                key
        ).orElseThrow(
                () -> new NotFoundException(CryptographicKeyItem.class, keyItemUuid)
        );
        KeyItemDetailDto dto = item.mapToDto();
        logger.debug("Key details: {}", dto);
        dto.setMetadata(
                metadataService.getFullMetadata(
                        key.getTokenInstanceReference().getConnectorUuid(),
                        Resource.CRYPTOGRAPHIC_KEY
                )
        );
        logger.debug("Key details with attributes {}", dto);
        return dto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.CREATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public KeyDetailDto createKey(UUID tokenInstanceUuid, SecuredParentUUID tokenProfileUuid, KeyRequestType type, KeyRequestDto request) throws AlreadyExistException, ValidationException, ConnectorException {
        logger.error("Creating a new key for Token profile {}. Input: {}", tokenProfileUuid, request);
        if (cryptographicKeyRepository.findByName(request.getName()).isPresent()) {
            logger.error("Key with same name already exists");
            throw new AlreadyExistException("Existing Key with same already exists");
        }
        if (request.getName() == null) {
            logger.error("Name is empty. Cannot create key without name");
            throw new ValidationException(ValidationError.create("Name is required for creating a new Key"));
        }
        TokenInstanceReference tokenInstanceReference = tokenInstanceService.getTokenInstanceEntity(SecuredUUID.fromUUID(tokenInstanceUuid));
        TokenProfile tokenProfile = tokenProfileRepository.findByUuid(
                        tokenProfileUuid)
                .orElseThrow(
                        () -> new NotFoundException(
                                TokenInstanceReference.class,
                                tokenProfileUuid
                        )
                );
        TokenProfileDetailDto dto = tokenProfile.mapToDetailDto();
        logger.debug("Token instance detail: {}", tokenInstanceReference);
        Connector connector = tokenInstanceReference.getConnector();
        logger.debug("Connector details: {}", connector);
        List<DataAttribute> attributes = mergeAndValidateAttributes(
                type,
                tokenInstanceReference,
                request.getAttributes()
        );
        logger.debug("Merged attributes for the request: {}", attributes);
        CreateKeyRequestDto createKeyRequestDto = new CreateKeyRequestDto();
        createKeyRequestDto.setCreateKeyAttributes(
                AttributeDefinitionUtils.getClientAttributes(
                        attributes
                )
        );
        createKeyRequestDto.setTokenProfileAttributes(
                AttributeDefinitionUtils.getClientAttributes(
                        dto.getAttributes()
                )
        );

        CryptographicKey key;
        if (type.equals(KeyRequestType.KEY_PAIR)) {
            key = createKeyTypeOfKeyPair(
                    connector,
                    tokenProfile,
                    request,
                    createKeyRequestDto,
                    attributes
            );
        } else {
            key = createKeyTypeOfSecret(
                    connector,
                    tokenProfile,
                    request,
                    createKeyRequestDto,
                    attributes
            );
        }

        attributeService.createAttributeContent(
                key.getUuid(),
                request.getCustomAttributes(),
                Resource.CRYPTOGRAPHIC_KEY
        );

        logger.debug("Key creation is successful. UUID is {}", key.getUuid());
        KeyDetailDto keyDetailDto = key.mapToDetailDto();
        keyDetailDto.setCustomAttributes(
                attributeService.getCustomAttributesWithValues(
                        key.getUuid(),
                        Resource.CRYPTOGRAPHIC_KEY
                )
        );
        keyDetailDto.setCustomAttributes(
                attributeService.getCustomAttributesWithValues(
                        key.getUuid(),
                        Resource.CRYPTOGRAPHIC_KEY
                )
        );
        logger.debug("Key details: {}", keyDetailDto);
        return keyDetailDto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public KeyDetailDto editKey(SecuredParentUUID tokenInstanceUuid, UUID uuid, EditKeyRequestDto request) throws NotFoundException {
        logger.info("Updating the key with uuid {}. Request: {}", uuid, request);
        CryptographicKey key = getCryptographicKeyEntity(uuid);
        if (request.getName() != null && !request.getName().isEmpty()) key.setName(request.getName());
        if (request.getDescription() != null) key.setDescription(request.getDescription());
        if (request.getOwner() != null) key.setOwner(request.getOwner());
        if (request.getGroupUuid() != null) key.setGroupUuid(UUID.fromString(request.getGroupUuid()));
        if (request.getTokenProfileUuid() != null) {
            TokenProfile tokenProfile = tokenProfileRepository.findByUuid(
                            SecuredUUID.fromString(request.getTokenProfileUuid()))
                    .orElseThrow(
                            () -> new NotFoundException(
                                    TokenInstanceReference.class,
                                    request.getTokenProfileUuid()
                            )
                    );
            if (!tokenProfile.getTokenInstanceReferenceUuid().equals(key.getTokenInstanceReferenceUuid())) {
                throw new ValidationException(
                        ValidationError.create(
                                "Cannot assign Token Profile from different provider"
                        )
                );
            }
            key.setTokenProfile(tokenProfile);
        }
        cryptographicKeyRepository.save(key);
        logger.debug("Key details updated. Key: {}", key);
        return getKey(tokenInstanceUuid, uuid.toString());
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.DISABLE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.ENABLE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void disableKey(SecuredParentUUID tokenInstanceUuid, UUID uuid, List<String> keyUuids) throws NotFoundException, ValidationException {
        logger.info("Request to disable the key with UUID {} on token instance {}", uuid, tokenInstanceUuid);
        if (keyUuids != null && !keyUuids.isEmpty()) {
            for (String keyUuid : new LinkedHashSet<>(keyUuids)) {
                disableKeyItem(UUID.fromString(keyUuid), false);
            }
        } else {
            disableKey(List.of(uuid.toString()));
        }
        logger.info("Key disabled: {}", uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.ENABLE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.ENABLE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void enableKey(SecuredParentUUID tokenInstanceUuid, UUID uuid, List<String> keyUuids) throws NotFoundException, ValidationException {
        logger.info("Request to enable the key with UUID {} on token instance {}", uuid, tokenInstanceUuid);

        if (keyUuids != null && !keyUuids.isEmpty()) {
            for (String keyUuid : new LinkedHashSet<>(keyUuids)) {
                enableKeyItem(UUID.fromString(keyUuid), false);
            }
        } else {
            enableKey(List.of(uuid.toString()));
        }
        logger.info("Key disabled: {}", uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.DISABLE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.ENABLE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void disableKey(List<String> uuids) {
        logger.info("Request to disable the key with UUID {} ", uuids);
        for (String keyUuid : new LinkedHashSet<>(uuids)) {
            try {
                CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(keyUuid));
                for (CryptographicKeyItem item : key.getItems()) {
                    disableKeyItem(item.getUuid(), true);
                }
            } catch (NotFoundException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key disabled: {}", uuids);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.ENABLE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.ENABLE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void enableKey(List<String> uuids) {
        logger.info("Request to enable the key with UUID {} ", uuids);
        for (String keyUuid : new LinkedHashSet<>(uuids)) {
            try {
                CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(keyUuid));
                for (CryptographicKeyItem item : key.getItems()) {
                    enableKeyItem(item.getUuid(), true);
                }
            } catch (NotFoundException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key disabled: {}", uuids);
    }

    @Override
    public void enableKeyItems(List<String> uuids) {
        logger.info("Request to enable the key Item with UUID {} ", uuids);
        for (String keyItemUuid : new LinkedHashSet<>(uuids)) {
            try {
                enableKeyItem(UUID.fromString(keyItemUuid), true);
            } catch (NotFoundException | ValidationException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key Items enabled: {}", uuids);
    }

    @Override
    public void disableKeyItems(List<String> uuids) {
        logger.info("Request to disable the key Item with UUID {} ", uuids);
        for (String keyItemUuid : new LinkedHashSet<>(uuids)) {
            try {
                disableKeyItem(UUID.fromString(keyItemUuid), true);
            } catch (NotFoundException | ValidationException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key Items disabled: {}", uuids);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DELETE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void deleteKey(SecuredParentUUID tokenInstanceUuid, UUID uuid, List<String> keyUuids) throws NotFoundException {
        logger.info("Request to deleted the key with UUID {} on token instance {}", uuid, tokenInstanceUuid);
        CryptographicKey key = getCryptographicKeyEntity(uuid);
        if (key.getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(key.getTokenProfile().getSecuredUuid());
        }
        if (keyUuids != null && !keyUuids.isEmpty()) {
            for (String keyUuid : new LinkedHashSet<>(keyUuids)) {
                CryptographicKeyItem content = cryptographicKeyItemRepository
                        .findByUuid(UUID.fromString(keyUuid))
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Sub key with the UUID " + keyUuid + " is not found",
                                        CryptographicKeyItem.class
                                )
                        );
                attributeService.deleteAttributeContent(
                        key.getUuid(),
                        Resource.CRYPTOGRAPHIC_KEY
                );
                key.getItems().remove(content);
                cryptographicKeyItemRepository.delete(content);
                cryptographicKeyRepository.save(key);
            }
            if (key.getItems().size() == 0) {
                certificateService.clearKeyAssociations(key.getUuid());
                cryptographicKeyRepository.delete(key);
            }
        } else {
            deleteKey(List.of(uuid.toString()));
        }
        logger.info("Key deleted: {}", uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DELETE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void deleteKey(List<String> uuids) {
        logger.info("Request to deleted the keys with UUIDs {}", uuids);
        for (String uuid : uuids) {
            try {
                CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(uuid));
                if (key.getTokenProfile() != null) {
                    permissionEvaluator.tokenProfile(key.getTokenProfile().getSecuredUuid());
                }
                for (CryptographicKeyItem content : key.getItems()) {
                    attributeService.deleteAttributeContent(
                            key.getUuid(),
                            Resource.CRYPTOGRAPHIC_KEY
                    );
                    cryptographicKeyItemRepository.delete(content);
                }
                certificateService.clearKeyAssociations(UUID.fromString(uuid));
                cryptographicKeyRepository.delete(key);
            } catch (NotFoundException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Keys deleted: {}", uuids);
    }

    @Override
    public void deleteKeyItems(List<String> keyItemUuids) throws ConnectorException {
        logger.info("Request to deleted the key items with UUIDs {}", keyItemUuids);
        for (String uuid : keyItemUuids) {
            try {
                CryptographicKeyItem keyItem = getCryptographicKeyItem(UUID.fromString(uuid));
                CryptographicKey key = keyItem.getCryptographicKey();
                permissionEvaluator.tokenInstance(keyItem.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
                if (keyItem.getCryptographicKey().getTokenProfile() != null) {
                    permissionEvaluator.tokenProfile(keyItem.getCryptographicKey().getTokenProfile().getSecuredUuid());
                }
                cryptographicKeyItemRepository.delete(keyItem);
                key.getItems().remove(keyItem);
                if (key.getItems().size() == 0) {
                    certificateService.clearKeyAssociations(key.getUuid());
                    cryptographicKeyRepository.delete(key);
                }
            } catch (NotFoundException e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key Items deleted: {}", keyItemUuids);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DELETE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void destroyKey(SecuredParentUUID tokenInstanceUuid, String uuid, List<String> keyUuids) throws ConnectorException {
        logger.info("Request to destroy the key with UUID {} on token profile {}", uuid, tokenInstanceUuid);
        CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(uuid));
        if (keyUuids != null && !keyUuids.isEmpty()) {
            for (String keyUuid : new LinkedHashSet<>(keyUuids)) {
                destroyKeyItem(
                        UUID.fromString(keyUuid),
                        key.getTokenInstanceReference().getTokenInstanceUuid(),
                        key.getTokenInstanceReference().getConnector().mapToDto(),
                        false
                );
            }
        } else {
            destroyKey(List.of(uuid));
        }
        logger.info("Key destroyed: {}", uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.DELETE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void destroyKey(List<String> uuids) throws ConnectorException {
        logger.info("Request to destroy the key with UUIDs {}", uuids);
        // Iterate through the keys
        for (String uuid : uuids) {
            CryptographicKey key = getCryptographicKeyEntity(UUID.fromString(uuid));

            for (CryptographicKeyItem content : key.getItems()) {
                try {
                    destroyKeyItem(
                            content.getUuid(),
                            key.getTokenInstanceReference().getTokenInstanceUuid(),
                            key.getTokenInstanceReference().getConnector().mapToDto(),
                            true
                    );
                } catch (Exception e) {
                    logger.warn(e.getLocalizedMessage());
                }
            }

        }
        logger.info("Key destroyed: {}", uuids);
    }

    @Override
    public void destroyKeyItems(List<String> keyItemUuids) throws ConnectorException {
        logger.info("Request to destroy the key items with UUIDs {}", keyItemUuids);
        // Iterate through the keys
        for (String uuid : keyItemUuids) {
            CryptographicKeyItem keyItem = getCryptographicKeyItem(UUID.fromString(uuid));
            try {
                destroyKeyItem(
                        keyItem.getUuid(),
                        keyItem.getCryptographicKey().getTokenInstanceReference().getTokenInstanceUuid(),
                        keyItem.getCryptographicKey().getTokenInstanceReference().getConnector().mapToDto(),
                        true
                );
            } catch (Exception e) {
                logger.warn(e.getLocalizedMessage());
            }
        }
        logger.info("Key Items destroyed: {}", keyItemUuids);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.ANY, parentResource = Resource.TOKEN_PROFILE, parentAction = ResourceAction.DETAIL)
    public List<BaseAttribute> listCreateKeyAttributes(UUID tokenInstanceUuid, SecuredParentUUID tokenProfileUuid, KeyRequestType type) throws ConnectorException {
        logger.info("Request to list the attributes for creating a new key on Token profile: {}", tokenProfileUuid);
        TokenProfile tokenProfile = tokenProfileRepository.findByUuid(
                        tokenProfileUuid.getValue())
                .orElseThrow(
                        () -> new NotFoundException(
                                TokenInstanceReference.class,
                                tokenProfileUuid
                        )
                );
        logger.debug("Token profile details: {}", tokenProfile);
        List<BaseAttribute> attributes;
        if (type.equals(KeyRequestType.KEY_PAIR)) {
            attributes = keyManagementApiClient.listCreateKeyPairAttributes(
                    tokenProfile.getTokenInstanceReference().getConnector().mapToDto(),
                    tokenProfile.getTokenInstanceReference().getTokenInstanceUuid()
            );
        } else {
            attributes = keyManagementApiClient.listCreateSecretKeyAttributes(
                    tokenProfile.getTokenInstanceReference().getConnector().mapToDto(),
                    tokenProfile.getTokenInstanceReference().getTokenInstanceUuid()
            );
        }
        logger.debug("Attributes for the new creation: {}", attributes);
        return attributes;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void syncKeys(SecuredParentUUID tokenInstanceUuid) throws ConnectorException {
        TokenInstanceReference tokenInstanceReference = tokenInstanceService.getTokenInstanceEntity(
                tokenInstanceUuid
        );
        //Create a map to hold the key and its objects. The association key will be used as the name for the parent key object
        Map<String, List<KeyDataResponseDto>> associations = new HashMap<>();
        // Get the list of keys from the connector
        List<KeyDataResponseDto> keys = keyManagementApiClient.listKeys(
                tokenInstanceReference.getConnector().mapToDto(),
                tokenInstanceReference.getTokenInstanceUuid()
        );

        // Iterate and add the keys with the same associations to the map
        for (KeyDataResponseDto key : keys) {
            associations.computeIfAbsent(
                    (key.getAssociation() == null || key.getAssociation().isEmpty()) ? "" : key.getAssociation(),
                    k -> new ArrayList<>()
            ).add(key);
        }
        logger.debug("Total number of keys from the connector: {}", keys.size());

        // Iterate through the created map and store the items in the database
        for (Map.Entry<String, List<KeyDataResponseDto>> entry : associations.entrySet()) {
            // If the key is empty then it is individual entity. Probably only private or public key or Secret Key
            if (entry.getKey().equals("")) {
                for (KeyDataResponseDto soleEntity : entry.getValue()) {
                    createKeyAndItems(
                            tokenInstanceReference.getConnectorUuid(),
                            tokenInstanceReference,
                            soleEntity.getName(),
                            List.of(soleEntity)
                    );
                }
            } else {
                createKeyAndItems(
                        tokenInstanceReference.getConnectorUuid(),
                        tokenInstanceReference,
                        entry.getKey(),
                        entry.getValue()
                );
            }
        }
        logger.info("Sync Key Completed");
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void compromiseKey(SecuredParentUUID tokenInstanceUuid, UUID uuid, CompromiseKeyRequestDto request) throws NotFoundException {
        logger.info("Request to compromise the key with UUID {} on token instance {}", uuid, tokenInstanceUuid);
        List<UUID> keyUuids = request.getUuids();
        if (keyUuids != null && !keyUuids.isEmpty()) {
            for (UUID keyUuid : new LinkedHashSet<>(keyUuids)) {
                compromiseKeyItem(keyUuid, request.getReason(), false);
            }
        } else {
            compromiseKey(new BulkCompromiseKeyRequestDto(request.getReason(), List.of(uuid)));
        }
        logger.info("Key marked as compromised: {}", uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void compromiseKey(BulkCompromiseKeyRequestDto request) {
        List<UUID> uuids = request.getUuids();
        logger.info("Request to mark the key as compromised with UUIDs {}", uuids);
        // Iterate through the keys
        for (UUID uuid : uuids) {
            try {
                CryptographicKey key = getCryptographicKeyEntity(uuid);

                for (CryptographicKeyItem content : key.getItems()) {
                    compromiseKeyItem(content.getUuid(), request.getReason(), true);
                }
            } catch (Exception e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key marked as compromised: {}", uuids);
    }

    @Override
    public void compromiseKeyItems(BulkCompromiseKeyItemRequestDto request) {
        List<UUID> keyItemUuids = request.getUuids();
        logger.info("Request to mark the key items as compromised with UUIDs {}", keyItemUuids);
        // Iterate through the keys
        for (UUID uuid : keyItemUuids) {
            try {
                compromiseKeyItem(uuid, request.getReason(), true);
            } catch (Exception e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key Items marked as compromised: {}", keyItemUuids);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void updateKeyUsages(BulkKeyUsageRequestDto request) {
        logger.info("Request to mark the key as compromised with UUIDs {}", request.getUuids());
        // Iterate through the keys
        for (UUID uuid : request.getUuids()) {
            try {
                CryptographicKey key = getCryptographicKeyEntity(uuid);

                for (CryptographicKeyItem content : key.getItems()) {
                    updateKeyUsages(content.getUuid(), request.getUsage(), false);
                }
            } catch (Exception e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key usages updated: {}", request.getUuids());
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.CRYPTOGRAPHIC_KEY, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE, parentResource = Resource.TOKEN, parentAction = ResourceAction.DETAIL)
    public void updateKeyUsages(SecuredParentUUID tokenInstanceUuid, UUID uuid, UpdateKeyUsageRequestDto request) throws NotFoundException {
        logger.info("Request to update the key usages with UUID {} on token instance {}", uuid, tokenInstanceUuid);
        if (request.getUuids() != null && !request.getUuids().isEmpty()) {
            for (UUID keyUuid : new LinkedHashSet<>(request.getUuids())) {
                updateKeyUsages(keyUuid, request.getUsage(), true);
            }
        } else {
            BulkKeyUsageRequestDto requestDto = new BulkKeyUsageRequestDto();
            requestDto.setUsage(request.getUsage());
            requestDto.setUuids(List.of(uuid));
            updateKeyUsages(requestDto);
        }
        logger.info("Key usages updated: {}", uuid);
    }

    @Override
    public void updateKeyItemUsages(BulkKeyItemUsageRequestDto request) {
        logger.info("Request to mark the key as compromised with UUIDs {}", request.getUuids());
        // Iterate through the keys
        for (UUID uuid : request.getUuids()) {
            try {
                updateKeyUsages(uuid, request.getUsage(), false);
            } catch (Exception e) {
                logger.warn(e.getMessage());
            }
        }
        logger.info("Key items usages updated: {}", request.getUuids());
    }

    @Override
    public List<KeyEventHistoryDto> getEventHistory(SecuredParentUUID tokenInstanceUuid, UUID uuid, UUID keyItemUuid) throws NotFoundException {
        logger.info("Request to get the list of events for the key item");
        return keyEventHistoryService.getKeyEventHistory(keyItemUuid);
    }

    @Override
    public UUID findKeyByFingerprint(String fingerprint) {
        CryptographicKeyItem item = cryptographicKeyItemRepository.findByFingerprint(fingerprint).orElse(null);
        if (item != null) {
            return item.getCryptographicKey().getUuid();
        }
        return null;
    }

    @Override
    public List<NameAndUuidDto> listResourceObjects(SecurityFilter filter) {
        return null;
    }

    @Override
    @ExternalAuthorization(resource = Resource.CRYPTOGRAPHIC_KEY, action = ResourceAction.UPDATE)
    public void evaluatePermissionChain(SecuredUUID uuid) throws NotFoundException {
        getCryptographicKeyEntity(uuid.getValue());
    }

    private void createKeyAndItems(UUID connectorUuid, TokenInstanceReference tokenInstanceReference, String key, List<KeyDataResponseDto> items) {
        //Iterate through the items for a specific key
        if (checkKeyAlreadyExists(tokenInstanceReference.getUuid(), items)) {
            return;
        }
        // Create the cryptographic Key
        KeyRequestDto dto = new KeyRequestDto();
        dto.setName(key);
        dto.setDescription("Discovered from " + tokenInstanceReference.getName());
        CryptographicKey cryptographicKey = createKeyEntity(
                dto,
                null,
                tokenInstanceReference,
                List.of()
        );
        // Create the items for each key
        Set<CryptographicKeyItem> children = new HashSet<>();
        for (KeyDataResponseDto item : items) {
            children.add(
                    createKeyContent(
                            item.getUuid(),
                            item.getName(),
                            item.getKeyData(),
                            cryptographicKey,
                            connectorUuid,
                            true
                    )
            );
        }
        cryptographicKey.setItems(children);
        cryptographicKeyRepository.save(cryptographicKey);
    }

    private boolean checkKeyAlreadyExists(UUID tokenInstanceUuid, List<KeyDataResponseDto> items) {
        //Iterate through the items for a specific key
        for (KeyDataResponseDto item : items) {
            //check if the item with the reference uuid already exists in the database
            // Assumption - Content of the key from earlier does not change
            for (CryptographicKeyItem keyItem : cryptographicKeyItemRepository.findByKeyReferenceUuid(UUID.fromString(item.getUuid()))) {
                if (keyItem.getCryptographicKey().getTokenInstanceReferenceUuid().equals(tokenInstanceUuid)) {
                    return true;
                }
            }
        }
        return false;
    }

    private CryptographicKey createKeyEntity(KeyRequestDto request, TokenProfile tokenProfile, TokenInstanceReference tokenInstanceReference, List<DataAttribute> attributes) {
        CryptographicKey key = new CryptographicKey();
        key.setName(request.getName());
        key.setDescription(request.getDescription());
        key.setTokenProfile(tokenProfile);
        key.setTokenInstanceReference(tokenInstanceReference);
        key.setAttributes(AttributeDefinitionUtils.serialize(attributes));
        key.setOwner(request.getOwner());
        if (request.getGroupUuid() != null) key.setGroupUuid(UUID.fromString(request.getGroupUuid()));
        logger.debug("Cryptographic Key: {}", key);
        cryptographicKeyRepository.save(key);
        return key;
    }

    private CryptographicKeyItem createKeyContent(String referenceUuid, String referenceName, KeyData keyData, CryptographicKey cryptographicKey, UUID connectorUuid, boolean isDiscovered) {
        logger.info("Creating the Key Content for {}", cryptographicKey);
        CryptographicKeyItem content = new CryptographicKeyItem();
        content.setName(referenceName);
        content.setCryptographicKey(cryptographicKey);
        content.setType(keyData.getType());
        content.setCryptographicAlgorithm(keyData.getAlgorithm());
        content.setKeyData(keyData.getFormat(), keyData.getValue());
        content.setFormat(keyData.getFormat());
        content.setLength(keyData.getLength());
        content.setKeyReferenceUuid(UUID.fromString(referenceUuid));
        content.setState(KeyState.ACTIVE);
        content.setEnabled(false);
        if (cryptographicKey.getTokenProfile() != null) {
            content.setUsage(
                    cryptographicKey
                            .getTokenProfile()
                            .getUsage()
                            .stream()
                            .filter(
                                    List.of(
                                            PERMITTED_USAGES.get(keyData.getType())
                                    )::contains)
                            .collect(
                                    Collectors.toList()
                            )
            );
        }
        try {
            content.setFingerprint(CertificateUtil.getThumbprint(content.getKeyData().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | NullPointerException e) {
            logger.error("Failed to calculate the fingerprint {}", e.getMessage());
        }
        cryptographicKeyItemRepository.save(content);
        String message;
        if (isDiscovered) {
            message = "Key Discovered from Token Instance "
                    + cryptographicKey.getTokenInstanceReference().getName();
        } else {
            message = "Key Created from Token Profile "
                    + cryptographicKey.getTokenProfile().getName()
                    + " on Token Instance "
                    + cryptographicKey.getTokenInstanceReference().getName();
        }
        keyEventHistoryService.addEventHistory(
                KeyEvent.CREATE,
                KeyEventStatus.SUCCESS,
                message,
                null,
                content.getUuid()
        );

        metadataService.createMetadataDefinitions(
                connectorUuid,
                keyData.getMetadata()
        );
        metadataService.createMetadata(
                connectorUuid,
                UUID.fromString(content.getUuid().toString()),
                cryptographicKey.getUuid(),
                referenceName,
                keyData.getMetadata(),
                Resource.CRYPTOGRAPHIC_KEY,
                Resource.CRYPTOGRAPHIC_KEY
        );

        if (keyData.getType().equals(KeyType.PUBLIC_KEY)) {
            certificateService.updateCertificateKeys(cryptographicKey.getUuid(), content.getFingerprint());
        }

        return content;
    }

    private CryptographicKey getCryptographicKeyEntity(UUID uuid) throws NotFoundException {
        return cryptographicKeyRepository
                .findByUuid(uuid)
                .orElseThrow(
                        () -> new NotFoundException(
                                CryptographicKey.class,
                                uuid
                        )
                );
    }

    private List<DataAttribute> mergeAndValidateAttributes(KeyRequestType type, TokenInstanceReference tokenInstanceRef, List<RequestAttributeDto> attributes) throws ConnectorException {
        logger.debug("Merging and validating attributes on token profile {}. Request Attributes are: {}", tokenInstanceRef, attributes);
        List<BaseAttribute> definitions;
        if (type.equals(KeyRequestType.KEY_PAIR)) {
            definitions = keyManagementApiClient.listCreateKeyPairAttributes(
                    tokenInstanceRef.getConnector().mapToDto(),
                    tokenInstanceRef.getTokenInstanceUuid()
            );
        } else {
            definitions = keyManagementApiClient.listCreateSecretKeyAttributes(
                    tokenInstanceRef.getConnector().mapToDto(),
                    tokenInstanceRef.getTokenInstanceUuid()
            );
        }
        logger.debug("Attributes from connector: {}", definitions);
        List<String> existingAttributesFromConnector = definitions
                .stream()
                .map(BaseAttribute::getName)
                .collect(Collectors.toList());
        logger.debug("List of attributes from the connector: {}", existingAttributesFromConnector);
        for (RequestAttributeDto requestAttributeDto : attributes) {
            if (!existingAttributesFromConnector.contains(requestAttributeDto.getName())) {
                DataAttribute referencedAttribute = attributeService.getReferenceAttribute(
                        tokenInstanceRef.getConnectorUuid(),
                        requestAttributeDto.getName()
                );
                if (referencedAttribute != null) {
                    definitions.add(referencedAttribute);
                }
            }
        }

        List<DataAttribute> merged = AttributeDefinitionUtils.mergeAttributes(
                definitions,
                attributes
        );
        logger.debug("Merged attributes: {}", merged);

        if (type.equals(KeyRequestType.KEY_PAIR)) {
            keyManagementApiClient.validateCreateKeyPairAttributes(
                    tokenInstanceRef.getConnector().mapToDto(),
                    tokenInstanceRef.getTokenInstanceUuid(),
                    attributes
            );
        } else {
            keyManagementApiClient.validateCreateSecretKeyAttributes(
                    tokenInstanceRef.getConnector().mapToDto(),
                    tokenInstanceRef.getTokenInstanceUuid(),
                    attributes
            );
        }

        return merged;
    }

    private CryptographicKey createKeyTypeOfKeyPair(Connector connector, TokenProfile tokenProfile, KeyRequestDto request, CreateKeyRequestDto createKeyRequestDto, List<DataAttribute> attributes) throws ConnectorException {
        KeyPairDataResponseDto response = keyManagementApiClient.createKeyPair(
                connector.mapToDto(),
                tokenProfile.getTokenInstanceReference().getTokenInstanceUuid(),
                createKeyRequestDto
        );
        logger.debug("Response from the connector for the new Key creation: {}", response);
        Set<CryptographicKeyItem> children = new HashSet<>();
        CryptographicKey key = createKeyEntity(
                request,
                tokenProfile,
                tokenProfile.getTokenInstanceReference(),
                attributes
        );
        children.add(createKeyContent(
                response.getPrivateKeyData().getUuid(),
                response.getPrivateKeyData().getName(),
                response.getPrivateKeyData().getKeyData(),
                key,
                connector.getUuid(),
                false
        ));
        children.add(createKeyContent(
                response.getPublicKeyData().getUuid(),
                response.getPrivateKeyData().getName(),
                response.getPublicKeyData().getKeyData(),
                key,
                connector.getUuid(),
                false
        ));
        key.setItems(children);
        cryptographicKeyRepository.save(key);
        return key;
    }

    private CryptographicKey createKeyTypeOfSecret(Connector connector, TokenProfile tokenProfile, KeyRequestDto request, CreateKeyRequestDto createKeyRequestDto, List<DataAttribute> attributes) throws ConnectorException {
        KeyDataResponseDto response = keyManagementApiClient.createSecretKey(
                connector.mapToDto(),
                tokenProfile.getTokenInstanceReference().getTokenInstanceUuid(),
                createKeyRequestDto
        );
        logger.debug("Response from the connector for the new Key creation: {}", response);
        CryptographicKey key = createKeyEntity(
                request,
                tokenProfile,
                tokenProfile.getTokenInstanceReference(),
                attributes
        );
        key.setItems(
                Set.of(
                        createKeyContent(
                                response.getUuid(),
                                response.getName(),
                                response.getKeyData(),
                                key,
                                connector.getUuid(),
                                false
                        )
                )
        );
        cryptographicKeyRepository.save(key);
        return key;
    }

    /**
     * Function to enable the key
     *
     * @param uuid UUID of the Key Item
     */
    private void enableKeyItem(UUID uuid, boolean evaluateTokenPermission) throws NotFoundException {
        CryptographicKeyItem content = getCryptographicKeyItem(uuid);
        if (content.getCryptographicKey().getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(content.getCryptographicKey().getTokenProfile().getSecuredUuid());
        }
        if (evaluateTokenPermission) {
            permissionEvaluator.tokenInstance(content.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
        }
        if (content.isEnabled()) {
            keyEventHistoryService.addEventHistory(KeyEvent.ENABLE, KeyEventStatus.FAILED,
                    "Key is already enabled", null, content);
            throw new ValidationException(
                    ValidationError.create(
                            "Key is already enabled"
                    )
            );
        }
        content.setEnabled(true);
        cryptographicKeyItemRepository.save(content);
        keyEventHistoryService.addEventHistory(KeyEvent.ENABLE, KeyEventStatus.SUCCESS,
                "Enable Key", null, content);
    }

    /**
     * Function to disable the key
     *
     * @param uuid UUID of the Key Item
     */
    private void disableKeyItem(UUID uuid, boolean evaluateTokenPermission) throws NotFoundException {
        CryptographicKeyItem content = getCryptographicKeyItem(uuid);
        if (content.getCryptographicKey().getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(content.getCryptographicKey().getTokenProfile().getSecuredUuid());
        }
        if (evaluateTokenPermission) {
            permissionEvaluator.tokenInstance(content.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
        }
        if (!content.isEnabled()) {
            keyEventHistoryService.addEventHistory(KeyEvent.DISABLE, KeyEventStatus.FAILED,
                    "Key is already disabled", null, content);
            throw new ValidationException(
                    ValidationError.create(
                            "Key is already disabled"
                    )
            );
        }
        content.setEnabled(false);
        cryptographicKeyItemRepository.save(content);
        keyEventHistoryService.addEventHistory(KeyEvent.DISABLE, KeyEventStatus.SUCCESS,
                "Disable Key", null, content);
    }

    /**
     * Function to mark a key as compromised
     *
     * @param uuid UUID of the Key Item
     */
    private void compromiseKeyItem(UUID uuid, KeyCompromiseReason reason, boolean evaluateTokenPermission) throws NotFoundException {
        CryptographicKeyItem content = getCryptographicKeyItem(uuid);
        if (content.getCryptographicKey().getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(content.getCryptographicKey().getTokenProfile().getSecuredUuid());
        }
        if (evaluateTokenPermission) {
            permissionEvaluator.tokenInstance(content.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
        }
        if (content.getState().equals(KeyState.COMPROMISED) || content.getState().equals(KeyState.DESTROYED)) {
            keyEventHistoryService.addEventHistory(KeyEvent.COMPROMISED, KeyEventStatus.FAILED,
                    "Key is already " + content.getState(), null, content);
            throw new ValidationException(
                    ValidationError.create(
                            "Invalid Key state. Cannot compromise key since it is already " + content.getState()
                    )
            );
        }
        content.setState(KeyState.COMPROMISED);
        content.setReason(reason);
        cryptographicKeyItemRepository.save(content);
        keyEventHistoryService.addEventHistory(KeyEvent.COMPROMISED, KeyEventStatus.SUCCESS,
                "Compromised Key. Reason: " + reason, null, content);
    }

    /**
     * Function to update the usage of the key
     *
     * @param uuid UUID of the Key Item
     */
    private void updateKeyUsages(UUID uuid, List<KeyUsage> usages, boolean evaluateTokenPermission) throws NotFoundException {
        CryptographicKeyItem content = getCryptographicKeyItem(uuid);
        if (content.getCryptographicKey().getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(content.getCryptographicKey().getTokenProfile().getSecuredUuid());
        }
        if (evaluateTokenPermission) {
            permissionEvaluator.tokenInstance(content.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
        }
        usages = new ArrayList<>(usages);
        if (!new HashSet<>(List.of(PERMITTED_USAGES.get(content.getType()))).containsAll(usages)) {
            usages.removeAll(List.of(PERMITTED_USAGES.get(content.getType())));
            String nonAllowedUsages = String.join(", ", usages.stream().map(KeyUsage::getCode).collect(Collectors.toList()));
            keyEventHistoryService.addEventHistory(KeyEvent.UPDATE_USAGE, KeyEventStatus.FAILED,
                    "Unsupported Key usages: " + nonAllowedUsages, null, content);
            throw new ValidationException(
                    ValidationError.create(
                            "Unsupported Key usages: " + nonAllowedUsages
                    )
            );
        }
        String oldUsage = String.join(", ", content.getUsage().stream().map(KeyUsage::getCode).collect(Collectors.toList()));
        content.setUsage(usages);
        cryptographicKeyItemRepository.save(content);
        String newUsage = String.join(", ", usages.stream().map(KeyUsage::getCode).collect(Collectors.toList()));
        keyEventHistoryService.addEventHistory(KeyEvent.UPDATE_USAGE, KeyEventStatus.SUCCESS,
                "Update Key Usage from " + oldUsage + " to " + newUsage, null, content);
    }

    /**
     * Function to destroy the key
     *
     * @param uuid UUID of the Key Item
     */
    private void destroyKeyItem(UUID uuid, String tokenInstanceUuid, ConnectorDto connectorDto, boolean evaluateTokenPermission) throws ConnectorException {
        CryptographicKeyItem content = getCryptographicKeyItem(uuid);
        if (content.getCryptographicKey().getTokenProfile() != null) {
            permissionEvaluator.tokenProfile(content.getCryptographicKey().getTokenProfile().getSecuredUuid());
        }
        if (evaluateTokenPermission) {
            permissionEvaluator.tokenInstance(content.getCryptographicKey().getTokenInstanceReference().getSecuredUuid());
        }
        if (content.getState().equals(KeyState.DESTROYED)) {
            keyEventHistoryService.addEventHistory(KeyEvent.DESTROY, KeyEventStatus.FAILED,
                    "Key is already destroyed", null, content);
            throw new ValidationException(
                    ValidationError.create(
                            "Key " + uuid.toString() + " is already destroyed"
                    )
            );
        }
        keyManagementApiClient.destroyKey(
                connectorDto,
                tokenInstanceUuid,
                content.getKeyReferenceUuid().toString()
        );
        logger.info("Key destroyed in the connector. Removing from the core now");
        content.setKeyData(null);
        content.setState(KeyState.DESTROYED);
        cryptographicKeyItemRepository.save(content);
        keyEventHistoryService.addEventHistory(KeyEvent.COMPROMISED, KeyEventStatus.SUCCESS,
                "Destroy Key", null, content);
    }

    private CryptographicKeyItem getCryptographicKeyItem(UUID uuid) throws NotFoundException {
        return cryptographicKeyItemRepository
                .findByUuid(uuid)
                .orElseThrow(
                        () -> new NotFoundException(
                                CryptographicKeyItem.class,
                                uuid
                        )
                );
    }

    private List<SearchFieldDataByGroupDto> getSearchableFieldsMap() {

        final List<SearchFieldDataByGroupDto> searchFieldDataByGroupDtos = new ArrayList<>();

        final List<SearchFieldObject> metadataSearchFieldObject = getSearchFieldObjectForMetadata();
        if (metadataSearchFieldObject.size() > 0) {
            searchFieldDataByGroupDtos.add(new SearchFieldDataByGroupDto(SearchHelper.prepareSearchForJSON(metadataSearchFieldObject), SearchGroup.META));
        }

        final List<SearchFieldObject> customAttrSearchFieldObject = getSearchFieldObjectForCustomAttributes();
        if (customAttrSearchFieldObject.size() > 0) {
            searchFieldDataByGroupDtos.add(new SearchFieldDataByGroupDto(SearchHelper.prepareSearchForJSON(customAttrSearchFieldObject), SearchGroup.CUSTOM));
        }

        List<SearchFieldDataDto> fields = List.of(
                SearchHelper.prepareSearch(SearchFieldNameEnum.NAME),
                SearchHelper.prepareSearch(SearchFieldNameEnum.CK_GROUP, groupRepository.findAll().stream().map(Group::getName).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.CK_OWNER),
                SearchHelper.prepareSearch(SearchFieldNameEnum.CK_KEY_USAGE, Arrays.stream((KeyUsage.values())).map(KeyUsage::getCode).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_LENGTH),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_STATE, Arrays.stream((KeyState.values())).map(KeyState::getCode).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_FORMAT, Arrays.stream((KeyFormat.values())).map(KeyFormat::getCode).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_TYPE, Arrays.stream((KeyType.values())).map(KeyType::getCode).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_CRYPTOGRAPHIC_ALGORITHM, Arrays.stream((CryptographicAlgorithm.values())).map(CryptographicAlgorithm::getCode).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_TOKEN_PROFILE, tokenProfileRepository.findAll().stream().map(TokenProfile::getName).collect(Collectors.toList())),
                SearchHelper.prepareSearch(SearchFieldNameEnum.KEY_TOKEN_INSTANCE_LABEL, tokenInstanceReferenceRepository.findAll().stream().map(TokenInstanceReference::getName).collect(Collectors.toList()))
        );
        fields = fields.stream().collect(Collectors.toList());
        fields.sort(new SearchFieldDataComparator());
        searchFieldDataByGroupDtos.add(new SearchFieldDataByGroupDto(fields, SearchGroup.PROPERTY));

        logger.debug("Searchable CryptographicKey Fields groups: {}", searchFieldDataByGroupDtos);
        return searchFieldDataByGroupDtos;
    }

    private List<SearchFieldObject> getSearchFieldObjectForMetadata() {
        return attributeContentRepository.findDistinctAttributeContentNamesByAttrTypeAndObjType(Resource.CRYPTOGRAPHIC_KEY, AttributeType.META);
    }

    private List<SearchFieldObject> getSearchFieldObjectForCustomAttributes() {
        return attributeContentRepository.findDistinctAttributeContentNamesByAttrTypeAndObjType(Resource.CRYPTOGRAPHIC_KEY, AttributeType.CUSTOM);
    }

}
