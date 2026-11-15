package com.processVisualisation.virtualKitchen.auth.security;

import com.processVisualisation.virtualKitchen.auth.exception.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the caller's identity from the security context populated by {@code JwtAuthenticationFilter}
 * (principal = the {@code Long} user id, authority = {@code ROLE_<userType>}). Identity and role
 * always come from the verified token, never from request bodies or parameters.
 */
public final class CurrentUser {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private CurrentUser() {
    }

    /**
     * @throws AuthException (401) when the request is not authenticated
     */
    public static Long requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication != null ? authentication.getPrincipal() : null;
        if (!(principal instanceof Long userId)) {
            throw new AuthException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * Requires an authenticated ADMIN and returns their user id. A defense-in-depth check: the
     * {@code /api/v1/admin/**} URL rule in {@code SecurityConfig} already enforces the role.
     *
     * @throws AuthException 401 when unauthenticated, 403 when authenticated without the ADMIN role
     */
    public static Long requireAdmin() {
        Long userId = requireUserId();
        if (!isAdmin()) {
            throw new AuthException("Admin privileges required", HttpStatus.FORBIDDEN);
        }
        return userId;
    }

    /** Whether the authenticated caller has the ADMIN role (false when unauthenticated). */
    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }

    /**
     * For endpoints that name a user in the path: allows the caller themself (or an ADMIN) and returns the
     * caller's id.
     *
     * @throws AuthException 401 when unauthenticated, 403 when the path names someone else
     */
    public static Long requireSelfOrAdmin(Long pathUserId) {
        Long userId = requireUserId();
        if (!userId.equals(pathUserId) && !isAdmin()) {
            throw new AuthException("You can only access your own data", HttpStatus.FORBIDDEN);
        }
        return userId;
    }
}
