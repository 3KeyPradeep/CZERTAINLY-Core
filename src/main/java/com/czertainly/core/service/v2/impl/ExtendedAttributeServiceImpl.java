package com.czertainly.core.service.v2.impl;

import com.czertainly.api.clients.v2.CertificateApiClient;
import com.czertainly.api.exception.ConnectorException;
import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.exception.ValidationError;
import com.czertainly.api.exception.ValidationException;
import com.czertainly.api.model.client.attribute.RequestAttributeDto;
import com.czertainly.api.model.common.attribute.v2.BaseAttribute;
import com.czertainly.api.model.common.attribute.v2.DataAttribute;
import com.czertainly.api.model.core.audit.ObjectType;
import com.czertainly.api.model.core.audit.OperationType;
import com.czertainly.core.aop.AuditLogged;
import com.czertainly.core.dao.entity.Connector;
import com.czertainly.core.dao.entity.Connector2FunctionGroup;
import com.czertainly.core.dao.entity.RaProfile;
import com.czertainly.core.dao.repository.ConnectorRepository;
import com.czertainly.core.service.v2.ExtendedAttributeService;
import com.czertainly.core.util.AttributeDefinitionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service("extendedAcmeServiceImpl")
public class ExtendedAttributeServiceImpl implements ExtendedAttributeService {

    @Autowired
    private CertificateApiClient certificateApiClient;
    @Autowired
    private ConnectorRepository connectorRepository;

    @Override
    @AuditLogged(originator = ObjectType.CLIENT, affected = ObjectType.ATTRIBUTES, operation = OperationType.REQUEST)
    public List<BaseAttribute> listIssueCertificateAttributes(RaProfile raProfile) throws ConnectorException {
        validateLegacyConnector(raProfile.getAuthorityInstanceReference().getConnector());

        return certificateApiClient.listIssueCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid());
    }

    @Override
    @AuditLogged(originator = ObjectType.CLIENT, affected = ObjectType.ATTRIBUTES, operation = OperationType.VALIDATE)
    public boolean validateIssueCertificateAttributes(RaProfile raProfile, List<RequestAttributeDto> attributes) throws ConnectorException, ValidationException {
        validateLegacyConnector(raProfile.getAuthorityInstanceReference().getConnector());

        return certificateApiClient.validateIssueCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid(),
                attributes);
    }

    @Override
    public List<DataAttribute> mergeAndValidateIssueAttributes(RaProfile raProfile, List<RequestAttributeDto> attributes) throws ConnectorException {
        if(raProfile.getAuthorityInstanceReference().getConnector() == null){
            throw new ValidationException(ValidationError.create("Connector of the Authority is not available / deleted"));
        }
        List<BaseAttribute> definitions = certificateApiClient.listIssueCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid());

        List<DataAttribute> merged = AttributeDefinitionUtils.mergeAttributes(definitions, attributes);

        certificateApiClient.validateIssueCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid(),
                attributes);

        return merged;
    }

    @Override
    @AuditLogged(originator = ObjectType.CLIENT, affected = ObjectType.ATTRIBUTES, operation = OperationType.REQUEST)
    public List<BaseAttribute> listRevokeCertificateAttributes(RaProfile raProfile) throws ConnectorException {
        validateLegacyConnector(raProfile.getAuthorityInstanceReference().getConnector());

        return certificateApiClient.listRevokeCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid());
    }

    @Override
    @AuditLogged(originator = ObjectType.CLIENT, affected = ObjectType.ATTRIBUTES, operation = OperationType.VALIDATE)
    public boolean validateRevokeCertificateAttributes(RaProfile raProfile, List<RequestAttributeDto> attributes) throws ConnectorException, ValidationException {
        validateLegacyConnector(raProfile.getAuthorityInstanceReference().getConnector());

        return certificateApiClient.validateRevokeCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid(),
                attributes);
    }

    @Override
    public List<DataAttribute> mergeAndValidateRevokeAttributes(RaProfile raProfile, List<RequestAttributeDto> attributes) throws ConnectorException {
        List<BaseAttribute> definitions = certificateApiClient.listRevokeCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid());

        List<DataAttribute> merged = AttributeDefinitionUtils.mergeAttributes(definitions, attributes);

        certificateApiClient.validateRevokeCertificateAttributes(
                raProfile.getAuthorityInstanceReference().getConnector().mapToDto(),
                raProfile.getAuthorityInstanceReference().getAuthorityInstanceUuid(),
                attributes);

        return merged;
    }

    @Override
    public void validateLegacyConnector(Connector connector) throws NotFoundException {
        for (Connector2FunctionGroup fg : connector.getFunctionGroups()) {
            if (!connectorRepository.findConnectedByFunctionGroupAndKind(fg.getFunctionGroup(), "LegacyEjbca").isEmpty()) {
                throw new NotFoundException("Legacy Authority. V2 Implementation not found on the connector");
            }
        }
    }
}
