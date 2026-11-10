package com.smartqueue;

import com.smartqueue.controller.KioskController;
import com.smartqueue.database.DatabaseInitializer;
import com.smartqueue.database.DatabaseManager;
import com.smartqueue.seed.DemoDataSeeder;
import com.smartqueue.repository.UserRepository;
import com.smartqueue.service.AuthService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.sql.SQLException;

public final class Main extends Application {
    private KioskController controller;

    @Override
    public void start(Stage stage) throws IOException {
        DatabaseManager databaseManager = new DatabaseManager();
        try {
            new DatabaseInitializer(databaseManager).initialize();
            new DemoDataSeeder(databaseManager).seed();
            new AuthService(new UserRepository(databaseManager)).bootstrapAdministratorFromEnvironment();
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to initialize the Smart Queue database.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to seed application data or bootstrap the administrator account.", ex);
        }

        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/fxml/main.fxml"));
        Scene scene = new Scene(loader.load(), 1280, 820);
        controller = loader.getController();
        scene.getStylesheets().add(Main.class.getResource("/css/app.css").toExternalForm());
        stage.setTitle("SmartQueue | Queue Management");
        stage.setMinWidth(980);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}