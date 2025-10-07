//WORK IN PROGRESS - ATTEMPTING TO INCORPORATE A MAP OVERLAY AS THE GAME BOARD
package main;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.net.URL;

public class SuburbanScrambleMAP extends Application {

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        WebEngine webEngine = webView.getEngine();

        // Enable console logging
        webEngine.setOnAlert(event -> System.out.println("JS Alert: " + event.getData()));
        webEngine.getLoadWorker().exceptionProperty().addListener((obs, old, newExc) -> {
            if (newExc != null) {
                System.err.println("WebView Error:");
                newExc.printStackTrace();
            }
        });

        // Load HTML file properly
        URL htmlUrl = getClass().getResource("/html/map.html");
        if (htmlUrl == null) {
            throw new RuntimeException("Cannot find HTML file in resources");
        }
        webEngine.load(htmlUrl.toExternalForm());

        stage.setScene(new Scene(webView, 800, 600));
        stage.setTitle("Suburban Scramble Map");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}