package com.smartqueue.controller;

import com.smartqueue.database.DatabaseManager;
import com.smartqueue.model.AppUser;
import com.smartqueue.model.GuestQueueEntry;
import com.smartqueue.model.QueueAdvanceResult;
import com.smartqueue.model.QueueStatus;
import com.smartqueue.model.QueueToken;
import com.smartqueue.model.ServiceItem;
import com.smartqueue.model.ServiceQueueSnapshot;
import com.smartqueue.model.UserRole;
import com.smartqueue.repository.QueueRepository;
import com.smartqueue.repository.ServiceRepository;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.service.QueueService;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
    private ServiceRepository serviceRepository;
    private UserRepository userRepository;
    private TextField nameField;
    private Spinner<Integer> ageSpinner;
    private ComboBox<ServiceItem> servicePicker;
    private Button joinButton;
    private Button cancelButton;
    private Button newVisitorButton;
    private Button navigationButton;
    private Label messageLabel;
    private VBox tokenPanel;
    private TableView<ServiceQueueSnapshot> queueTable;
    private ComboBox<ServiceItem> operatorServicePicker;
    private Label operatorMessageLabel;
    private Label nowServingLabel;
    private Button advanceQueueButton;
    private AppUser currentGuest;
    private AppUser currentAdmin;
    private QueueToken currentToken;
    private QueueToken operatorCurrentToken;
    private ScheduledFuture<?> refreshTask;

    @FXML
    private void initialize() {
        databaseManager = new DatabaseManager();
        QueueRepository queueRepository = new QueueRepository(databaseManager);
        serviceRepository = new ServiceRepository(databaseManager);
        userRepository = new UserRepository(databaseManager);
        queueService = new QueueService(databaseManager, queueRepository, serviceRepository, userRepository);
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
        navigationButton = new Button("Operator Dashboard");
        navigationButton.getStyleClass().add("nav-button");
        navigationButton.setOnAction(event -> showOperatorDashboard());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(18, brand, spacer, navigationButton, mode);
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
        navigationButton.setText("Operator Dashboard");
        navigationButton.setOnAction(event -> showOperatorDashboard());
        nameField = new TextField();
        nameField.setPromptText("Full name");
        nameField.getStyleClass().add("glass-input");
        ageSpinner = new Spinner<>();
        ageSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 130, 18));
        ageSpinner.setEditable(false);
        ageSpinner.getStyleClass().add("glass-input");
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
        newVisitorButton = new Button("New Visitor");
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

        VBox page = new VBox(20,
                headingBlock("Welcome", "Join a service queue and follow your token status."),
                formPanel, tokenPanel, overviewPanel);
        page.setAlignment(Pos.TOP_CENTER);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page);
        loadKioskData();
        refreshTask = refreshExecutor.scheduleWithFixedDelay(this::refreshQueueStatus, 5, 5, TimeUnit.SECONDS);
    }

    private void showOperatorDashboard() {
        stopLiveRefresh();
        navigationButton.setText("Customer Kiosk");
        navigationButton.setOnAction(event -> showKiosk());
        operatorServicePicker = new ComboBox<>();
        operatorServicePicker.setPromptText("Select a service");
        operatorServicePicker.getStyleClass().add("glass-input");
        operatorServicePicker.setMaxWidth(Double.MAX_VALUE);
        operatorServicePicker.setOnAction(event -> updateOperatorActionState());
        nowServingLabel = new Label("Loading current service...");
        nowServingLabel.getStyleClass().add("metric-value");
        nowServingLabel.setWrapText(true);
        operatorMessageLabel = new Label("Loading the operator dashboard...");
        operatorMessageLabel.getStyleClass().add("dashboard-message");
        operatorMessageLabel.setWrapText(true);
        advanceQueueButton = new Button("Serve Next Customer");
        advanceQueueButton.getStyleClass().add("primary-button");
        advanceQueueButton.setDisable(true);
        advanceQueueButton.setOnAction(event -> advanceQueue());

        VBox controls = new VBox(14,
                sectionHeader("Queue Controls"),
                fieldLabel("Service"),
                operatorServicePicker,
                fieldLabel("Current Customer"),
                nowServingLabel,
                advanceQueueButton,
                operatorMessageLabel);
        controls.setPadding(new Insets(24));
        controls.setMaxWidth(620);
        controls.getStyleClass().add("glass-card");

        queueTable = queueOverviewTable();
        VBox overviewPanel = new VBox(14, sectionHeader("Current Queue Status"), queueTable);
        overviewPanel.setPadding(new Insets(24));
        overviewPanel.getStyleClass().add("glass-card");

        VBox page = new VBox(20,
                headingBlock("Queue Operator Dashboard",
                        "Complete the current service and automatically begin serving the next waiting customer."),
                controls, overviewPanel);
        page.setAlignment(Pos.TOP_CENTER);
        page.setPadding(new Insets(28, 36, 40, 36));
        setPage(page);
        loadOperatorData();
    }

    private void loadOperatorData() {
        runAsync(() -> {
            List<ServiceItem> services = serviceRepository.list(true);
            List<ServiceQueueSnapshot> status = queueService.overview();
            AppUser admin = userRepository.findFirstActiveAdmin()
                    .orElseGet(() -> new AppUser(0, "Application Admin", null,
                            UserRole.ADMIN, true));
            Optional<QueueToken> current = queueService.currentForStaff(admin);
            return new OperatorData(services, status, admin, current);
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
            } else if (currentAdmin.id() == 0) {
                operatorMessageLabel.setText("Using the application admin access for this local dashboard.");
            } else {
                operatorMessageLabel.setText("Using admin account: " + currentAdmin.name() + ".");
            }
            updateOperatorCurrentCustomer();
            updateOperatorActionState();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            advanceQueueButton.setDisable(true);
        });
    }

    private void advanceQueue() {
        ServiceItem service = operatorServicePicker.getValue();
        if (currentAdmin == null || service == null) {
            return;
        }
        advanceQueueButton.setDisable(true);
        operatorMessageLabel.setText("Updating the queue...");
        runAsync(() -> {
            QueueAdvanceResult result = queueService.advanceQueue(currentAdmin, service.id(), "Counter 1");
            return new OperatorAdvanceData(result, queueService.overview());
        }, data -> {
            operatorCurrentToken = data.result().nextToken().orElse(null);
            queueTable.setItems(FXCollections.observableArrayList(data.queueStatus()));
            if (data.result().nextToken().isPresent()) {
                QueueToken next = data.result().nextToken().get();
                String completed = data.result().completedToken()
                        .map(token -> "Completed " + token.tokenNumber() + ". ")
                        .orElse("");
                operatorMessageLabel.setText(completed + "Now serving " + next.tokenNumber()
                        + " - " + next.userName() + ".");
            } else if (data.result().completedToken().isPresent()) {
                operatorMessageLabel.setText("Service completed for "
                        + data.result().completedToken().get().tokenNumber() + ". No customers are waiting.");
            } else {
                operatorMessageLabel.setText("There are no customers waiting for this service.");
            }
            updateOperatorCurrentCustomer();
            updateOperatorActionState();
        }, failure -> {
            operatorMessageLabel.setText(message(failure));
            updateOperatorActionState();
        });
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
        if (advanceQueueButton == null) {
            return;
        }
        ServiceItem selected = operatorServicePicker.getValue();
        boolean activeOnAnotherService = operatorCurrentToken != null && selected != null
                && operatorCurrentToken.service().id() != selected.id();
        advanceQueueButton.setText(operatorCurrentToken == null
                ? "Serve Next Customer" : "Complete Service & Serve Next");
        advanceQueueButton.setDisable(currentAdmin == null || selected == null
                || activeOnAnotherService);
        if (activeOnAnotherService) {
            operatorMessageLabel.setText("Complete the active customer for "
                    + operatorCurrentToken.service().name() + " before selecting another service.");
        }
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
        runAsync(() -> queueService.joinGuest(name, age, service.id()), joined -> {
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
        currentGuest = null;
        currentToken = null;
        nameField.clear();
        ageSpinner.getValueFactory().setValue(18);
        servicePicker.getSelectionModel().clearSelection();
        nameField.setDisable(false);
        ageSpinner.setDisable(false);
        servicePicker.setDisable(false);
        joinButton.setDisable(false);
        newVisitorButton.setVisible(false);
        newVisitorButton.setManaged(false);
        messageLabel.setText("Enter your details and select a service to join the queue.");
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
            if (servicePicker.getValue() == null && !data.services().isEmpty() && currentGuest == null) {
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
                Optional<QueueToken> token = guest == null ? Optional.empty() : queueService.activeToken(guest);
                Platform.runLater(() -> {
                    queueTable.setItems(FXCollections.observableArrayList(status));
                    if (guest != null && currentGuest != null && guest.id() == currentGuest.id() && token.isPresent()) {
                        currentToken = token.get();
                        renderToken(currentToken);
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
        Label guest = new Label(currentGuest.name() + "  |  Age " + currentGuest.age());
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

    private record OperatorData(List<ServiceItem> services, List<ServiceQueueSnapshot> queueStatus,
                                AppUser admin, Optional<QueueToken> current) {
    }

    private record OperatorAdvanceData(QueueAdvanceResult result, List<ServiceQueueSnapshot> queueStatus) {
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