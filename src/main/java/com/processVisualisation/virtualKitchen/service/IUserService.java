package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.UserRequestDTO;
import com.processVisualisation.virtualKitchen.dto.UserResponseDTO;
import com.processVisualisation.virtualKitchen.dto.UserUpdateDTO;

public interface IUserService {

    UserResponseDTO createUser(UserRequestDTO request);

    UserResponseDTO getUserById(Long id);

    UserResponseDTO updateUser(Long id, UserUpdateDTO request);

    void deleteUser(Long id);
}