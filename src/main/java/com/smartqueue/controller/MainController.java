package com.smartqueue.controller;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.AnalyticsSummary;
import com.smartqueue.model.Metric;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.service.AuthService;
import com.smartqueue.service.AnalyticsService;
import com.smartqueue.service.QueueService;
import com.smartqueue.security.SessionStore;
import com.smartqueue.service.SessionService;
import javafx.collections.FXCollections;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.beans.property.ReadOnlyStringWrapper;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.Callable;

public final class MainController {
    @FXML
    private BorderPane root;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smartqueue-database-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smartqueue-live-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private ScheduledFuture<?> refreshTask;
    private DatabaseManager databaseManager;
    private AuthService authService;
    private AnalyticsService analyticsService;
    private QueueService queueService;
    private SessionService sessionService;
    private SessionStore sessionStore;
    private ServiceRepository serviceRepository;
    private AppUser currentUser;

    @FXML
    private void initialize() {
        databaseManager = new DatabaseManager();
        UserRepository userRepository = new UserRepository(databaseManager);
        QueueRepository queueRepository = new QueueRepository(databaseManager);
        serviceRepository = new ServiceRepository(databaseManager);
        authService = new AuthService(databaseManager, userRepository);
        queueService = new QueueService(databaseManager, queueRepository, serviceRepository);
        analyticsService = new AnalyticsService(databaseManager, queueRepository);
        sessionService = new SessionService(databaseManager, userRepository);
        sessionStore = new SessionStore();
        root.setTop(buildNavigation());
        root.setBottom(buildFooter());
        showLanding();
        restoreSession();
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
        refreshExecutor.shutdownNow();
    }

    private HBox buildNavigation() {
        HBox navigation = new HBox(18);
        navigation.setAlignment(Pos.CENTER_LEFT);
        navigation.setPadding(new Insets(12, 32, 12, 32));
        navigation.getStyleClass().add("top-navigation");

        Button brand = navButton("SmartQueue", this::showLanding);
        brand.getStyleClass().add("brand-button");
        Button home = navButton("Home", this::showLanding);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        navigation.getChildren().addAll(brand, home, spacer);
        if (currentUser == null) {
            navigation.getChildren().addAll(navButton("Sign in", this::showLogin),
                    navButton("Create account", this::showRegister));
        } else {
            Label identity = new Label(currentUser.name() + "  |  " + currentUser.role());
            identity.getStyleClass().add("identity-label");
            navigation.getChildren().addAll(identity, navButton("Dashboard", this::openDashboard),
                    navButton("Sign out", this::signOut));
        }
        return navigation;
    }

