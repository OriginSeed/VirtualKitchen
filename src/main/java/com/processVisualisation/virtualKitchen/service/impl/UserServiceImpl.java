package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.UserRequestInputDTO;
import com.processVisualisation.virtualKitchen.model.User;
import com.processVisualisation.virtualKitchen.repository.UserRepository;
import com.processVisualisation.virtualKitchen.service.IUserService;

import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements IUserService {

        @Autowired
        private UserRepository userRepository;
        @Autowired
        private SequenceGeneratorService sequenceGeneratorService;

        @Override
        public Long createUser(UserRequestInputDTO request) {
            User user = new User();
            user.setId(sequenceGeneratorService.generateSequence(User.SEQUENCE_NAME));
            user.setName(request.getName());
            user.setEmail(request.getEmail());
            user.setPassword(request.getPassword()); // In production, use BCrypt to encode!

            User savedUser = userRepository.save(user);
            return savedUser.getId();
        }

        @Override
        public User getUserById(Long id) {
            return userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("User not found"));
        }
}
