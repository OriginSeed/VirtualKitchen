package com.processVisualisation.virtualKitchen.service.impl;

import com.processVisualisation.virtualKitchen.dto.UserRequestDTO;
import com.processVisualisation.virtualKitchen.dto.UserResponseDTO;
import com.processVisualisation.virtualKitchen.dto.UserUpdateDTO;
import com.processVisualisation.virtualKitchen.exception.UserNotFoundException;
import com.processVisualisation.virtualKitchen.mapper.UserMapper;
import com.processVisualisation.virtualKitchen.model.User;
import com.processVisualisation.virtualKitchen.repository.UserRepository;
import com.processVisualisation.virtualKitchen.service.IUserService;
import com.processVisualisation.virtualKitchen.service.SequenceGeneratorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements IUserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SequenceGeneratorService sequenceGeneratorService;

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserResponseDTO createUser(UserRequestDTO request) {

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = userMapper.toEntity(request);
        user.setId(sequenceGeneratorService.generateSequence(User.SEQUENCE_NAME));

        // TODO: encode password using BCrypt
        // user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User saved = userRepository.save(user);
        return userMapper.toDTO(saved);
    }

    @Override
    public UserResponseDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        return userMapper.toDTO(user);
    }

    @Override
    public UserResponseDTO getUserByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found with email: " + email));

        return userMapper.toDTO(user);
    }

    @Override
    public UserResponseDTO updateUser(Long id, UserUpdateDTO request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        user.setName(request.getName());

        User updated = userRepository.save(user);
        return userMapper.toDTO(updated);
    }

    @Override
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        userRepository.delete(user);
    }
}