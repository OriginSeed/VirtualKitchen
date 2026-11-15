package com.processVisualisation.virtualKitchen.kitchen.service;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import com.processVisualisation.virtualKitchen.auth.security.CurrentUser;
import com.processVisualisation.virtualKitchen.kitchen.model.Kitchen;
import com.processVisualisation.virtualKitchen.kitchen.repository.KitchenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Who may read a kitchen and what is in it: its owner (the authenticated caller, from the verified JWT) or an
 * ADMIN. Endpoints that take a kitchen id from the path check here before returning anything.
 */
@Component
public class KitchenAccess {

    private final KitchenRepository repo;

    public KitchenAccess(KitchenRepository repo) {
        this.repo = repo;
    }

    /**
     * @throws AuthException 401 when unauthenticated, 403 when the kitchen belongs to someone else
     * @throws NoSuchElementException when no kitchen has this id
     */
    public Kitchen requireAccess(Long kitchenId) {
        Long userId = CurrentUser.requireUserId();
        Kitchen kitchen = repo.findById(kitchenId)
                .orElseThrow(() -> new NoSuchElementException("Kitchen not found: " + kitchenId));
        if (!userId.equals(kitchen.getOwnerId()) && !CurrentUser.isAdmin()) {
            throw new AuthException("You can only access your own kitchen", HttpStatus.FORBIDDEN);
        }
        return kitchen;
    }
}
