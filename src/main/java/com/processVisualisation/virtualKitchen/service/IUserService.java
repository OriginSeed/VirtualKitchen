package com.processVisualisation.virtualKitchen.service;

import com.processVisualisation.virtualKitchen.dto.UserRequestInputDTO;
import com.processVisualisation.virtualKitchen.model.User;

public interface IUserService {
    Long createUser(UserRequestInputDTO request);
    User getUserById(Long id);
}
