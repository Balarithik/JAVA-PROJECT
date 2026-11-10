package com.smartqueue.service;

import com.smartqueue.model.AppUser;
import com.smartqueue.model.AnalyticsSummary;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.UserRepository;

import java.sql.SQLException;
import java.util.List;

/** Administrator-only operations for service configuration and operational reports. */
public final class AdminService {
    private final AuthService authService;
    private final ServiceRepository serviceRepository;
    private final AnalyticsService analyticsService;
    private final UserRepository userRepository;
    private final QueueRepository queueRepository;

    public AdminService(AuthService authService, ServiceRepository serviceRepository,
                        AnalyticsService analyticsService, UserRepository userRepository,
                        QueueRepository queueRepository) {
        this.authService = authService;
        this.serviceRepository = serviceRepository;
        this.analyticsService = analyticsService;
        this.userRepository = userRepository;
        this.queueRepository = queueRepository;
    }

    public AppUser createStaff(AppUser administrator, String name, String email, char[] password)
            throws SQLException {
        requireAdministrator(administrator);
        return authService.createStaff(administrator, name, email, password);
    }


    public List<AppUser> staffAccounts(AppUser administrator) throws SQLException {
        requireAdministrator(administrator);
        return userRepository.listByRole(UserRole.STAFF);
    }

    public void setStaffActive(AppUser administrator, long staffId, boolean active) throws SQLException {
        requireAdministrator(administrator);
        AppUser staff = userRepository.findById(staffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff account was not found."));
        if (staff.role() != UserRole.STAFF || staff.id() == administrator.id()) {
            throw new SecurityException("Only another staff account can be changed here.");
        }
        if (!active && queueRepository.countActiveTokensForStaff(staffId) > 0) {
            throw new IllegalStateException("Complete or reassign the staff member's active token before deactivating this account.");
        }
        userRepository.setActive(staffId, active);
    }

    public ServiceItem createService(AppUser administrator, String name, String prefix,
                                     String description, int averageMinutes) throws SQLException {
        requireAdministrator(administrator);
        String cleanName = name == null ? "" : name.trim();
        String cleanPrefix = prefix == null ? "" : prefix.trim();
        if (cleanName.length() < 2 || cleanName.length() > 150) {
            throw new IllegalArgumentException("Service name must contain between 2 and 150 characters.");
        }
        if (!cleanPrefix.matches("[A-Za-z]{1,5}")) {
            throw new IllegalArgumentException("Token prefix must contain 1 to 5 letters.");
        }
        if (averageMinutes < 1 || averageMinutes > 240) {
            throw new IllegalArgumentException("Average service time must be between 1 and 240 minutes.");
        }
        return serviceRepository.create(cleanName, cleanPrefix, description, averageMinutes);
    }

    public void setServiceActive(AppUser administrator, long serviceId, boolean active) throws SQLException {
        requireAdministrator(administrator);
        serviceRepository.setActive(serviceId, active);
    }

    public AnalyticsSummary analytics(AppUser administrator) throws SQLException {
        requireAdministrator(administrator);
        return analyticsService.load(administrator);
    }

    private void requireAdministrator(AppUser administrator) {
        if (administrator == null || !administrator.active()
                || administrator.role() != com.smartqueue.model.UserRole.ADMIN) {
            throw new SecurityException("Administrator access is required.");
        }
    }
}
