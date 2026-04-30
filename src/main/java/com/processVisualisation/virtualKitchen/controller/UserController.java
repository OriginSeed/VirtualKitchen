package com.processVisualisation.virtualKitchen.controller;

import com.processVisualisation.virtualKitchen.dto.UserRequestInputDTO;
import com.processVisualisation.virtualKitchen.model.User;
import com.processVisualisation.virtualKitchen.service.IUserService;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

        @Autowired
        private IUserService userService;

        @PostMapping // Handles POST /api/v1/users
        public ResponseEntity<Long> register(@Valid @RequestBody UserRequestInputDTO request) {
            long userId = userService.createUser(request);
            return new ResponseEntity<>(userId, HttpStatus.CREATED);
        }

        @GetMapping("/{id}") // Handles GET /api/v1/users/{id}
        public ResponseEntity<User> getProfile(@PathVariable Long id) {
            return ResponseEntity.ok(userService.getUserById(id));
        }
}
