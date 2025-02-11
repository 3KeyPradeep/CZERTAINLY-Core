package com.czertainly.core.dao.entity;

import com.czertainly.api.model.client.raprofile.RaProfileAcmeDetailResponseDto;
import com.czertainly.api.model.client.raprofile.RaProfileScepDetailResponseDto;
import com.czertainly.api.model.client.raprofile.SimplifiedRaProfileDto;
import com.czertainly.api.model.common.NameAndUuidDto;
import com.czertainly.api.model.common.attribute.v2.DataAttribute;
import com.czertainly.api.model.core.raprofile.RaProfileDto;
import com.czertainly.core.dao.entity.acme.AcmeProfile;
import com.czertainly.core.dao.entity.scep.ScepProfile;
import com.czertainly.core.service.acme.impl.ExtendedAcmeHelperService;
import com.czertainly.core.service.model.Securable;
import com.czertainly.core.service.scep.impl.ExtendedScepHelperServiceImpl;
import com.czertainly.core.util.AttributeDefinitionUtils;
import com.czertainly.core.util.DtoMapper;
import com.czertainly.core.util.ObjectAccessControlMapper;
import jakarta.persistence.*;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;


@Entity
@Table(name = "ra_profile")
public class RaProfile extends UniquelyIdentifiedAndAudited implements Serializable, DtoMapper<RaProfileDto>, Securable, ObjectAccessControlMapper<NameAndUuidDto> {

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "authority_instance_name")
    private String authorityInstanceName;

    //    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "attributes", length = Integer.MAX_VALUE)
    private String attributes;

    @ManyToOne
    @JoinColumn(name = "authority_instance_ref_uuid", insertable = false, updatable = false)
    private AuthorityInstanceReference authorityInstanceReference;

    @Column(name = "authority_instance_ref_uuid")
    private UUID authorityInstanceReferenceUuid;

    @Column(name = "enabled")
    private Boolean enabled;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
            name = "ra_profile_2_compliance_profile",
            joinColumns = @JoinColumn(name = "ra_profile_uuid"),
            inverseJoinColumns = @JoinColumn(name = "compliance_profile_uuid"))
    private Set<ComplianceProfile> complianceProfiles;

    @OneToOne(mappedBy = "raProfile")
    private RaProfileProtocolAttribute protocolAttribute;

    /**
     * Acme related objects for RA Profile
     */
    @OneToOne
    @JoinColumn(name = "acme_profile_uuid", insertable = false, updatable = false)
    private AcmeProfile acmeProfile;

    @Column(name = "acme_profile_uuid")
    private UUID acmeProfileUuid;

    @OneToOne
    @JoinColumn(name = "scep_profile_uuid", insertable = false, updatable = false)
    private ScepProfile scepProfile;

    @Column(name = "scep_profile_uuid")
    private UUID scepProfileUuid;


    public RaProfileAcmeDetailResponseDto mapToAcmeDto() {
        RaProfileAcmeDetailResponseDto dto = new RaProfileAcmeDetailResponseDto();
        if (acmeProfile == null) {
            dto.setAcmeAvailable(false);
            return dto;
        }
        dto.setName(acmeProfile.getName());
        dto.setUuid(acmeProfile.getUuid().toString());
        dto.setIssueCertificateAttributes(AttributeDefinitionUtils.getResponseAttributes(AttributeDefinitionUtils.deserialize(protocolAttribute.getAcmeIssueCertificateAttributes(), DataAttribute.class)));
        dto.setRevokeCertificateAttributes(AttributeDefinitionUtils.getResponseAttributes(AttributeDefinitionUtils.deserialize(protocolAttribute.getAcmeRevokeCertificateAttributes(), DataAttribute.class)));
        dto.setDirectoryUrl(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + ExtendedAcmeHelperService.ACME_URI_HEADER + "/raProfile/" + name + "/directory");
        dto.setAcmeAvailable(true);
        return dto;
    }


    public RaProfileScepDetailResponseDto mapToScepDto() {
        RaProfileScepDetailResponseDto dto = new RaProfileScepDetailResponseDto();
        if (scepProfile == null) {
            dto.setScepAvailable(false);
            return dto;
        }
        dto.setScepAvailable(true);
        dto.setUrl(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
                + ExtendedScepHelperServiceImpl.SCEP_URL_PREFIX + "/" + name);
        dto.setName(scepProfile.getName());
        dto.setUuid(scepProfile.getUuid().toString());
        dto.setIssueCertificateAttributes(AttributeDefinitionUtils.getResponseAttributes(AttributeDefinitionUtils.deserialize(protocolAttribute.getScepIssueCertificateAttributes(), DataAttribute.class)));
        return dto;
    }

    public RaProfileDto mapToDtoSimple() {
        RaProfileDto dto = new RaProfileDto();
        dto.setUuid(this.uuid.toString());
        dto.setName(this.name);
        List<String> enabledProtocols = new ArrayList<>();
        if (acmeProfile != null) {
            enabledProtocols.add("ACME");
        }
        if (scepProfile != null) {
            enabledProtocols.add("SCEP");
        }
        dto.setEnabledProtocols(enabledProtocols);
        dto.setDescription(this.description);
        dto.setAuthorityInstanceName(this.authorityInstanceName);
        try {
            dto.setAuthorityInstanceUuid(this.authorityInstanceReference.getUuid().toString());
        } catch (NullPointerException e) {
            dto.setAuthorityInstanceUuid(null);
        }
        dto.setEnabled(this.enabled);

        return dto;
    }

    public SimplifiedRaProfileDto mapToDtoSimplified() {
        SimplifiedRaProfileDto dto = new SimplifiedRaProfileDto();
        dto.setUuid(this.uuid.toString());
        dto.setName(this.name);
        dto.setEnabled(this.enabled);
        try {
            dto.setAuthorityInstanceUuid(this.authorityInstanceReference.getUuid().toString());
        } catch (NullPointerException e) {
            dto.setAuthorityInstanceUuid(null);
        }
        return dto;
    }

    @Override
    @Transient
    public RaProfileDto mapToDto() {
        RaProfileDto dto = new RaProfileDto();
        dto.setUuid(uuid.toString());
        dto.setName(name);
        dto.setDescription(this.description);
        dto.setAttributes(AttributeDefinitionUtils.getResponseAttributes(AttributeDefinitionUtils.deserialize(this.attributes, DataAttribute.class)));
        dto.setAuthorityInstanceUuid(authorityInstanceReference != null ? authorityInstanceReference.getUuid().toString() : null);
        dto.setAuthorityInstanceName(this.authorityInstanceName);
        dto.setEnabled(enabled);
        return dto;
    }

    @Override
    public NameAndUuidDto mapToAccessControlObjects() {
        return new NameAndUuidDto(uuid.toString(), name);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAttributes() {
        return attributes;
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getAuthorityInstanceName() {
        return authorityInstanceName;
    }

    public void setAuthorityInstanceName(String authorityInstanceName) {
        this.authorityInstanceName = authorityInstanceName;
    }

    public AuthorityInstanceReference getAuthorityInstanceReference() {
        return authorityInstanceReference;
    }

    public void setAuthorityInstanceReference(AuthorityInstanceReference authorityInstanceReference) {
        this.authorityInstanceReference = authorityInstanceReference;
        if (authorityInstanceReference != null)
            this.authorityInstanceReferenceUuid = authorityInstanceReference.getUuid();
        else this.authorityInstanceReferenceUuid = null;
    }

    public UUID getAuthorityInstanceReferenceUuid() {
        return authorityInstanceReferenceUuid;
    }

    public void setAuthorityInstanceReferenceUuid(UUID authorityInstanceReferenceUuid) {
        this.authorityInstanceReferenceUuid = authorityInstanceReferenceUuid;
    }

    public void setAuthorityInstanceReferenceUuid(String authorityInstanceReferenceUuid) {
        this.authorityInstanceReferenceUuid = UUID.fromString(authorityInstanceReferenceUuid);
    }

    public AcmeProfile getAcmeProfile() {
        return acmeProfile;
    }

    public void setAcmeProfile(AcmeProfile acmeProfile) {
        this.acmeProfile = acmeProfile;
        if (acmeProfile != null) this.acmeProfileUuid = acmeProfile.getUuid();
        else this.acmeProfileUuid = null;
    }

    public ScepProfile getScepProfile() {
        return scepProfile;
    }

    public void setScepProfile(ScepProfile scepProfile) {
        this.scepProfile = scepProfile;
        if (acmeProfile != null) this.scepProfileUuid = acmeProfile.getUuid();
        else this.scepProfileUuid = null;
    }

    public Set<ComplianceProfile> getComplianceProfiles() {
        return complianceProfiles;
    }

    public void setComplianceProfiles(Set<ComplianceProfile> complianceProfiles) {
        this.complianceProfiles = complianceProfiles;
    }

    public RaProfileProtocolAttribute getProtocolAttribute() {
        if(protocolAttribute == null) {
            return new RaProfileProtocolAttribute();
        }
        return protocolAttribute;
    }

    public void setProtocolAttribute(RaProfileProtocolAttribute protocolAttribute) {
        this.protocolAttribute = protocolAttribute;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("uuid", uuid)
                .append("name", name)
                .append("enabled", enabled)
                .toString();
    }
}
