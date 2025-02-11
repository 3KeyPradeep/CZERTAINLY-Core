package com.czertainly.core.service.impl;

import com.czertainly.api.clients.AuthorityInstanceApiClient;
import com.czertainly.api.exception.*;
import com.czertainly.api.model.client.attribute.RequestAttributeDto;
import com.czertainly.api.model.client.compliance.SimplifiedComplianceProfileDto;
import com.czertainly.api.model.client.raprofile.*;
import com.czertainly.api.model.common.NameAndUuidDto;
import com.czertainly.api.model.common.attribute.v2.BaseAttribute;
import com.czertainly.api.model.common.attribute.v2.DataAttribute;
import com.czertainly.api.model.core.audit.ObjectType;
import com.czertainly.api.model.core.audit.OperationType;
import com.czertainly.api.model.core.auth.Resource;
import com.czertainly.api.model.core.raprofile.RaProfileDto;
import com.czertainly.core.aop.AuditLogged;
import com.czertainly.core.dao.entity.*;
import com.czertainly.core.dao.entity.acme.AcmeProfile;
import com.czertainly.core.dao.entity.scep.ScepProfile;
import com.czertainly.core.dao.repository.*;
import com.czertainly.core.dao.repository.scep.ScepProfileRepository;
import com.czertainly.core.model.auth.ResourceAction;
import com.czertainly.core.security.authz.ExternalAuthorization;
import com.czertainly.core.security.authz.SecuredParentUUID;
import com.czertainly.core.security.authz.SecuredUUID;
import com.czertainly.core.security.authz.SecurityFilter;
import com.czertainly.core.service.AttributeService;
import com.czertainly.core.service.ComplianceService;
import com.czertainly.core.service.PermissionEvaluator;
import com.czertainly.core.service.RaProfileService;
import com.czertainly.core.service.model.SecuredList;
import com.czertainly.core.service.v2.ExtendedAttributeService;
import com.czertainly.core.util.AttributeDefinitionUtils;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class RaProfileServiceImpl implements RaProfileService {

    private static final Logger logger = LoggerFactory.getLogger(RaProfileServiceImpl.class);

    @Autowired
    private RaProfileRepository raProfileRepository;
    @Autowired
    private AuthorityInstanceReferenceRepository authorityInstanceReferenceRepository;
    @Autowired
    private AuthorityInstanceApiClient authorityInstanceApiClient;
    @Autowired
    private CertificateRepository certificateRepository;
    @Autowired
    private AcmeProfileRepository acmeProfileRepository;
    @Autowired
    private ExtendedAttributeService extendedAttributeService;
    @Autowired
    private ComplianceService complianceService;
    @Autowired
    private ComplianceProfileRepository complianceProfileRepository;
    @Autowired
    private AttributeService attributeService;
    @Autowired
    private PermissionEvaluator permissionEvaluator;
    @Autowired
    private RaProfileProtocolAttributeRepository raProfileProtocolAttributeRepository;
    @Autowired
    private ScepProfileRepository scepProfileRepository;


    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.LIST, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.LIST)
    public List<RaProfileDto> listRaProfiles(SecurityFilter filter, Optional<Boolean> enabled) {
        filter.setParentRefProperty("authorityInstanceReferenceUuid");
        if (enabled == null || !enabled.isPresent()) {
            return raProfileRepository.findUsingSecurityFilter(filter).stream().map(RaProfile::mapToDtoSimple).collect(Collectors.toList());
        } else {
            return raProfileRepository.findUsingSecurityFilter(filter, enabled.get()).stream().map(RaProfile::mapToDtoSimple).collect(Collectors.toList());
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.LIST)
    public SecuredList<RaProfile> listRaProfilesAssociatedWithAcmeProfile(String acmeProfileUuid, SecurityFilter filter) {
        List<RaProfile> raProfiles = raProfileRepository.findAllByAcmeProfileUuid(UUID.fromString(acmeProfileUuid));
        return SecuredList.fromFilter(filter, raProfiles);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.CREATE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.CREATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public RaProfileDto addRaProfile(SecuredParentUUID authorityInstanceUuid, AddRaProfileRequestDto dto) throws AlreadyExistException, ValidationException, ConnectorException {
        if (StringUtils.isBlank(dto.getName())) {
            throw new ValidationException(ValidationError.create("RA profile name must not be empty"));
        }

        Optional<RaProfile> o = raProfileRepository.findByName(dto.getName());
        if (o.isPresent()) {
            throw new AlreadyExistException(RaProfile.class, dto.getName());
        }
        attributeService.validateCustomAttributes(dto.getCustomAttributes(), Resource.RA_PROFILE);
        AuthorityInstanceReference authorityInstanceRef = authorityInstanceReferenceRepository.findByUuid(authorityInstanceUuid)
                .orElseThrow(() -> new NotFoundException(AuthorityInstanceReference.class, authorityInstanceUuid));

        List<DataAttribute> attributes = mergeAndValidateAttributes(authorityInstanceRef, dto.getAttributes());
        RaProfile raProfile = createRaProfile(dto, attributes, authorityInstanceRef);
        raProfileRepository.save(raProfile);

        attributeService.createAttributeContent(raProfile.getUuid(), dto.getCustomAttributes(), Resource.RA_PROFILE);

        RaProfileDto raProfileDto = raProfile.mapToDto();
        raProfileDto.setCustomAttributes(attributeService.getCustomAttributesWithValues(raProfile.getUuid(), Resource.RA_PROFILE));

        return raProfileDto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DETAIL)
    public RaProfileDto getRaProfile(SecuredUUID uuid) throws NotFoundException {
        RaProfile raProfile = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));
        RaProfileDto dto = raProfile.mapToDto();
        dto.setCustomAttributes(attributeService.getCustomAttributesWithValues(raProfile.getUuid(), Resource.RA_PROFILE));
        return dto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.REQUEST)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DETAIL, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public RaProfileDto getRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        RaProfile raProfile = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

        RaProfileDto dto = raProfile.mapToDto();
        dto.setCustomAttributes(attributeService.getCustomAttributesWithValues(raProfile.getUuid(), Resource.RA_PROFILE));
        return dto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public RaProfileDto editRaProfile(SecuredParentUUID authorityInstanceUuid, SecuredUUID uuid, EditRaProfileRequestDto dto) throws ConnectorException {
        RaProfile raProfile = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

        AuthorityInstanceReference authorityInstanceRef = authorityInstanceReferenceRepository.findByUuid(authorityInstanceUuid)
                .orElseThrow(() -> new NotFoundException(AuthorityInstanceReference.class, authorityInstanceUuid));

        attributeService.validateCustomAttributes(dto.getCustomAttributes(), Resource.RA_PROFILE);
        List<DataAttribute> attributes = mergeAndValidateAttributes(authorityInstanceRef, dto.getAttributes());

        updateRaProfile(raProfile, authorityInstanceRef, dto, attributes);
        raProfileRepository.save(raProfile);

        attributeService.updateAttributeContent(raProfile.getUuid(), dto.getCustomAttributes(), Resource.RA_PROFILE);

        RaProfileDto raProfileDto = raProfile.mapToDto();
        raProfileDto.setCustomAttributes(attributeService.getCustomAttributesWithValues(raProfile.getUuid(), Resource.RA_PROFILE));
        return raProfileDto;
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DELETE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public void deleteRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        deleteRaProfileInt(uuid);
    }


    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DELETE)
    public void deleteRaProfile(SecuredUUID uuid) throws NotFoundException {
        deleteRaProfileInt(uuid);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.ENABLE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ENABLE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public void enableRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        RaProfile entity = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

        entity.setEnabled(true);
        raProfileRepository.save(entity);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.DISABLE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ENABLE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public void disableRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        RaProfile entity = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

        entity.setEnabled(false);
        raProfileRepository.save(entity);
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.DELETE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DELETE)
    public void bulkDeleteRaProfile(List<SecuredUUID> uuids) {
        for (SecuredUUID uuid : uuids) {
            try {
                deleteRaProfile(uuid);
            } catch (NotFoundException e) {
                logger.warn("Unable to find RA Profile with uuid {}. It may have already been deleted", uuid);
            }
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.DISABLE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ENABLE)
    public void bulkDisableRaProfile(List<SecuredUUID> uuids) {
        for (SecuredUUID uuid : uuids) {
            try {
                RaProfile entity = raProfileRepository.findByUuid(uuid)
                        .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

                entity.setEnabled(false);
                raProfileRepository.save(entity);
            } catch (NotFoundException e) {
                logger.warn("Unable to disable RA Profile with uuid {}. It may have been deleted", uuid);
            }
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.ENABLE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ENABLE)
    public void bulkEnableRaProfile(List<SecuredUUID> uuids) {
        for (SecuredUUID uuid : uuids) {
            try {
                RaProfile entity = raProfileRepository.findByUuid(uuid)
                        .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));

                entity.setEnabled(true);
                raProfileRepository.save(entity);
            } catch (NotFoundException e) {
                logger.warn("Unable to enable RA Profile with uuid {}. It may have been deleted", uuids);
            }
        }
    }

    @Override
    @AuditLogged(originator = ObjectType.FE, affected = ObjectType.RA_PROFILE, operation = OperationType.CHANGE)
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE)
    public void bulkRemoveAssociatedAcmeProfile(List<SecuredUUID> uuids) {
        List<RaProfile> raProfiles = raProfileRepository.findAllByUuidIn(
                uuids.stream().map(SecuredUUID::getValue).collect(Collectors.toList())
        );
        raProfiles.forEach(raProfile -> raProfile.setAcmeProfile(null));
        raProfileRepository.saveAll(raProfiles);
    }

    @Override
    public void bulkRemoveAssociatedScepProfile(List<SecuredUUID> uuids) {
        List<RaProfile> raProfiles = raProfileRepository.findAllByUuidIn(
                uuids.stream().map(SecuredUUID::getValue).collect(Collectors.toList())
        );
        raProfiles.forEach(raProfile -> raProfile.setScepProfile(null));
        raProfileRepository.saveAll(raProfiles);
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DETAIL, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    // TODO - use acme service to obtain ACME profile
    public RaProfileAcmeDetailResponseDto getAcmeForRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        return getRaProfileEntity(uuid).mapToAcmeDto();
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public RaProfileAcmeDetailResponseDto activateAcmeForRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid, SecuredUUID acmeProfileUuid, ActivateAcmeForRaProfileRequestDto request) throws ConnectorException, ValidationException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        AcmeProfile acmeProfile = acmeProfileRepository.findByUuid(acmeProfileUuid)
                .orElseThrow(() -> new NotFoundException(AcmeProfile.class, acmeProfileUuid));
        raProfile.setAcmeProfile(acmeProfile);
        RaProfileProtocolAttribute raProfileProtocolAttribute = raProfile.getProtocolAttribute();
        raProfileProtocolAttribute.setAcmeIssueCertificateAttributes(AttributeDefinitionUtils.serialize(
                extendedAttributeService.mergeAndValidateIssueAttributes
                        (raProfile, request.getIssueCertificateAttributes()
                        )));
        raProfileProtocolAttribute.setAcmeRevokeCertificateAttributes(AttributeDefinitionUtils.serialize(
                extendedAttributeService.mergeAndValidateRevokeAttributes(
                        raProfile, request.getRevokeCertificateAttributes()
                )));
        raProfileProtocolAttribute.setRaProfile(raProfile);
        raProfileProtocolAttributeRepository.save(raProfileProtocolAttribute);
        raProfile.setProtocolAttribute(raProfileProtocolAttribute);
        raProfileRepository.save(raProfile);
        return raProfile.mapToAcmeDto();
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public void deactivateAcmeForRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        raProfile.setAcmeProfile(null);
        RaProfileProtocolAttribute raProfileProtocolAttribute = raProfile.getProtocolAttribute();
        raProfileProtocolAttribute.setAcmeRevokeCertificateAttributes(null);
        raProfileProtocolAttribute.setScepIssueCertificateAttributes(null);
        raProfile.setProtocolAttribute(raProfileProtocolAttribute);
        raProfileRepository.save(raProfile);
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public RaProfileScepDetailResponseDto activateScepForRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid, SecuredUUID scepProfileUuid, ActivateScepForRaProfileRequestDto request) throws ConnectorException, ValidationException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        ScepProfile scepProfile = scepProfileRepository.findByUuid(scepProfileUuid)
                .orElseThrow(() -> new NotFoundException(ScepProfile.class, scepProfileUuid));
        raProfile.setScepProfile(scepProfile);
        RaProfileProtocolAttribute raProfileProtocolAttribute = raProfile.getProtocolAttribute();
        raProfileProtocolAttribute.setScepIssueCertificateAttributes(AttributeDefinitionUtils.serialize(
                extendedAttributeService.mergeAndValidateIssueAttributes(
                        raProfile, request.getIssueCertificateAttributes()
                )));
        raProfileProtocolAttribute.setRaProfile(raProfile);
        raProfileProtocolAttributeRepository.save(raProfileProtocolAttribute);
        raProfile.setProtocolAttribute(raProfileProtocolAttribute);
        raProfileRepository.save(raProfile);
        return raProfile.mapToScepDto();
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.DETAIL)
    public void deactivateScepForRaProfile(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws NotFoundException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        raProfile.setScepProfile(null);
        RaProfileProtocolAttribute raProfileProtocolAttribute = raProfile.getProtocolAttribute();
        raProfileProtocolAttribute.setScepIssueCertificateAttributes(null);
        raProfile.setProtocolAttribute(raProfileProtocolAttribute);
        raProfileRepository.save(raProfile);
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ANY, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.ANY)
    public List<BaseAttribute> listRevokeCertificateAttributes(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws ConnectorException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        return extendedAttributeService.listRevokeCertificateAttributes(raProfile);
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.ANY, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.ANY)
    public List<BaseAttribute> listIssueCertificateAttributes(SecuredParentUUID authorityUuid, SecuredUUID uuid) throws ConnectorException {
        RaProfile raProfile = getRaProfileEntity(uuid);
        return extendedAttributeService.listIssueCertificateAttributes(raProfile);
    }

    @Override
    // TODO - remove, service should not allow modifying RaProfile entity outside of it.
    public RaProfile updateRaProfileEntity(RaProfile raProfile) {
        raProfileRepository.save(raProfile);
        return raProfile;
    }

    @Override
    @Async
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.CHECK_COMPLIANCE)
    public void checkCompliance(List<SecuredUUID> uuids) {
        for (SecuredUUID uuid : uuids) {
            logger.info("Checking compliance for RA Profile: {}", uuid);
            try {
                complianceService.complianceCheckForRaProfile(uuid);
            } catch (Exception e) {
                logger.error("Compliance check failed.", e);
            }
        }
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.LIST, parentResource = Resource.AUTHORITY, parentAction = ResourceAction.LIST)
    public Long statisticsRaProfilesCount(SecurityFilter filter) {
        filter.setParentRefProperty("authorityInstanceReferenceUuid");
        return raProfileRepository.countUsingSecurityFilter(filter);
    }

    @Override
    @ExternalAuthorization(resource = Resource.COMPLIANCE_PROFILE, action = ResourceAction.DETAIL)
    public List<SimplifiedComplianceProfileDto> getComplianceProfiles(String authorityUuid, String raProfileUuid, SecurityFilter filter) throws NotFoundException {
        //Evaluate RA profile permissions
        ((RaProfileService) AopContext.currentProxy()).getRaProfile(SecuredParentUUID.fromString(authorityUuid), SecuredUUID.fromString(raProfileUuid));
        return getComplianceProfilesForRaProfile(raProfileUuid, filter);
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DETAIL)
    public Boolean evaluateNullableRaPermissions(SecurityFilter filter) {
        return !filter.getResourceFilter().areOnlySpecificObjectsAllowed();
    }

    @Override
    public SecuredList<RaProfile> listRaProfilesAssociatedWithScepProfile(String scepProfileUuid, SecurityFilter filter) {
        List<RaProfile> raProfiles = raProfileRepository.findAllByScepProfileUuid(UUID.fromString(scepProfileUuid));
        return SecuredList.fromFilter(filter, raProfiles);
    }

    @Override
    public RaProfileScepDetailResponseDto getScepForRaProfile(SecuredParentUUID authorityInstanceUuid, SecuredUUID raProfileUuid) throws NotFoundException {
        return getRaProfileEntity(raProfileUuid).mapToScepDto();
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.LIST)
    public List<NameAndUuidDto> listResourceObjects(SecurityFilter filter) {
        return raProfileRepository.findUsingSecurityFilter(filter)
                .stream()
                .map(RaProfile::mapToAccessControlObjects)
                .collect(Collectors.toList());
    }

    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.DETAIL)
    // TODO - make private, service should not allow modifying RaProfile entity outside of it.
    public RaProfile getRaProfileEntity(SecuredUUID uuid) throws NotFoundException {
        return raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));
    }

    @Override
    @ExternalAuthorization(resource = Resource.RA_PROFILE, action = ResourceAction.UPDATE)
    public void evaluatePermissionChain(SecuredUUID uuid) throws NotFoundException {
        RaProfile profile = getRaProfileEntity(uuid);
        if (profile.getAuthorityInstanceReference() == null) {
            return;
        }
        // Parent Permission evaluation - Authority Instance
        permissionEvaluator.authorityInstance(profile.getAuthorityInstanceReference().getSecuredUuid());

    }

    private List<DataAttribute> mergeAndValidateAttributes(AuthorityInstanceReference authorityInstanceRef, List<RequestAttributeDto> attributes) throws ConnectorException {
        List<BaseAttribute> definitions = authorityInstanceApiClient.listRAProfileAttributes(
                authorityInstanceRef.getConnector().mapToDto(),
                authorityInstanceRef.getAuthorityInstanceUuid());

        List<String> existingAttributesFromConnector = definitions.stream().map(BaseAttribute::getName).collect(Collectors.toList());
        for (RequestAttributeDto requestAttributeDto : attributes) {
            if (!existingAttributesFromConnector.contains(requestAttributeDto.getName())) {
                DataAttribute referencedAttribute = attributeService.getReferenceAttribute(authorityInstanceRef.getConnectorUuid(), requestAttributeDto.getName());
                if (referencedAttribute != null) {
                    definitions.add(referencedAttribute);
                }
            }
        }

        List<DataAttribute> merged = AttributeDefinitionUtils.mergeAttributes(definitions, attributes);

        if (Boolean.FALSE.equals(authorityInstanceApiClient.validateRAProfileAttributes(
                authorityInstanceRef.getConnector().mapToDto(),
                authorityInstanceRef.getAuthorityInstanceUuid(),
                attributes))) {

            throw new ValidationException(ValidationError.create("RA profile attributes validation failed."));
        }

        return merged;
    }

    private RaProfile createRaProfile(AddRaProfileRequestDto dto, List<DataAttribute> attributes, AuthorityInstanceReference authorityInstanceRef) {
        RaProfile entity = new RaProfile();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setAttributes(AttributeDefinitionUtils.serialize(attributes));
        entity.setAuthorityInstanceReference(authorityInstanceRef);
        entity.setEnabled(dto.isEnabled() != null && dto.isEnabled());
        entity.setAuthorityInstanceName(authorityInstanceRef.getName());
        return entity;
    }

    private RaProfile updateRaProfile(RaProfile entity, AuthorityInstanceReference authorityInstanceRef, EditRaProfileRequestDto dto, List<DataAttribute> attributes) {
        entity.setDescription(dto.getDescription());
        entity.setAttributes(AttributeDefinitionUtils.serialize(attributes));
        entity.setAuthorityInstanceReference(authorityInstanceRef);
        if (dto.isEnabled() != null) {
            entity.setEnabled(dto.isEnabled() != null && dto.isEnabled());
        }
        entity.setAuthorityInstanceName(authorityInstanceRef.getName());
        return entity;
    }

    private void deleteRaProfileInt(SecuredUUID uuid) throws NotFoundException {
        RaProfile raProfile = raProfileRepository.findByUuid(uuid)
                .orElseThrow(() -> new NotFoundException(RaProfile.class, uuid));
        List<AcmeProfile> acmeProfiles = acmeProfileRepository.findByRaProfile(raProfile);
        for (AcmeProfile acmeProfile : acmeProfiles) {
            acmeProfile.setRaProfile(null);
            acmeProfile.setRaProfileUuid(null);
            acmeProfileRepository.save(acmeProfile);
        }
        for (Certificate certificate : certificateRepository.findByRaProfile(raProfile)) {
            certificate.setRaProfile(null);
            certificate.setRaProfileUuid(null);
            certificateRepository.save(certificate);
        }
        attributeService.deleteAttributeContent(raProfile.getUuid(), Resource.RA_PROFILE);
        raProfileRepository.delete(raProfile);
    }

    private List<SimplifiedComplianceProfileDto> getComplianceProfilesForRaProfile(String raProfileUuid, SecurityFilter filter) {
        return complianceProfileRepository.findUsingSecurityFilter(filter)
                .stream()
                .filter(e -> e.getRaProfiles().stream().map(RaProfile::getUuid).map(UUID::toString).collect(Collectors.toList()).contains(raProfileUuid))
                .map(ComplianceProfile::raProfileMapToDto)
                .collect(Collectors.toList());
    }
}
