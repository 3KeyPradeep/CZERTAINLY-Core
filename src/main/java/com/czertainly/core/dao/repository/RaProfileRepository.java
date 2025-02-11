package com.czertainly.core.dao.repository;

import com.czertainly.core.dao.entity.RaProfile;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional
public interface RaProfileRepository extends SecurityFilterRepository<RaProfile, Long> {

    Optional<RaProfile> findByUuid(UUID uuid);

    Optional<RaProfile> findByName(String name);

    Optional<RaProfile> findByNameAndEnabledIsTrue(String name);

    Optional<RaProfile> findByUuidAndEnabledIsTrue(UUID uuid);

    List<RaProfile> findAllByAcmeProfileUuid(UUID acmeProfileUuid);

    List<RaProfile> findAllByScepProfileUuid(UUID scepProfileUuid);

    List<RaProfile> findAllByUuidIn(List<UUID>uuids);
}
