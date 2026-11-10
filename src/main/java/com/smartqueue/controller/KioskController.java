package com.smartqueue.controller;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.GuestQueueEntry;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.service.QueueService;
import com.smartqueue.service.AuthService;
import com.smartqueue.service.AdminService;
import com.smartqueue.service.AnalyticsService;
import com.smartqueue.model.AnalyticsSummary;
import com.smartqueue.model.Metric;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class KioskController {
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
    private DatabaseManager databaseManager;
    private QueueService queueService;
    private AuthService authService;
    private AdminService adminService;
    private ServiceRepository serviceRepository;
    private UserRepository userRepository;
    private TextField nameField;
    private Spinner<Integer> ageSpinner;
    private ComboBox<ServiceItem> servicePicker;
    private Button joinButton;
    private Button cancelButton;
    private Button newVisitorButton;
    private Button navigationButton;
    private Button accountButton;
    private Label messageLabel;
    private VBox tokenPanel;
    private VBox historyPanel;
    private TableView<QueueToken> historyTable;
    private TableView<ServiceQueueSnapshot> queueTable;
    private ComboBox<ServiceItem> operatorServicePicker;
    private Label operatorMessageLabel;
    private Label nowServingLabel;
    private TableView<QueueToken> operatorTokenTable;
    private TableView<AppUser> adminStaffTable;
    private TableView<ServiceItem> adminServiceTable;
    private Label adminAnalyticsLabel;
    private Button callNextButton;
    private Button startServingButton;
    private Button completeTokenButton;
    private Button skipTokenButton;
    private Button recallTokenButton;
    private AppUser currentGuest;
    private AppUser currentAdmin;
    private boolean authenticatedCustomer;
    private QueueToken currentToken;
    private QueueToken operatorCurrentToken;
    private ScheduledFuture<?> refreshTask;

    @FXML
    private void initialize() {
        databaseManager = new DatabaseManager();
        QueueRepository queueRepository = new QueueRepository(databaseManager);
        serviceRepository = new ServiceRepository(databaseManager);
        userRepository = new UserRepository(databaseManager);
        authService = new AuthService(userRepository);
        queueService = new QueueService(databaseManager, queueRepository, serviceRepository, userRepository);
        adminService = new AdminService(authService, serviceRepository,
                new AnalyticsService(databaseManager, queueRepository), userRepository, queueRepository);
        root.setTop(buildHeader());
        root.setBottom(buildFooter());
        showKiosk();
    }

    public void shutdown() {
        stopLiveRefresh();
        ioExecutor.shutdownNow();
        refreshExecutor.shutdownNow();
    }

    private HBox buildHeader() {
        Label brand = new Label("SmartQueue");
        brand.getStyleClass().add("brand-button");
        Label mode = new Label("CUSTOMER QUEUE KIOSK");
        mode.getStyleClass().add("identity-label");
        navigationButton = new Button("Operator Login");
        navigationButton.getStyleClass().add("nav-button");
        navigationButton.setOnAction(event -> openOperatorDashboard());
        accountButton = new Button("Customer Login");
        accountButton.getStyleClass().add("nav-button");
        accountButton.setOnAction(event -> handleCustomerAccount());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(18, brand, spacer, accountButton, navigationButton, mode);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 32, 12, 32));
        header.getStyleClass().add("top-navigation");
        return header;
    }

    private VBox buildFooter() {
        Label footerText = new Label("Join the Queue. Track Your Turn. Save Your Time.");
        footerText.getStyleClass().add("footer-copy");
        VBox footer = new VBox(footerText);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(16));
        footer.getStyleClass().add("footer-bar");
        return footer;
    }

    private void showKiosk() {
        stopLiveRefresh();
        navigationButton.setText("Operator Login");
        navigationButton.setOnAction(event -> openOperatorDashboard());
        accountButton.setDisable(false);
        accountButton.setText(authenticatedCustomer ? "Sign Out" : "Customer Login");
        nameField = new TextField();
        nameField.setPromptText("Full name");
        nameField.getStyleClass().add("glass-input");
        ageSpinner = new Spinner<>();
        ageSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 130, 18));
        ageSpinner.setEditable(false);
        ageSpinner.getStyleClass().add("glass-input");
        if (authenticatedCustomer && currentGuest != null) {
            nameField.setText(currentGuest.name());
            nameField.setDisable(true);
            if (currentGuest.age() != null) {
                ageSpinner.getValueFactory().setValue(currentGuest.age());
            }
            ageSpinner.setDisable(true);
        }
        servicePicker = new ComboBox<>();
        servicePicker.setPromptText("Choose a service");
        servicePicker.getStyleClass().add("glass-input");
        servicePicker.setMaxWidth(Double.MAX_VALUE);
        messageLabel = new Label("Enter your details and select a service to join the queue.");
        messageLabel.getStyleClass().add("dashboard-message");
        messageLabel.setWrapText(true);

        joinButton = new Button("Join Queue");
        joinButton.getStyleClass().add("primary-button");
        joinButton.setOnAction(event -> joinQueue());
        cancelButton = new Button("Cancel Token");
        cancelButton.getStyleClass().add("danger-button");
        cancelButton.setOnAction(event -> cancelQueue());
        newVisitorButton = new Button(authenticatedCustomer ? "New Queue Token" : "New Visitor");
        newVisitorButton.getStyleClass().add("secondary-button");
        newVisitorButton.setOnAction(event -> resetForNewVisitor());
        newVisitorButton.setVisible(false);
        newVisitorButton.setManaged(false);

        GridPane fields = new GridPane();
        fields.setHgap(14);
        fields.setVgap(8);
        ColumnConstraintsSupport.configure(fields);
        fields.add(fieldLabel("Name"), 0, 0);
        fields.add(fieldLabel("Age"), 1, 0);
        fields.add(nameField, 0, 1);
        fields.add(ageSpinner, 1, 1);
        fields.add(fieldLabel("Service"), 0, 2);
        fields.add(servicePicker, 0, 3, 2, 1);
        fields.add(joinButton, 0, 4);
        fields.add(newVisitorButton, 1, 4);

        VBox formPanel = new VBox(16, sectionHeader("Join a Queue"),
                new Label("No account is needed. Enter your name and age, then select the service you need."), fields,
                messageLabel);
        formPanel.setPadding(new Insets(24));
        formPanel.setMaxWidth(620);
        formPanel.getStyleClass().add("glass-card");
        tokenPanel = new VBox(12);
        tokenPanel.setPadding(new Insets(24));
        tokenPanel.getStyleClass().add("glass-card");
        tokenPanel.getChildren().addAll(sectionHeader("Your Queue Token"), emptyMessage("No active visit."));
        queueTable = queueOverviewTable();
        VBox overviewPanel = new VBox(14, sectionHeader("Current Queue Status"), queueTable);
        overviewPanel.setPadding(new Insets(24));
        overviewPanel.getStyleClass().add("glass-card");
        historyTable = customerHistoryTable();
        historyPanel = new VBox(14, sectionHeader("Your Recent Tokens"), historyTable);
        historyPanel.setPadding(new Insets(24));
        historyPanel.getStyleClass().add("glass-card");
        historyPanel.setVisible(authenticatedCustomer);
        historyPanel.setManaged(authenticatedCustomer);

        VBox page = new VBox(20,
                headingBlock("Welcome", "Join a service queue and follow your token status."),
                formPanel, tokenPanel, historyPanel, overviewPanel);
        page.setAlignment(Pos.TOP_CENTER);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page);
        loadKioskData();
        if (authenticatedCustomer) {
            loadCustomerHistory();
            AppUser customer = currentGuest;
            runAsync(() -> queueService.activeToken(customer), active -> active.ifPresent(token -> {
                currentToken = token;
                renderToken(token);
                servicePicker.setDisable(true);
                joinButton.setDisable(true);
                newVisitorButton.setVisible(true);
                newVisitorButton.setManaged(true);
            }), failure -> messageLabel.setText(message(failure)));
        }
        refreshTask = refreshExecutor.scheduleWithFixedDelay(this::refreshQueueStatus, 5, 5, TimeUnit.SECONDS);
    }

    private TableView<QueueToken> customerHistoryTable() {
        TableView<QueueToken> table = new TableView<>();
        table.setPlaceholder(new Label("Sign in to view your recent queue tokens."));
        table.setPrefHeight(190);
        TableColumn<QueueToken, String> token = new TableColumn<>("Token");
        token.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().tokenNumber()));
        TableColumn<QueueToken, String> service = new TableColumn<>("Service");
        service.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().name()));
        TableColumn<QueueToken, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status().name()));
        TableColumn<QueueToken, String> joined = new TableColumn<>("Joined");
        joined.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatTime(cell.getValue().joinedAt())));
        table.getColumns().addAll(token, service, status, joined);
        token.setPrefWidth(150);
        service.setPrefWidth(300);
        status.setPrefWidth(150);
        joined.setPrefWidth(220);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private void handleCustomerAccount() {
        if (authenticatedCustomer) {
            authenticatedCustomer = false;
            currentGuest = null;
            newVisitorButton.setText("New Visitor");
            currentToken = null;
            accountButton.setText("Customer Login");
            nameField.clear();
            nameField.setDisable(false);
            ageSpinner.setDisable(false);
            servicePicker.setDisable(false);
            joinButton.setDisable(false);
            newVisitorButton.setVisible(false);
            newVisitorButton.setManaged(false);
            tokenPanel.getChildren().setAll(sectionHeader("Your Queue Token"), emptyMessage("No active visit."));
            historyTable.getItems().clear();
            historyPanel.setVisible(false);
            historyPanel.setManaged(false);
            messageLabel.setText("You have signed out. You can still join as a guest.");
            return;
        }

        Optional<AppUser> signedIn = promptCustomerAccount();
        if (signedIn.isEmpty()) {
            return;
        }
        currentGuest = signedIn.get();
        authenticatedCustomer = true;
        currentToken = null;
        accountButton.setText("Sign Out");
        newVisitorButton.setText("New Queue Token");
        servicePicker.setDisable(false);
        joinButton.setDisable(false);
        tokenPanel.getChildren().setAll(sectionHeader("Your Queue Token"), emptyMessage("No active visit."));
        nameField.setText(currentGuest.name());
        nameField.setDisable(true);
        if (currentGuest.age() != null) {
            ageSpinner.getValueFactory().setValue(currentGuest.age());
        }
        ageSpinner.setDisable(true);
        messageLabel.setText("Signed in as " + currentGuest.name() + ". Your tokens will be saved to your account.");
        historyPanel.setVisible(true);
        historyPanel.setManaged(true);
        loadCustomerHistory();
        runAsync(() -> queueService.activeToken(currentGuest), active -> {
            if (active.isPresent()) {
                currentToken = active.get();
                renderToken(currentToken);
                servicePicker.setDisable(true);
                joinButton.setDisable(true);
                newVisitorButton.setVisible(true);
                newVisitorButton.setManaged(true);
            }
        }, failure -> messageLabel.setText(message(failure)));
    }

    private Optional<AppUser> promptCustomerAccount() {
        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Customer Account");
            dialog.setHeaderText("Sign in or create a customer account");
            ButtonType continueButton = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(continueButton, ButtonType.CANCEL);

            TextField loginEmail = new TextField();
            loginEmail.setPromptText("Email address");
            PasswordField loginPassword = new PasswordField();
            loginPassword.setPromptText("Password");
            VBox loginForm = new VBox(10, loginEmail, loginPassword);

            TextField registerName = new TextField();
            registerName.setPromptText("Full name");
            TextField registerEmail = new TextField();
            registerEmail.setPromptText("Email address");
            PasswordField registerPassword = new PasswordField();
            registerPassword.setPromptText("Password (at least 8 characters)");
            VBox registerForm = new VBox(10, registerName, registerEmail, registerPassword);

            Tab loginTab = new Tab("Sign In", loginForm);
            Tab registerTab = new Tab("Create Account", registerForm);
            TabPane tabs = new TabPane(loginTab, registerTab);
            tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            dialog.getDialogPane().setContent(tabs);
            dialog.getDialogPane().setPrefWidth(430);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != continueButton) {
                return Optional.empty();
            }
            try {
                AppUser account;
                if (tabs.getSelectionModel().getSelectedItem() == loginTab) {
                    account = authService.authenticate(loginEmail.getText(), loginPassword.getText().toCharArray())
                            .filter(user -> user.role() == UserRole.USER)
                            .orElseThrow(() -> new IllegalArgumentException("Invalid customer credentials or inactive account."));
                } else {
                    account = authService.registerCustomer(registerName.getText(), registerEmail.getText(),
                            registerPassword.getText().toCharArray());
                }
                return Optional.of(account);
            } catch (Exception ex) {
                showError("Customer account", message(ex));
            }
        }
    }

    private void loadCustomerHistory() {
        if (!authenticatedCustomer || currentGuest == null || historyTable == null) {
            return;
        }
        AppUser customer = currentGuest;
        runAsync(() -> queueService.history(customer), history ->
                historyTable.setItems(FXCollections.observableArrayList(history)),
                failure -> messageLabel.setText(message(failure)));
    }

    private void openOperatorDashboard() {
        if (currentAdmin == null) {
            Optional<AppUser> operator = promptOperatorLogin();
            if (operator.isEmpty()) {
                return;
            }
            currentAdmin = operator.get();
        }
        accountButton.setDisable(true);
        showOperatorDashboard();
    }

    private Optional<AppUser> promptOperatorLogin() {
        while (true) {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("Operator Sign In");
            dialog.setHeaderText("Staff and administrator access");
            ButtonType signInButton = new ButtonType("Sign In", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(signInButton, ButtonType.CANCEL);
            TextField email = new TextField();
            email.setPromptText("Work email");
            PasswordField password = new PasswordField();
            password.setPromptText("Password");
            GridPane form = new GridPane();
            form.setHgap(12);
            form.setVgap(12);
            form.add(fieldLabel("Email"), 0, 0);
            form.add(email, 1, 0);
            form.add(fieldLabel("Password"), 0, 1);
            form.add(password, 1, 1);
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().setPrefWidth(460);

            Optional<ButtonType> choice = dialog.showAndWait();
            if (choice.isEmpty() || choice.get() != signInButton) {
                return Optional.empty();
            }
            try {
                AppUser operator = authService.authenticate(email.getText(), password.getText().toCharArray())
                        .filter(user -> user.role() == UserRole.STAFF || user.role() == UserRole.ADMIN)
                        .orElseThrow(() -> new IllegalArgumentException("Invalid operator credentials or inactive account."));
                return Optional.of(operator);
            } catch (Exception ex) {
                showError("Operator sign in", message(ex));
            }
        }
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void logoutOperator() {
        currentAdmin = null;
        operatorCurrentToken = null;
        accountButton.setDisable(false);
        showKiosk();
    }

    private void showOperatorDashboard() {
        if (currentAdmin == null || !currentAdmin.active()) {
            return;
        }
        stopLiveRefresh();
        navigationButton.setText("Sign Out");
        navigationButton.setOnAction(event -> logoutOperator());
        operatorServicePicker = new ComboBox<>();
        operatorServicePicker.setPromptText("Select a service");
        operatorServicePicker.getStyleClass().add("glass-input");
        operatorServicePicker.setMaxWidth(Double.MAX_VALUE);
        operatorServicePicker.setOnAction(event -> {
            updateOperatorActionState();
            loadOperatorQueue();
        });
        nowServingLabel = new Label("Loading current service...");
        nowServingLabel.getStyleClass().add("metric-value");
        nowServingLabel.setWrapText(true);
        operatorMessageLabel = new Label("Loading the operator dashboard...");
        operatorMessageLabel.getStyleClass().add("dashboard-message");
        operatorMessageLabel.setWrapText(true);

        callNextButton = new Button("Call Next");
        callNextButton.getStyleClass().add("primary-button");
        callNextButton.setOnAction(event -> callNextCustomer());
        startServingButton = new Button("Start Serving");
        startServingButton.getStyleClass().add("secondary-button");
        startServingButton.setOnAction(event -> startServingCurrent());
        completeTokenButton = new Button("Complete");
        completeTokenButton.getStyleClass().add("primary-button");
        completeTokenButton.setOnAction(event -> completeCurrent());
        skipTokenButton = new Button("Skip");
        skipTokenButton.getStyleClass().add("danger-button");
        skipTokenButton.setOnAction(event -> skipCurrent());
        recallTokenButton = new Button("Recall Selected");
        recallTokenButton.getStyleClass().add("secondary-button");
        recallTokenButton.setOnAction(event -> recallSelected());

        HBox actions = new HBox(10, callNextButton, startServingButton,
                completeTokenButton, skipTokenButton, recallTokenButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        operatorTokenTable = operatorQueueTable();
        operatorTokenTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateOperatorActionState());

        VBox controls = new VBox(14,
                sectionHeader("Queue Controls"),
                fieldLabel("Service"),
                operatorServicePicker,
                fieldLabel("Current Customer"),
                nowServingLabel,
                actions,
                operatorMessageLabel);
        controls.setPadding(new Insets(24));
        controls.setMaxWidth(980);
        controls.getStyleClass().add("glass-card");

        queueTable = queueOverviewTable();
        VBox overviewPanel = new VBox(14, sectionHeader("Service Overview"), queueTable);
        overviewPanel.setPadding(new Insets(24));
        overviewPanel.getStyleClass().add("glass-card");

        VBox operationalPanel = new VBox(14, sectionHeader("Waiting and Active Tokens"), operatorTokenTable);
        operationalPanel.setPadding(new Insets(24));
        operationalPanel.getStyleClass().add("glass-card");

        VBox page = new VBox(20,
                headingBlock("Queue Operator Dashboard",
                        "Call customers in FIFO order, start service, complete or skip a token, and recall skipped customers."),
                controls, operationalPanel, overviewPanel);
        if (currentAdmin.role() == UserRole.ADMIN) {
            page.getChildren().add(buildAdminTools());
        }
        page.setAlignment(Pos.TOP_CENTER);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page);
        loadOperatorData();
    }

    private VBox buildAdminTools() {
        adminStaffTable = adminStaffTable();
        adminServiceTable = adminServiceTable();
        adminAnalyticsLabel = new Label("Loading analytics...");
        adminAnalyticsLabel.setWrapText(true);
        adminAnalyticsLabel.getStyleClass().add("muted-copy");

        Button addStaffButton = new Button("Create Staff Account");
        addStaffButton.getStyleClass().add("primary-button");
        addStaffButton.setOnAction(event -> promptCreateStaff());
        Button toggleStaffButton = new Button("Activate / Deactivate Staff");
        toggleStaffButton.getStyleClass().add("secondary-button");
        toggleStaffButton.setOnAction(event -> toggleSelectedStaff());
        Button addServiceButton = new Button("Add Service");
        addServiceButton.getStyleClass().add("primary-button");
        addServiceButton.setOnAction(event -> promptCreateService());
        Button toggleServiceButton = new Button("Activate / Deactivate Service");
        toggleServiceButton.getStyleClass().add("secondary-button");
        toggleServiceButton.setOnAction(event -> toggleSelectedService());
        Button refreshReportsButton = new Button("Refresh Reports");
        refreshReportsButton.getStyleClass().add("secondary-button");
        refreshReportsButton.setOnAction(event -> refreshAdminTools());

        VBox staffPanel = new VBox(10, sectionHeader("Staff Accounts"), adminStaffTable,
                new HBox(10, addStaffButton, toggleStaffButton));
        VBox servicePanel = new VBox(10, sectionHeader("Service Departments"), adminServiceTable,
                new HBox(10, addServiceButton, toggleServiceButton));
        VBox reportsPanel = new VBox(10, sectionHeader("Queue Analytics"), adminAnalyticsLabel,
                refreshReportsButton);
        VBox panel = new VBox(18, sectionHeader("Administrator Console"), staffPanel, servicePanel, reportsPanel);
        panel.setPadding(new Insets(24));
        panel.setMaxWidth(1100);
        panel.getStyleClass().add("glass-card");
        refreshAdminTools();
        return panel;
    }

    private TableView<AppUser> adminStaffTable() {
        TableView<AppUser> table = new TableView<>();
        table.setPlaceholder(new Label("No staff accounts have been created."));
        table.setPrefHeight(170);
        TableColumn<AppUser, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        TableColumn<AppUser, String> role = new TableColumn<>("Role");
        role.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().role().name()));
        TableColumn<AppUser, String> active = new TableColumn<>("Status");
        active.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().active() ? "Active" : "Inactive"));
        table.getColumns().addAll(name, role, active);
        name.setPrefWidth(360);
        role.setPrefWidth(180);
        active.setPrefWidth(180);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private TableView<ServiceItem> adminServiceTable() {
        TableView<ServiceItem> table = new TableView<>();
        table.setPlaceholder(new Label("No service departments are configured."));
        table.setPrefHeight(170);
        TableColumn<ServiceItem, String> name = new TableColumn<>("Service");
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        TableColumn<ServiceItem, String> prefix = new TableColumn<>("Token Prefix");
        prefix.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().prefix()));
        TableColumn<ServiceItem, String> active = new TableColumn<>("Status");
        active.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().active() ? "Active" : "Inactive"));
        TableColumn<ServiceItem, String> tokens = new TableColumn<>("Active Tokens");
        tokens.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Integer.toString(cell.getValue().activeTokensCount())));
        table.getColumns().addAll(name, prefix, active, tokens);
        name.setPrefWidth(320);
        prefix.setPrefWidth(150);
        active.setPrefWidth(150);
        tokens.setPrefWidth(150);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private void refreshAdminTools() {
        if (currentAdmin == null || currentAdmin.role() != UserRole.ADMIN
                || adminStaffTable == null || adminServiceTable == null || adminAnalyticsLabel == null) {
            return;
        }
        AppUser administrator = currentAdmin;
        runAsync(() -> new AdminData(adminService.staffAccounts(administrator),
                        serviceRepository.list(false), adminService.analytics(administrator)), data -> {
                    adminStaffTable.setItems(FXCollections.observableArrayList(data.staff()));
                    adminServiceTable.setItems(FXCollections.observableArrayList(data.services()));
                    adminAnalyticsLabel.setText(formatAnalytics(data.analytics()));
                }, failure -> showError("Administrator console", message(failure)));
    }

    private String formatAnalytics(AnalyticsSummary summary) {
        return "Average queue wait (minutes): " + formatMetrics(summary.averageWaitMinutes())
                + "\nDaily completed tokens: " + formatMetrics(summary.dailyThroughput())
                + "\nHourly arrivals: " + formatMetrics(summary.hourlyDistribution())
                + "\nService volume: " + formatMetrics(summary.serviceVolume());
    }

    private String formatMetrics(List<Metric> metrics) {
        if (metrics.isEmpty()) {
            return "No data yet";
        }
        return metrics.stream().map(metric -> metric.label() + " = " + metric.value())
                .reduce((left, right) -> left + ", " + right).orElse("No data yet");
    }

    private void promptCreateStaff() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Create Staff Account");
        dialog.setHeaderText("Create credentials for a queue operator");
        ButtonType save = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField name = new TextField();
        name.setPromptText("Full name");
        TextField email = new TextField();
        email.setPromptText("Work email");
        PasswordField password = new PasswordField();
        password.setPromptText("Password (at least 8 characters)");
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.add(fieldLabel("Name"), 0, 0);
        form.add(name, 1, 0);
        form.add(fieldLabel("Email"), 0, 1);
        form.add(email, 1, 1);
        form.add(fieldLabel("Password"), 0, 2);
        form.add(password, 1, 2);
        dialog.getDialogPane().setContent(form);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != save) {
            return;
        }
        runAsync(() -> adminService.createStaff(currentAdmin, name.getText(), email.getText(),
                        password.getText().toCharArray()), created -> {
                    operatorMessageLabel.setText("Created staff account for " + created.name() + ".");
                    refreshAdminTools();
                }, failure -> showError("Create staff account", message(failure)));
        password.clear();
    }

    private void toggleSelectedStaff() {
        AppUser selected = adminStaffTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Staff accounts", "Select a staff account first.");
            return;
        }
        runAsync(() -> {
            adminService.setStaffActive(currentAdmin, selected.id(), !selected.active());
            return null;
        }, ignored -> refreshAdminTools(), failure -> showError("Staff accounts", message(failure)));
    }

    private void promptCreateService() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Service Department");
        dialog.setHeaderText("Configure a queue for a service");
        ButtonType save = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField name = new TextField();
        name.setPromptText("Service name");
        TextField prefix = new TextField();
        prefix.setPromptText("Token prefix (letters only)");
        TextField description = new TextField();
        description.setPromptText("Description");
        TextField average = new TextField("5");
        average.setPromptText("Average minutes per customer");
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.add(fieldLabel("Name"), 0, 0);
        form.add(name, 1, 0);
        form.add(fieldLabel("Prefix"), 0, 1);
        form.add(prefix, 1, 1);
        form.add(fieldLabel("Description"), 0, 2);
        form.add(description, 1, 2);
        form.add(fieldLabel("Average minutes"), 0, 3);
        form.add(average, 1, 3);
        dialog.getDialogPane().setContent(form);
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != save) {
            return;
        }
        try {
            int minutes = Integer.parseInt(average.getText().trim());
            runAsync(() -> adminService.createService(currentAdmin, name.getText(), prefix.getText(),
                            description.getText(), minutes), created -> {
                        operatorMessageLabel.setText("Created service " + created.name() + ".");
                        refreshAdminTools();
                        loadOperatorData();
                    }, failure -> showError("Create service", message(failure)));
        } catch (NumberFormatException ex) {
            showError("Create service", "Average minutes must be a whole number.");
        }
    }

    private void toggleSelectedService() {
        ServiceItem selected = adminServiceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Service departments", "Select a service first.");
            return;
        }
        runAsync(() -> {
            adminService.setServiceActive(currentAdmin, selected.id(), !selected.active());
            return null;
        }, ignored -> {
            refreshAdminTools();
            loadOperatorData();
        }, failure -> showError("Service departments", message(failure)));
    }

    private TableView<QueueToken> operatorQueueTable() {
        TableView<QueueToken> table = new TableView<>();
        table.setPlaceholder(new Label("No waiting or active tokens for this service."));
        table.setPrefHeight(270);
        TableColumn<QueueToken, String> token = new TableColumn<>("Token");
        token.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().tokenNumber()));
        TableColumn<QueueToken, String> customer = new TableColumn<>("Customer");
        customer.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().userName()));
        TableColumn<QueueToken, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status().name()));
        TableColumn<QueueToken, String> joined = new TableColumn<>("Joined");
        joined.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatTime(cell.getValue().joinedAt())));
        TableColumn<QueueToken, String> counter = new TableColumn<>("Counter");
        counter.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueOrDash(cell.getValue().counterNumber())));
        table.getColumns().addAll(token, customer, status, joined, counter);
        token.setPrefWidth(130);
        customer.setPrefWidth(220);
        status.setPrefWidth(130);
        joined.setPrefWidth(210);
        counter.setPrefWidth(130);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private void loadOperatorData() {
        runAsync(() -> {
            List<ServiceItem> services = serviceRepository.list(true);
            List<ServiceQueueSnapshot> status = queueService.overview();
            AppUser operator = currentAdmin;
            if (operator == null || !operator.active()
                    || (operator.role() != UserRole.STAFF && operator.role() != UserRole.ADMIN)) {
                throw new SecurityException("Please sign in with an active staff or administrator account.");
            }
            Optional<QueueToken> current = queueService.currentForStaff(operator);
            return new OperatorData(services, status, operator, current);
        }, data -> {
            operatorServicePicker.setItems(FXCollections.observableArrayList(data.services()));
            queueTable.setItems(FXCollections.observableArrayList(data.queueStatus()));
            currentAdmin = data.admin();
            operatorCurrentToken = data.current().orElse(null);

            if (operatorCurrentToken != null) {
                data.services().stream()
                        .filter(service -> service.id() == operatorCurrentToken.service().id())
                        .findFirst().ifPresent(operatorServicePicker::setValue);
            }
            if (operatorServicePicker.getValue() == null && !data.services().isEmpty()) {
                operatorServicePicker.setValue(data.services().getFirst());
            }
            if (data.services().isEmpty()) {
                operatorMessageLabel.setText("No active services are available.");
            } else {
                operatorMessageLabel.setText("Signed in as " + currentAdmin.name() + " ("
                        + currentAdmin.role().name() + ").");
            }
            updateOperatorCurrentCustomer();
            updateOperatorActionState();
            loadOperatorQueue();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            setOperatorActionsDisabled(true);
        });
    }

    private void loadOperatorQueue() {
        ServiceItem selected = operatorServicePicker == null ? null : operatorServicePicker.getValue();
        if (selected == null || currentAdmin == null || operatorTokenTable == null) {
            return;
        }
        AppUser operator = currentAdmin;
        runAsync(() -> queueService.operationalQueue(operator, selected.id()), tokens -> {
            operatorTokenTable.setItems(FXCollections.observableArrayList(tokens));
            if (operatorCurrentToken != null) {
                tokens.stream().filter(token -> token.id() == operatorCurrentToken.id()).findFirst()
                        .ifPresent(token -> operatorTokenTable.getSelectionModel().select(token));
            }
            updateOperatorActionState();
        }, failure -> operatorMessageLabel.setText(message(failure)));
    }

    private void callNextCustomer() {
        ServiceItem service = operatorServicePicker.getValue();
        if (service == null || currentAdmin == null) {
            return;
        }
        setOperatorActionsDisabled(true);
        runAsync(() -> queueService.callNext(currentAdmin, service.id(), "Counter 1"), called -> {
            operatorCurrentToken = called.orElse(null);
            operatorMessageLabel.setText(called.map(token -> "Called " + token.tokenNumber()
                    + " for " + token.userName() + ".").orElse("No customers are waiting for this service."));
            updateOperatorCurrentCustomer();
            refreshOperatorViews();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
    }

    private void startServingCurrent() {
        if (currentAdmin == null || operatorCurrentToken == null) {
            return;
        }
        long tokenId = operatorCurrentToken.id();
        setOperatorActionsDisabled(true);
        runAsync(() -> queueService.startServing(currentAdmin, tokenId), serving -> {
            operatorCurrentToken = serving;
            operatorMessageLabel.setText("Service started for " + serving.tokenNumber() + ".");
            updateOperatorCurrentCustomer();
            refreshOperatorViews();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
    }

    private void completeCurrent() {
        if (currentAdmin == null || operatorCurrentToken == null) {
            return;
        }
        long tokenId = operatorCurrentToken.id();
        setOperatorActionsDisabled(true);
        runAsync(() -> queueService.complete(currentAdmin, tokenId), completed -> {
            operatorCurrentToken = null;
            operatorMessageLabel.setText("Completed " + completed.tokenNumber() + ". Call the next customer when ready.");
            updateOperatorCurrentCustomer();
            refreshOperatorViews();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
    }

    private void skipCurrent() {
        if (currentAdmin == null || operatorCurrentToken == null) {
            return;
        }
        long tokenId = operatorCurrentToken.id();
        setOperatorActionsDisabled(true);
        runAsync(() -> queueService.skip(currentAdmin, tokenId), skipped -> {
            operatorCurrentToken = null;
            operatorMessageLabel.setText("Skipped " + skipped.tokenNumber() + ". You can recall it from the token list.");
            updateOperatorCurrentCustomer();
            refreshOperatorViews();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
    }

    private void recallSelected() {
        QueueToken selected = operatorTokenTable.getSelectionModel().getSelectedItem();
        if (currentAdmin == null || selected == null || selected.status() != QueueStatus.SKIPPED) {
            operatorMessageLabel.setText("Select a skipped token to recall.");
            return;
        }
        setOperatorActionsDisabled(true);
        runAsync(() -> queueService.recall(currentAdmin, selected.id(), "Counter 1"), recalled -> {
            operatorCurrentToken = recalled;
            operatorMessageLabel.setText("Recalled " + recalled.tokenNumber() + ". Start serving when ready.");
            updateOperatorCurrentCustomer();
            refreshOperatorViews();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
    }

    private void refreshOperatorViews() {
        runAsync(queueService::overview, status -> queueTable.setItems(FXCollections.observableArrayList(status)),
                failure -> operatorMessageLabel.setText(message(failure)));
        loadOperatorQueue();
        updateOperatorCurrentCustomer();
        updateOperatorActionState();
    }

    private void setOperatorActionsDisabled(boolean disabled) {
        callNextButton.setDisable(disabled);
        startServingButton.setDisable(disabled);
        completeTokenButton.setDisable(disabled);
        skipTokenButton.setDisable(disabled);
        recallTokenButton.setDisable(disabled);
    }

    private void updateOperatorCurrentCustomer() {
        if (operatorCurrentToken == null) {
            nowServingLabel.setText("No customer is currently assigned to you.");
        } else {
            nowServingLabel.setText(operatorCurrentToken.tokenNumber() + " - "
                    + operatorCurrentToken.userName() + " (" + operatorCurrentToken.service().name() + ")");
        }
    }

    private void updateOperatorActionState() {
        if (callNextButton == null || operatorServicePicker == null) {
            return;
        }
        ServiceItem selectedService = operatorServicePicker.getValue();
        boolean hasCurrent = operatorCurrentToken != null;
        QueueStatus currentStatus = hasCurrent ? operatorCurrentToken.status() : null;
        QueueToken selectedToken = operatorTokenTable == null ? null
                : operatorTokenTable.getSelectionModel().getSelectedItem();
        boolean canOperate = currentAdmin != null && currentAdmin.active() && selectedService != null;
        operatorServicePicker.setDisable(hasCurrent);
        callNextButton.setDisable(!canOperate || hasCurrent);
        startServingButton.setDisable(!canOperate || !hasCurrent || currentStatus != QueueStatus.CALLED);
        completeTokenButton.setDisable(!canOperate || !hasCurrent
                || (currentStatus != QueueStatus.CALLED && currentStatus != QueueStatus.SERVING));
        skipTokenButton.setDisable(!canOperate || !hasCurrent || currentStatus != QueueStatus.CALLED);
        recallTokenButton.setDisable(!canOperate || hasCurrent || selectedToken == null
                || selectedToken.status() != QueueStatus.SKIPPED
                || (selectedToken.servedById() != null && selectedToken.servedById() != currentAdmin.id()));
    }

    private void joinQueue() {
        ServiceItem service = servicePicker.getValue();
        if (service == null) {
            messageLabel.setText("Choose a service before joining the queue.");
            return;
        }
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.length() < 2) {
            messageLabel.setText("Enter a name with at least two characters.");
            return;
        }
        int age;
        try {
            age = ageSpinner.getValueFactory().getValue();
        } catch (RuntimeException ex) {
            messageLabel.setText("Enter an age between 0 and 130.");
            return;
        }

        joinButton.setDisable(true);
        messageLabel.setText("Joining queue...");
        AppUser signedInCustomer = currentGuest;
        boolean useSignedInAccount = authenticatedCustomer;
        runAsync(() -> useSignedInAccount
                ? new GuestQueueEntry(signedInCustomer, queueService.join(signedInCustomer, service.id()))
                : queueService.joinGuest(name, age, service.id()), joined -> {
            currentGuest = joined.guest();
            currentToken = joined.token();
            nameField.setDisable(true);
            ageSpinner.setDisable(true);
            servicePicker.setDisable(true);
            joinButton.setDisable(true);
            newVisitorButton.setVisible(true);
            newVisitorButton.setManaged(true);
            renderToken(joined.token());
            messageLabel.setText("You have joined " + service.name() + ". Keep this token number for your visit.");
            loadKioskData();
        }, failure -> {
            joinButton.setDisable(false);
            messageLabel.setText(message(failure));
        });
    }

    private void cancelQueue() {
        if (currentGuest == null || currentToken == null) {
            return;
        }
        cancelButton.setDisable(true);
        runAsync(() -> queueService.cancel(currentGuest, currentToken.id()), token -> {
            currentToken = token;
            renderToken(token);
            messageLabel.setText("Token " + token.tokenNumber() + " has been cancelled.");
            loadKioskData();
        }, failure -> {
            cancelButton.setDisable(false);
            messageLabel.setText(message(failure));
        });
    }

    private void resetForNewVisitor() {
        if (!authenticatedCustomer) {
            currentGuest = null;
            nameField.clear();
            ageSpinner.getValueFactory().setValue(18);
        } else if (currentGuest != null) {
            nameField.setText(currentGuest.name());
            if (currentGuest.age() != null) {
                ageSpinner.getValueFactory().setValue(currentGuest.age());
            }
        }
        currentToken = null;
        servicePicker.getSelectionModel().clearSelection();
        nameField.setDisable(authenticatedCustomer);
        ageSpinner.setDisable(authenticatedCustomer);
        servicePicker.setDisable(false);
        joinButton.setDisable(false);
        newVisitorButton.setVisible(false);
        newVisitorButton.setManaged(false);
        messageLabel.setText(authenticatedCustomer ? "Choose a service to join another queue."
                : "Enter your details and select a service to join the queue.");
        tokenPanel.getChildren().setAll(sectionHeader("Your Queue Token"), emptyMessage("No active visit."));
    }

    private void loadKioskData() {
        runAsync(() -> new KioskData(serviceRepository.list(true), queueService.overview()), data -> {
            ServiceItem selected = servicePicker.getValue();
            servicePicker.setItems(FXCollections.observableArrayList(data.services()));
            if (selected != null) {
                data.services().stream().filter(item -> item.id() == selected.id())
                        .findFirst().ifPresent(servicePicker::setValue);
            }
            if (servicePicker.getValue() == null && !data.services().isEmpty() && currentToken == null) {
                servicePicker.setValue(data.services().getFirst());
            }
            queueTable.setItems(FXCollections.observableArrayList(data.queueStatus()));
            if (data.services().isEmpty()) {
                messageLabel.setText("No active services are available. Please ask the service operator for assistance.");
                joinButton.setDisable(true);
            } else if (currentGuest == null) {
                joinButton.setDisable(false);
            }
        }, failure -> messageLabel.setText(message(failure)));
    }

    private void refreshQueueStatus() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.submit(() -> {
            try {
                List<ServiceQueueSnapshot> status = queueService.overview();
                AppUser guest = currentGuest;
                QueueToken displayedToken = currentToken;
                Optional<QueueToken> token = Optional.empty();
                if (guest != null) {
                    token = queueService.activeToken(guest);
                    if (token.isEmpty() && displayedToken != null) {
                        token = queueService.history(guest).stream()
                                .filter(item -> item.id() == displayedToken.id()).findFirst();
                    }
                }
                Optional<QueueToken> refreshedToken = token;
                Platform.runLater(() -> {
                    if (queueTable != null) {
                        queueTable.setItems(FXCollections.observableArrayList(status));
                    }
                    if (guest != null && currentGuest != null && guest.id() == currentGuest.id()
                            && displayedToken != null && refreshedToken.isPresent()
                            && refreshedToken.get().id() == displayedToken.id()) {
                        currentToken = refreshedToken.get();
                        renderToken(currentToken);
                        if (authenticatedCustomer) {
                            loadCustomerHistory();
                        }
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> messageLabel.setText(message(ex)));
            } finally {
                refreshInProgress.set(false);
            }
        });
    }

    private void renderToken(QueueToken token) {
        tokenPanel.getChildren().clear();
        tokenPanel.getChildren().add(sectionHeader("Your Queue Token"));
        String ageText = currentGuest.age() == null ? "" : "  |  Age " + currentGuest.age();
        Label guest = new Label(currentGuest.name() + ageText);
        guest.getStyleClass().add("muted-copy");
        GridPane details = new GridPane();
        details.setHgap(12);
        details.setVgap(12);
        details.add(metric("Token", token.tokenNumber()), 0, 0);
        details.add(metric("Service", token.service().name()), 1, 0);
        details.add(metric("Status", token.status().name()), 2, 0);
        details.add(metric("People Ahead", Integer.toString(token.peopleAhead())), 0, 1);
        details.add(metric("Estimated Wait", token.estimatedWaitTime() + " min"), 1, 1);
        details.add(metric("Joined", formatTime(token.joinedAt())), 2, 1);
        if (token.counterNumber() != null && !token.counterNumber().isBlank()) {
            details.add(metric("Counter", token.counterNumber()), 0, 2);
        }
        tokenPanel.getChildren().addAll(guest, details);
        boolean cancellable = token.status() == QueueStatus.WAITING || token.status() == QueueStatus.CALLED;
        cancelButton.setDisable(!cancellable);
        tokenPanel.getChildren().add(cancelButton);
    }

    private TableView<ServiceQueueSnapshot> queueOverviewTable() {
        TableView<ServiceQueueSnapshot> table = new TableView<>();
        table.setPlaceholder(new Label("Service queue information will appear here."));
        table.setPrefHeight(230);
        TableColumn<ServiceQueueSnapshot, String> service = new TableColumn<>("Service");
        service.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().name()));
        TableColumn<ServiceQueueSnapshot, String> waiting = new TableColumn<>("Waiting");
        waiting.setCellValueFactory(cell -> new ReadOnlyStringWrapper(Integer.toString(cell.getValue().waitingCount())));
        TableColumn<ServiceQueueSnapshot, String> serving = new TableColumn<>("Now Serving");
        serving.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueOrDash(cell.getValue().currentlyServingToken())));
        TableColumn<ServiceQueueSnapshot, String> average = new TableColumn<>("Average Time");
        average.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().service().averageServiceTime() + " min"));
        table.getColumns().addAll(service, waiting, serving, average);
        service.setPrefWidth(300);
        waiting.setPrefWidth(140);
        serving.setPrefWidth(180);
        average.setPrefWidth(170);
        table.getStyleClass().add("dark-table");
        return table;
    }

    private VBox metric(String title, String value) {
        Label caption = new Label(title);
        caption.getStyleClass().add("metric-caption");
        Label content = new Label(value);
        content.getStyleClass().add("metric-value");
        VBox box = new VBox(7, caption, content);
        box.setPadding(new Insets(14));
        box.getStyleClass().add("glass-card");
        return box;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-copy");
        return label;
    }

    private Label sectionHeader(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private VBox headingBlock(String title, String description) {
        Label heading = new Label(title);
        heading.getStyleClass().add("dashboard-title");
        Label copy = new Label(description);
        copy.getStyleClass().add("muted-copy");
        VBox block = new VBox(6, heading, copy);
        block.setAlignment(Pos.CENTER);
        return block;
    }

    private Label emptyMessage(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted-copy");
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

    private void setPage(Node page) {
        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("dark-scroll-pane");
        root.setCenter(scrollPane);
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

    private String message(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "The request could not be completed." : current.getMessage();
    }

    private record KioskData(List<ServiceItem> services, List<ServiceQueueSnapshot> queueStatus) {
    }

    private record AdminData(List<AppUser> staff, List<ServiceItem> services, AnalyticsSummary analytics) {
    }

    private record OperatorData(List<ServiceItem> services, List<ServiceQueueSnapshot> queueStatus,
                                AppUser admin, Optional<QueueToken> current) {
    }

    private static final class ColumnConstraintsSupport {
        private static void configure(GridPane grid) {
            javafx.scene.layout.ColumnConstraints name = new javafx.scene.layout.ColumnConstraints();
            name.setPercentWidth(68);
            name.setHgrow(Priority.ALWAYS);
            javafx.scene.layout.ColumnConstraints age = new javafx.scene.layout.ColumnConstraints();
            age.setPercentWidth(32);
            grid.getColumnConstraints().setAll(name, age);
        }
    }
}