    private VBox buildFooter() {
        Label footerText = new Label("SmartQueue  |  Join the Queue. Track Your Turn. Save Your Time.");
        footerText.getStyleClass().add("footer-copy");
        VBox footer = new VBox(footerText);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(16));
        footer.getStyleClass().add("footer-bar");
        return footer;
    }

    private Button navButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showLanding() {
        stopLiveRefresh();
        VBox page = new VBox(24);
        page.setAlignment(Pos.TOP_CENTER);
        page.setPadding(new Insets(58, 52, 72, 52));
        page.getStyleClass().add("landing-page");

        Label eyebrow = new Label("NEXT-GEN QUEUE MANAGEMENT");
        eyebrow.getStyleClass().add("eyebrow");
        Label headline = new Label("Join the Queue.\nTrack Your Turn. Save Your Time.");
        headline.getStyleClass().add("hero-title");
        headline.setWrapText(true);
        headline.setTextAlignment(TextAlignment.CENTER);
        Label description = new Label("Generate virtual tokens, follow queue progress, and let staff manage service counters from one shared workflow.");
        description.getStyleClass().add("hero-copy");
        description.setWrapText(true);
        description.setMaxWidth(700);
        description.setTextAlignment(TextAlignment.CENTER);
        HBox actions = new HBox(12, primaryButton("Get a Virtual Token", this::showRegister),
                secondaryButton("Staff & Admin Sign In", this::showLogin));
        actions.setAlignment(Pos.CENTER);
        HBox preview = new HBox(14,
                infoCard("QUEUE CONTROL", "Virtual tokens", "Join a service and track your turn."),
                infoCard("COUNTER STATION", "Staff operations", "Call, serve, complete, skip, and recall."),
                infoCard("SYSTEM INSIGHTS", "Queue analytics", "Review throughput and service volume."));
        preview.setAlignment(Pos.CENTER);
        preview.setMaxWidth(1050);
        page.getChildren().addAll(eyebrow, headline, description, actions, preview);
        setPage(page, false);
    }

    private VBox infoCard(String eyebrow, String heading, String detail) {
        Label type = new Label(eyebrow);
        type.getStyleClass().add("card-eyebrow");
        Label title = new Label(heading);
        title.getStyleClass().add("card-title");
        Label copy = new Label(detail);
        copy.getStyleClass().add("muted-copy");
        copy.setWrapText(true);
        VBox card = new VBox(10, type, title, copy);
        card.setPadding(new Insets(22));
        card.getStyleClass().add("glass-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void showLogin() {
        stopLiveRefresh();
        VBox form = formPanel("Welcome back", "Sign in to access your queue, counter controls, or admin console.");
        TextField email = input("Email address");
        email.setPromptText("alex@smartqueue.com");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        password.getStyleClass().add("glass-input");
        TextField visiblePassword = input("Password");
        StackPane passwordStack = new StackPane(password, visiblePassword);
        CheckBox showPassword = new CheckBox("Show password");
        showPassword.getStyleClass().add("subtle-check");
        visiblePassword.visibleProperty().bind(showPassword.selectedProperty());
        visiblePassword.managedProperty().bind(showPassword.selectedProperty());
        password.visibleProperty().bind(showPassword.selectedProperty().not());
        password.managedProperty().bind(showPassword.selectedProperty().not());
        password.textProperty().bindBidirectional(visiblePassword.textProperty());
        Label error = errorLabel();
        Button submit = new Button("Sign in");
        submit.getStyleClass().add("primary-button");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(event -> submitLogin(email.getText(),
                showPassword.isSelected() ? visiblePassword.getText() : password.getText(), submit, error));

        HBox demos = new HBox(8);
        demos.setAlignment(Pos.CENTER);
        addDemoButton(demos, "Customer", "user@smartqueue.com", password, visiblePassword, submit);
        addDemoButton(demos, "Staff", "staff@smartqueue.com", password, visiblePassword, submit);
        addDemoButton(demos, "Admin", "admin@smartqueue.com", password, visiblePassword, submit);
        Label demoNote = new Label("Demo accounts require explicit development seed data.");
        demoNote.getStyleClass().add("muted-copy");
        demoNote.setWrapText(true);

        form.getChildren().addAll(email, passwordStack, showPassword, error, submit,
                sectionDivider(), demos, demoNote, linkLabel("New to SmartQueue? Create an account", this::showRegister));
        setPage(centered(form), false);
    }

    private void addDemoButton(HBox buttons, String role, String email, PasswordField password,
                               TextField visiblePassword, Button submit) {
        Button demo = secondaryButton(role, () -> { });
        demo.setOnAction(event -> {
            TextField emailField = findEmailField();
            if (emailField != null) {
                emailField.setText(email);
                password.setText("Password123!");
                visiblePassword.setText("Password123!");
                submit.fire();
            }
        });
        buttons.getChildren().add(demo);
    }

    private TextField findEmailField() {
        if (!(root.getCenter() instanceof ScrollPane scrollPane)
                || !(scrollPane.getContent() instanceof VBox wrapper)
                || wrapper.getChildren().isEmpty() || !(wrapper.getChildren().get(0) instanceof VBox form)) {
            return null;
        }
        return form.getChildren().stream().filter(TextField.class::isInstance)
                .map(TextField.class::cast).findFirst().orElse(null);
    }

    private void submitLogin(String email, String password, Button submit, Label error) {
        error.setText("");
        submit.setDisable(true);
        runDatabase(() -> createSession(authService.login(email, password)), user -> {
            currentUser = user;
            root.setTop(buildNavigation());
            openDashboard();
        }, failure -> {
            submit.setDisable(false);
            error.setText(message(failure));
        });
    }

    private AppUser createSession(AppUser user) throws Exception {
        String token = sessionService.createSession(user);
        try {
            sessionStore.saveToken(token);
            return user;
        } catch (IOException ex) {
            sessionService.revoke(token);
            throw ex;
        }
    }

    private void restoreSession() {
        ioExecutor.submit(() -> {
            AppUser restored = null;
            try {
                String token = sessionStore.readToken();
                restored = sessionService.restore(token).orElse(null);
                if (restored == null && token != null) {
                    sessionStore.clear();
                }
            } catch (Exception ignored) {
            }
            AppUser sessionUser = restored;
            if (sessionUser != null) {
                Platform.runLater(() -> {
                    if (currentUser == null) {
                        currentUser = sessionUser;
                        root.setTop(buildNavigation());
                        openDashboard();
                    }
                });
            }
        });
    }

    private void signOut() {
        String token = null;
        try {
            token = sessionStore.readToken();
        } catch (IOException ignored) {
        }
        currentUser = null;
        root.setTop(buildNavigation());
        showLanding();
        String sessionToken = token;
        ioExecutor.submit(() -> {
            try {
                sessionService.revoke(sessionToken);
            } catch (SQLException ignored) {
            } finally {
                try {
                    sessionStore.clear();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void showRegister() {
        stopLiveRefresh();
        VBox form = formPanel("Create your account", "Register for a customer account and join an available service queue.");
        TextField name = input("Full name");
        TextField email = input("Email address");
        TextField phone = input("Phone (optional)");
        PasswordField password = new PasswordField();
        password.setPromptText("Password (at least 6 characters)");
        password.getStyleClass().add("glass-input");
        PasswordField confirmation = new PasswordField();
        confirmation.setPromptText("Confirm password");
        confirmation.getStyleClass().add("glass-input");
        Label error = errorLabel();
        Button submit = new Button("Create customer account");
        submit.getStyleClass().add("primary-button");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(event -> {
            error.setText("");
            if (!password.getText().equals(confirmation.getText())) {
                error.setText("Passwords do not match.");
                return;
            }
            submit.setDisable(true);
            runDatabase(() -> createSession(authService.register(name.getText(), email.getText(), phone.getText(), password.getText())),
                    user -> {
                        currentUser = user;
                        root.setTop(buildNavigation());
                        openDashboard();
                    }, failure -> {
                        submit.setDisable(false);
                        error.setText(message(failure));
                    });
        });
        form.getChildren().addAll(name, email, phone, password, confirmation, error, submit,
                linkLabel("Already registered? Sign in", this::showLogin));
        setPage(centered(form), false);
    }

    private VBox formPanel(String title, String subtitle) {
        Label heading = new Label(title);
        heading.getStyleClass().add("form-title");
        Label description = new Label(subtitle);
        description.getStyleClass().add("muted-copy");
        description.setWrapText(true);
        VBox panel = new VBox(14, heading, description);
        panel.setPadding(new Insets(30));
        panel.setMaxWidth(460);
        panel.getStyleClass().add("glass-card");
        return panel;
    }

    private VBox centered(Node content) {
        VBox wrapper = new VBox(content);
        wrapper.setAlignment(Pos.CENTER);
        wrapper.setPadding(new Insets(34, 20, 50, 20));
        return wrapper;
    }

    private TextField input(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("glass-input");
        return field;
    }

    private Label errorLabel() {
        Label error = new Label();
        error.getStyleClass().add("error-copy");
        error.setWrapText(true);
        return error;
    }

    private Button primaryButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Button secondaryButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("secondary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private Label sectionDivider() {
        Label divider = new Label("DEVELOPMENT LOGIN");
        divider.getStyleClass().add("section-divider");
        return divider;
    }

    private Label linkLabel(String text, Runnable action) {
        Label link = new Label(text);
        link.getStyleClass().add("link-copy");
        link.setOnMouseClicked(event -> action.run());
        return link;
    }

    private void openDashboard() {
        if (currentUser == null) {
            showLogin();
            return;
        }
        switch (currentUser.role()) {
            case USER -> showCustomerDashboard();
            case STAFF -> showStaffDashboard();
            case ADMIN -> showAdminDashboard();
        }
    }

    private void showCustomerDashboard() {
        stopLiveRefresh();
        if (currentUser == null) {
            showLogin();
            return;
        }

        Label message = new Label();
        message.getStyleClass().add("dashboard-message");
        VBox activePanel = lightPanel("My Active Token");
        VBox statusPanel = lightPanel("Queue Status");
        GridPane serviceGrid = new GridPane();
        serviceGrid.setHgap(16);
        serviceGrid.setVgap(16);
        VBox serviceSection = new VBox(14, sectionHeader("Available Services"), serviceGrid);
        TableView<QueueToken> history = historyTable();
        VBox historyPanel = new VBox(12, sectionHeader("Queue History"), history);
        historyPanel.getStyleClass().add("light-panel");
        historyPanel.setPadding(new Insets(22));

        VBox page = new VBox(20, headingBlock("User Dashboard", "Manage your queue and check your waiting status."),
                message, activePanel, statusPanel, serviceSection, historyPanel);
        page.setPadding(new Insets(30, 36, 42, 36));
        page.getStyleClass().add("customer-page");
        setPage(page, true);

        AppUser user = currentUser;
        loadCustomerData(user, activePanel, statusPanel, serviceGrid, history, message, true);
        refreshTask = refreshExecutor.scheduleWithFixedDelay(() ->
                loadCustomerData(user, activePanel, statusPanel, serviceGrid, history, message, false),
                5, 5, TimeUnit.SECONDS);
    }

    private void loadCustomerData(AppUser user, VBox activePanel, VBox statusPanel, GridPane serviceGrid,
                                  TableView<QueueToken> history, Label message, boolean includeHistory) {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.submit(() -> {
            try {
                List<ServiceItem> services = serviceRepository.list(true);
                Optional<QueueToken> activeToken = queueService.activeToken(user);
                List<ServiceQueueSnapshot> overview = queueService.overview();
                List<QueueToken> historyItems = includeHistory ? queueService.history(user) : null;
                Platform.runLater(() -> renderCustomerData(services, activeToken, overview, historyItems,
                        activePanel, statusPanel, serviceGrid, history, message));
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText(message(ex)));
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private void renderCustomerData(List<ServiceItem> services, Optional<QueueToken> activeToken,
                                    List<ServiceQueueSnapshot> overview, List<QueueToken> historyItems,
                                    VBox activePanel, VBox statusPanel, GridPane serviceGrid,
                                    TableView<QueueToken> history, Label message) {
        activePanel.getChildren().clear();
        Label title = sectionHeader("My Active Token");
        activePanel.getChildren().add(title);
        if (activeToken.isPresent()) {
            QueueToken token = activeToken.get();
            GridPane metrics = new GridPane();
            metrics.setHgap(12);
            metrics.setVgap(12);
            metrics.add(lightMetric("Token Number", token.tokenNumber()), 0, 0);
            metrics.add(lightMetric("People Ahead", Integer.toString(token.peopleAhead())), 1, 0);
            metrics.add(lightMetric("Estimated Wait", token.estimatedWaitTime() + " min"), 2, 0);
            metrics.add(lightMetric("Status", token.status().name()), 0, 1);
            metrics.add(lightMetric("Service", token.service().name()), 1, 1);
            metrics.add(lightMetric("Joined", formatTime(token.joinedAt())), 2, 1);
            activePanel.getChildren().add(metrics);
            if (token.counterNumber() != null && !token.counterNumber().isBlank()) {
                activePanel.getChildren().add(new Label("Counter: " + token.counterNumber()));
            }
            Button cancel = new Button("Cancel Token");
            cancel.getStyleClass().add("danger-button");
            cancel.setOnAction(event -> {
                cancel.setDisable(true);
                runAsync(() -> queueService.cancel(currentUser, token.id()), result -> {
                    message.setText("Your queue token has been cancelled.");
                    loadCustomerData(currentUser, activePanel, statusPanel, serviceGrid, history, message, true);
                }, failure -> {
                    cancel.setDisable(false);
                    message.setText(message(failure));
                });
            });
            activePanel.getChildren().add(cancel);
        } else {
            Label empty = new Label("You don't have an active queue token.");
            empty.getStyleClass().add("light-muted");
            activePanel.getChildren().add(empty);
        }

        statusPanel.getChildren().clear();
        statusPanel.getChildren().add(sectionHeader("Queue Status"));
        if (activeToken.isPresent()) {
            QueueToken token = activeToken.get();
            ServiceQueueSnapshot snapshot = overview.stream()
                    .filter(item -> item.service().id() == token.service().id()).findFirst().orElse(null);
            if (snapshot == null) {
                statusPanel.getChildren().add(new Label("Service status unavailable."));
            } else {
                HBox statusItems = new HBox(12,
                        lightMetric("Currently Serving", valueOrDash(snapshot.currentlyServingToken())),
                        lightMetric("People Waiting", Integer.toString(snapshot.waitingCount())),
                        lightMetric("Service", snapshot.service().name()));
                statusPanel.getChildren().add(statusItems);
            }
        } else {
            statusPanel.getChildren().add(new Label("Join a queue to see your live status."));
        }

        serviceGrid.getChildren().clear();
        if (services.isEmpty()) {
            serviceGrid.add(new Label("No services are currently available."), 0, 0);
        } else {
            for (int index = 0; index < services.size(); index++) {
                ServiceItem service = services.get(index);
                VBox card = new VBox(10);
                card.setPadding(new Insets(18));
                card.setPrefWidth(300);
                card.getStyleClass().add("light-service-card");
                Label name = new Label(service.name());
                name.getStyleClass().add("light-card-title");
                Label description = new Label(service.description().isBlank()
                        ? "Queue service available." : service.description());
                description.setWrapText(true);
                description.getStyleClass().add("light-muted");
                Label details = new Label("Prefix  " + service.prefix() + "\nAverage time  "
                        + service.averageServiceTime() + " min\nActive tokens  " + service.activeTokensCount());
                Button join = new Button(activeToken.isPresent() ? "Already in Queue" : "Join Queue");
                join.getStyleClass().add("customer-button");
                join.setMaxWidth(Double.MAX_VALUE);
                join.setDisable(activeToken.isPresent());
                join.setOnAction(event -> {
                    join.setDisable(true);
                    message.setText("Joining queue...");
                    runAsync(() -> queueService.join(currentUser, service.id()), token -> {
                        message.setText("Successfully joined " + service.name() + " with token " + token.tokenNumber() + ".");
                        loadCustomerData(currentUser, activePanel, statusPanel, serviceGrid, history, message, true);
                    }, failure -> {
                        join.setDisable(false);
                        message.setText(message(failure));
                    });
                });
                card.getChildren().addAll(name, description, details, join);
                serviceGrid.add(card, index % 3, index / 3);
            }
        }
        if (historyItems != null) {
            history.setItems(FXCollections.observableArrayList(historyItems));
        }
        if (message.getText().equals("Loading dashboard...")) {
            message.setText("");
        }
    }

    private VBox lightPanel(String title) {
        VBox panel = new VBox(14, sectionHeader(title));
        panel.setPadding(new Insets(22));
        panel.getStyleClass().add("light-panel");
        return panel;
    }

    private VBox lightMetric(String label, String value) {
        Label caption = new Label(label);
        caption.getStyleClass().add("light-muted");
        Label amount = new Label(value);
        amount.getStyleClass().add("light-metric-value");
        VBox metric = new VBox(7, caption, amount);
        metric.setPadding(new Insets(16));
        metric.getStyleClass().add("light-metric");
        return metric;
    }

    private TableView<QueueToken> historyTable() {
        TableView<QueueToken> table = new TableView<>();
        table.setPlaceholder(new Label("No queue history yet."));
        table.setPrefHeight(230);
        TableColumn<QueueToken, String> token = new TableColumn<>("Token");
        token.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().tokenNumber()));
        TableColumn<QueueToken, String> service = new TableColumn<>("Service");
        service.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().name()));
        TableColumn<QueueToken, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status().name()));
        TableColumn<QueueToken, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatTime(cell.getValue().joinedAt())));
        table.getColumns().addAll(token, service, status, date);
        token.setPrefWidth(140);
        service.setPrefWidth(230);
        status.setPrefWidth(140);
        date.setPrefWidth(240);
        table.getStyleClass().add("light-table");
        return table;
    }

    private void showStaffDashboard() {
        stopLiveRefresh();
        if (currentUser.role() != UserRole.STAFF && currentUser.role() != UserRole.ADMIN) {
            showUnauthorized();
            return;
        }

        AppUser user = currentUser;
        Label message = new Label();
        message.getStyleClass().add("dashboard-message");
        ComboBox<ServiceItem> servicePicker = new ComboBox<>();
        servicePicker.setPromptText("Select service");
        servicePicker.getStyleClass().add("glass-input");
        TextField counter = input("Counter 1");
        counter.setText("Counter 1");
        VBox metrics = new VBox(10);
        VBox currentPanel = new VBox(12);
        currentPanel.setPadding(new Insets(22));
        currentPanel.getStyleClass().add("glass-card");
        TableView<ServiceQueueSnapshot> overviewTable = serviceOverviewTable();
        QueueToken[] selectedToken = {null};

        Button start = primaryButton("Start Serving", () -> { });
        Button complete = primaryButton("Complete", () -> { });
        Button recall = secondaryButton("Recall", () -> { });
        Button skip = secondaryButton("Skip", () -> { });
        Button callNext = primaryButton("Call Next", () -> { });
        start.setOnAction(event -> withCurrentToken(selectedToken, message,
            () -> queueService.startServing(user, selectedToken[0].id()), "Service started.", user,
                servicePicker, counter, metrics, currentPanel, overviewTable, selectedToken, message,
                start, complete, recall, skip));
        complete.setOnAction(event -> withCurrentToken(selectedToken, message,
            () -> queueService.complete(user, selectedToken[0].id()), "Customer service completed.", user,
                servicePicker, counter, metrics, currentPanel, overviewTable, selectedToken, message,
                start, complete, recall, skip));
        skip.setOnAction(event -> withCurrentToken(selectedToken, message,
            () -> queueService.skip(user, selectedToken[0].id()), "Customer skipped.", user,
                servicePicker, counter, metrics, currentPanel, overviewTable, selectedToken, message,
                start, complete, recall, skip));
        recall.setOnAction(event -> withCurrentToken(selectedToken, message,
            () -> queueService.recall(user, selectedToken[0].id(), counter.getText()), "Customer recalled.", user,
                servicePicker, counter, metrics, currentPanel, overviewTable, selectedToken, message,
                start, complete, recall, skip));
        callNext.setOnAction(event -> {
            ServiceItem selected = servicePicker.getValue();
            if (selected == null) {
                message.setText("Please select a service first.");
                return;
            }
            message.setText("");
            callNext.setDisable(true);
            runAsync(() -> queueService.callNext(user, selected.id(), counter.getText()), token -> {
                callNext.setDisable(false);
                message.setText(token.map(item -> "Next customer called: " + item.tokenNumber())
                        .orElse("No waiting customers in the queue."));
                refreshStaffData(user, servicePicker, counter, metrics, currentPanel, overviewTable,
                        selectedToken, message, start, complete, recall, skip);
            }, failure -> {
                callNext.setDisable(false);
                message.setText(message(failure));
            });
        });
        HBox controls = new HBox(10, start, complete, recall, skip, callNext);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox serviceControls = new HBox(12, servicePicker, counter);
        serviceControls.setAlignment(Pos.CENTER_RIGHT);
        VBox header = new VBox(14,
                headingBlock("Staff Counter Station", "Manage customers and control the queue in real time."),
                serviceControls);
        header.getStyleClass().add("glass-card");
        header.setPadding(new Insets(22));
        Label overviewTitle = sectionHeader("Service Queue Overview");
        VBox overview = new VBox(14, overviewTitle, overviewTable);
        overview.getStyleClass().add("glass-card");
        overview.setPadding(new Insets(22));
        VBox page = new VBox(18, header, message, metrics, currentPanel, controls, overview);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page, false);

        refreshStaffData(user, servicePicker, counter, metrics, currentPanel, overviewTable,
                selectedToken, message, start, complete, recall, skip);
        refreshTask = refreshExecutor.scheduleWithFixedDelay(() ->
                refreshStaffData(user, servicePicker, counter, metrics, currentPanel, overviewTable,
                        selectedToken, message, start, complete, recall, skip),
                5, 5, TimeUnit.SECONDS);
    }

    private void refreshStaffData(AppUser user, ComboBox<ServiceItem> servicePicker, TextField counter,
                                  VBox metrics, VBox currentPanel, TableView<ServiceQueueSnapshot> overviewTable,
                                  QueueToken[] selectedToken, Label message, Button start, Button complete,
                                  Button recall, Button skip) {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.submit(() -> {
            try {
                List<ServiceItem> services = serviceRepository.list(true);
                List<ServiceQueueSnapshot> overview = queueService.overview();
                Optional<QueueToken> current = queueService.currentForStaff(user);
                Platform.runLater(() -> {
                    ServiceItem priorSelection = servicePicker.getValue();
                    servicePicker.setItems(FXCollections.observableArrayList(services));
                    if (priorSelection != null) {
                        servicePicker.getItems().stream().filter(item -> item.id() == priorSelection.id())
                                .findFirst().ifPresent(servicePicker::setValue);
                    }
                    if (servicePicker.getValue() == null && !services.isEmpty()) {
                        servicePicker.setValue(services.getFirst());
                    }
                    ServiceItem selected = servicePicker.getValue();
                    ServiceQueueSnapshot selectedSnapshot = overview.stream()
                            .filter(item -> selected != null && item.service().id() == selected.id())
                            .findFirst().orElse(null);
                    metrics.getChildren().setAll(
                            metricCard("Queue Waiting", selectedSnapshot == null ? "0" : Integer.toString(selectedSnapshot.waitingCount()), "Customers waiting"),
                            metricCard("Average Service", selected == null ? "0 min" : selected.averageServiceTime() + " min", selected == null ? "Select a service" : selected.name()),
                            metricCard("Counter Status", "ACTIVE", counter.getText()));
                    selectedToken[0] = current.orElse(null);
                    renderCurrentToken(currentPanel, selectedToken[0]);
                    QueueStatus status = selectedToken[0] == null ? null : selectedToken[0].status();
                    start.setDisable(status != QueueStatus.CALLED);
                    complete.setDisable(status != QueueStatus.CALLED && status != QueueStatus.SERVING);
                    skip.setDisable(status != QueueStatus.CALLED);
                    recall.setDisable(status != QueueStatus.CALLED && status != QueueStatus.SKIPPED);
                    overviewTable.setItems(FXCollections.observableArrayList(overview));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> message.setText(message(ex)));
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private void withCurrentToken(QueueToken[] selectedToken, Label message,
                                  Callable<QueueToken> action,
                                  String successMessage, AppUser user, ComboBox<ServiceItem> servicePicker,
                                  TextField counter, VBox metrics, VBox currentPanel,
                                  TableView<ServiceQueueSnapshot> overviewTable, QueueToken[] tokenHolder,
                                  Label statusMessage, Button start, Button complete, Button recall, Button skip) {
        QueueToken token = selectedToken[0];
        if (token == null) {
            message.setText("There is no active token.");
            return;
        }
        message.setText("");
        runAsync(action, result -> {
            statusMessage.setText(successMessage);
            refreshStaffData(user, servicePicker, counter, metrics, currentPanel, overviewTable,
                    tokenHolder, statusMessage, start, complete, recall, skip);
        }, failure -> statusMessage.setText(message(failure)));
    }

    private void showAdminDashboard() {
        stopLiveRefresh();
        if (currentUser.role() != UserRole.ADMIN) {
            showUnauthorized();
            return;
        }

        AppUser user = currentUser;
        Label message = new Label();
        message.getStyleClass().add("dashboard-message");
        HBox metrics = new HBox(14);
        GridPane charts = new GridPane();
        charts.setHgap(16);
        charts.setVgap(16);
        TableView<ServiceItem> services = adminServiceTable();
        VBox servicePanel = new VBox(14, sectionHeader("Service Queue Overview"), services);
        servicePanel.setPadding(new Insets(22));
        servicePanel.getStyleClass().add("glass-card");
        VBox systemStatus = new VBox(12);
        systemStatus.setPadding(new Insets(22));
        systemStatus.getStyleClass().add("glass-card");
        Button refresh = secondaryButton("Refresh", () -> { });
        refresh.setOnAction(event -> loadAdminDashboard(user, metrics, charts, services, systemStatus,
                message, refresh));
        HBox heading = new HBox(16, headingBlock("Administrator Hub", "Monitor services, queues, system health, and analytics."), refresh);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heading.getChildren().getFirst(), Priority.ALWAYS);
        VBox page = new VBox(18, heading, message, metrics, charts, servicePanel, systemStatus);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page, false);
        loadAdminDashboard(user, metrics, charts, services, systemStatus, message, refresh);
    }

    private void loadAdminDashboard(AppUser user, HBox metrics, GridPane charts, TableView<ServiceItem> services,
                                    VBox systemStatus, Label message, Button refresh) {
        message.setText("Loading administration data...");
        refresh.setDisable(true);
        runAsync(() -> {
            List<ServiceItem> serviceList = serviceRepository.list(false);
            AnalyticsSummary analytics = analyticsService.load(user);
            int activeTokens = analyticsService.activeTokenCount();
            return new AdminData(serviceList, analytics, activeTokens, databaseManager.isHealthy());
        }, data -> {
            refresh.setDisable(false);
            message.setText("");
            long totalActive = data.services().stream().mapToLong(ServiceItem::activeTokensCount).sum();
            metrics.getChildren().setAll(
                    metricCard("Active Services", Integer.toString(data.services().size()), "Services configured"),
                    metricCard("Queue Waiting", Long.toString(totalActive), "Active queue tokens"),
                    metricCard("Application", "ONLINE", "JavaFX desktop runtime"),
                    metricCard("Database", data.databaseHealthy() ? "HEALTHY" : "UNAVAILABLE", "SQLite JDBC"));

            AnalyticsSummary analytics = data.analytics();
            charts.getChildren().clear();
            charts.add(barChart("Average Wait Time (minutes)", analytics.averageWaitMinutes()), 0, 0);
            charts.add(barChart("Service Volume", analytics.serviceVolume()), 1, 0);
            charts.add(barChart("Daily Throughput", analytics.dailyThroughput()), 0, 1);
            charts.add(barChart("Busiest Hours", analytics.hourlyDistribution()), 1, 1);
            services.setItems(FXCollections.observableArrayList(data.services()));

            systemStatus.getChildren().setAll(sectionHeader("System Status"),
                    statusRow("Database connection", data.databaseHealthy() ? "Healthy" : "Unavailable"),
                    statusRow("Queue services", data.services().size() + " configured"),
                    statusRow("Active queue tokens", Integer.toString(data.activeTokens())),
                    statusRow("Administrator", user.name() + "  |  " + user.email()));
        }, failure -> {
            refresh.setDisable(false);
            message.setText(message(failure));
        });
    }

    private BarChart<String, Number> barChart(String title, List<Metric> metrics) {
        CategoryAxis categoryAxis = new CategoryAxis();
        NumberAxis numberAxis = new NumberAxis();
        BarChart<String, Number> chart = new BarChart<>(categoryAxis, numberAxis);
        chart.setTitle(title);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(280);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Metric metric : metrics) {
            series.getData().add(new XYChart.Data<>(metric.label(), metric.value()));
        }
        chart.getData().add(series);
        chart.getStyleClass().add("dashboard-chart");
        return chart;
    }

    private VBox metricCard(String title, String value, String detail) {
        Label titleLabel = new Label(title.toUpperCase());
        titleLabel.getStyleClass().add("metric-caption");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("metric-value");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("muted-copy");
        VBox card = new VBox(8, titleLabel, valueLabel, detailLabel);
        card.setPadding(new Insets(18));
        card.getStyleClass().add("glass-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private TableView<ServiceQueueSnapshot> serviceOverviewTable() {
        TableView<ServiceQueueSnapshot> table = new TableView<>();
        table.setPlaceholder(new Label("No services available."));
        table.setPrefHeight(250);
        TableColumn<ServiceQueueSnapshot, String> name = new TableColumn<>("Service");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().name()));
        TableColumn<ServiceQueueSnapshot, String> prefix = new TableColumn<>("Prefix");
        prefix.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().prefix()));
        TableColumn<ServiceQueueSnapshot, String> waiting = new TableColumn<>("Waiting");
        waiting.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Integer.toString(cell.getValue().waitingCount())));
        TableColumn<ServiceQueueSnapshot, String> serving = new TableColumn<>("Current Token");
        serving.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueOrDash(cell.getValue().currentlyServingToken())));
        TableColumn<ServiceQueueSnapshot, String> average = new TableColumn<>("Average");
        average.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().averageServiceTime() + " min"));
        table.getColumns().addAll(name, prefix, waiting, serving, average);
        name.setPrefWidth(260);
        prefix.setPrefWidth(110);
        waiting.setPrefWidth(130);
        serving.setPrefWidth(180);
        average.setPrefWidth(150);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private TableView<ServiceItem> adminServiceTable() {
        TableView<ServiceItem> table = new TableView<>();
        table.setPlaceholder(new Label("No services available."));
        table.setPrefHeight(250);
        TableColumn<ServiceItem, String> name = new TableColumn<>("Service");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        TableColumn<ServiceItem, String> prefix = new TableColumn<>("Prefix");
        prefix.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().prefix()));
        TableColumn<ServiceItem, String> active = new TableColumn<>("Active Tokens");
        active.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Integer.toString(cell.getValue().activeTokensCount())));
        TableColumn<ServiceItem, String> average = new TableColumn<>("Average Time");
        average.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().averageServiceTime() + " min"));
        table.getColumns().addAll(name, prefix, active, average);
        name.setPrefWidth(300);
        prefix.setPrefWidth(130);
        active.setPrefWidth(170);
        average.setPrefWidth(170);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private HBox statusRow(String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("muted-copy");
        Label data = new Label(value);
        data.getStyleClass().add("status-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, key, spacer, data);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox headingBlock(String title, String subtitle) {
        Label heading = new Label(title);
        heading.getStyleClass().add("dashboard-title");
        Label description = new Label(subtitle);
        description.getStyleClass().add("muted-copy");
        VBox block = new VBox(5, heading, description);
        return block;
    }

    private Label sectionHeader(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        return label;
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String formatTime(Instant instant) {
        if (instant == null) {
            return "—";
        }
        return DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
                .withZone(ZoneId.systemDefault()).format(instant);
    }

    private void renderCurrentToken(VBox panel, QueueToken token) {
        panel.getChildren().clear();
        panel.getChildren().add(sectionHeader("Current Customer"));
        if (token == null) {
            Label empty = new Label("No customer is currently assigned to this staff account.");
            empty.getStyleClass().add("muted-copy");
            panel.getChildren().add(empty);
            return;
        }
        Label service = new Label(token.service().name() + "  |  " + token.status());
        service.getStyleClass().add("muted-copy");
        GridPane details = new GridPane();
        details.setHgap(16);
        details.setVgap(12);
        details.add(metricCard("Token", token.tokenNumber(), "Queue token"), 0, 0);
        details.add(metricCard("Customer", token.userName(), token.userEmail()), 1, 0);
        details.add(metricCard("Counter", valueOrDash(token.counterNumber()),
                token.servedByName() == null ? "Staff member" : token.servedByName()), 2, 0);
        panel.getChildren().addAll(service, details);
    }

    private void stopLiveRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    private <T> void runAsync(Callable<T> action, Consumer<T> success, Consumer<Throwable> failure) {
        ioExecutor.submit(() -> {
            try {
                T result = action.call();
                Platform.runLater(() -> success.accept(result));
            } catch (Exception ex) {
                Platform.runLater(() -> failure.accept(ex));
            }
        });
    }

    private void showDashboardPlaceholder(String title, String description) {
        Label heading = new Label(title);
        heading.getStyleClass().add("form-title");
        Label copy = new Label(description);
        copy.getStyleClass().add("muted-copy");
        copy.setWrapText(true);
        VBox panel = new VBox(14, heading, copy, primaryButton("Back to home", this::showLanding));
        panel.setPadding(new Insets(32));
        panel.getStyleClass().add("glass-card");
        setPage(centered(panel), false);
    }

    private void showUnauthorized() {
        Label heading = new Label("Access Denied");
        heading.getStyleClass().add("form-title");
        Label copy = new Label("Your current account role does not have permission to view this restricted page.");
        copy.getStyleClass().add("muted-copy");
        copy.setWrapText(true);
        VBox panel = new VBox(14, heading, copy,
                primaryButton("Back to my dashboard", this::openDashboard),
                secondaryButton("Home", this::showLanding));
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(32));
        panel.getStyleClass().add("glass-card");
        setPage(centered(panel), false);
    }

    private void setPage(Node page, boolean lightTheme) {
        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add(lightTheme ? "light-scroll-pane" : "dark-scroll-pane");
        root.setCenter(scrollPane);
    }

    private void runDatabase(java.util.concurrent.Callable<AppUser> action, Consumer<AppUser> success,
                             Consumer<Throwable> failure) {
        ioExecutor.submit(() -> {
            try {
                AppUser user = action.call();
                Platform.runLater(() -> success.accept(user));
            } catch (Exception ex) {
                Platform.runLater(() -> failure.accept(ex));
            }
        });
    }

    private String message(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        if (current instanceof SQLException && current.getMessage() != null
                && current.getMessage().contains("UNIQUE constraint failed")) {
            return "An account with this email already exists.";
        }
        return current.getMessage() == null ? "The request could not be completed." : current.getMessage();
    }

    private record AdminData(List<ServiceItem> services, AnalyticsSummary analytics, int activeTokens,
                             boolean databaseHealthy) {
    }
}