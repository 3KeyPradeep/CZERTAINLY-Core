package com.czertainly.core.service;

import com.czertainly.api.exception.NotFoundException;
import com.czertainly.api.model.core.auth.AddUserRequestDto;
import com.czertainly.api.model.core.auth.RoleDto;
import com.czertainly.api.model.core.auth.SubjectPermissionsDto;
import com.czertainly.api.model.core.auth.UserDetailDto;
import com.czertainly.api.model.core.auth.UserDto;
import com.czertainly.api.model.core.auth.UserUpdateRequestDto;

import java.security.cert.CertificateException;
import java.util.List;

public interface UserManagementService {
    List<UserDto> listUsers();

    UserDetailDto getUser(String userUuid) throws NotFoundException;

    UserDetailDto createUser(AddUserRequestDto request) throws CertificateException, NotFoundException;

    UserDetailDto updateUser(String userUuid, UserUpdateRequestDto request);

    void deleteUser(String userUuid);

    UserDetailDto updateRoles(String userUuid, List<String> roleUuids);

    UserDetailDto updateRole(String userUuid, String roleUuid);

    SubjectPermissionsDto getPermissions(String userUuid);

    UserDetailDto enableUser(String userUuid);

    UserDetailDto disableUser(String userUuid);

    List<RoleDto> getUserRoles(String userUuid);

    UserDetailDto removeRole(String userUuid, String roleUuid);
}
