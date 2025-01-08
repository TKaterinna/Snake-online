package ru.nsu.snake.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import ru.nsu.snake.SnakesProto;
import ru.nsu.snake.Controller;
import ru.nsu.snake.model.GameField;
import ru.nsu.snake.model.GameLogic;
import ru.nsu.snake.model.Snake;
import ru.nsu.snake.Observer;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

public class GameUI extends Application implements Observer {
    private static double cellSize;
    private final long threshold = 3000;
    private BorderPane root;
    private GridPane gameGrid;
    private TableView<PlayerInfo> leaderboardTable;
    private TableView<ServerInfo> curGameInfo;
    private TableView<ServerInfo> serverList = new TableView<>();
    private final Map<String, Timer> serverTimers = new HashMap<>();
    private GameField gameField = null;
    private volatile boolean gameRunning = false;
    private Button createGameButton;
    private Button exitGameButton;
    private final Controller controller = new Controller(this);

    @Override
    public void start(Stage stage) {
        gameGrid = new GridPane();
        gameGrid.setPadding(new Insets(10));
        gameGrid.setAlignment(Pos.CENTER);

        root = new BorderPane();
        root.setPrefSize(1400, 1000);

        root.setStyle("-fx-background-color: #2e2e2e;");

        root.setCenter(gameGrid);
        VBox infoPanel = createInfoPanel();
        root.setRight(infoPanel);
        updateButtonsState();

        Scene scene = new Scene(root);
        scene.setOnKeyPressed(event -> handleKeyPress(event.getCode()));
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(event -> {
            gameExit();
            controller.close();

            for (Timer timer : serverTimers.values()) {
                timer.cancel();
            }
            Platform.exit();
        });

        controller.start();
    }
    private void clearGameGrid() {
        gameGrid.getChildren().clear();

        for (int i = 0; i < gameField.getWidth(); i++) {
            for (int j = 0; j < gameField.getHeight(); j++) {
                Rectangle cell = createSquare(i, j, Color.LIGHTGRAY);
                gameGrid.add(cell, i, j);
            }
        }
    }
    private VBox createInfoPanel() {
        VBox vbox = new VBox();
        vbox.setSpacing(20);
        vbox.setPadding(new Insets(20));
        vbox.setStyle("-fx-background-color: #3e3e3e; -fx-border-color: #4e4e4e; -fx-border-width: 2px;");

        Label lBoard = new Label("Leaderboard");
        lBoard.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        lBoard.setTextFill(Color.WHITE);
        leaderboardTable = new TableView<>();
        leaderboardTable.setPrefHeight(200);
        leaderboardTable.setStyle("-fx-background-color: #4e4e4e; -fx-text-fill: white;");
        TableColumn<PlayerInfo, String> placeColumn = new TableColumn<>("Place");
        TableColumn<PlayerInfo, String> playerNameColumn = new TableColumn<>("Player Name");
        TableColumn<PlayerInfo, String> scoreColumn = new TableColumn<>("Score");
        placeColumn.setCellValueFactory(new PropertyValueFactory<>("place"));
        playerNameColumn.setCellValueFactory(new PropertyValueFactory<>("playerName"));
        scoreColumn.setCellValueFactory(new PropertyValueFactory<>("score"));
        placeColumn.setStyle("-fx-text-fill: black;");
        playerNameColumn.setStyle("-fx-text-fill: black;");
        scoreColumn.setStyle("-fx-text-fill: black;");
        leaderboardTable.getColumns().addAll(placeColumn, playerNameColumn, scoreColumn);
        VBox leaderBox = new VBox(lBoard, leaderboardTable);

        Label cGame = new Label("Current game");
        cGame.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        cGame.setTextFill(Color.WHITE);
        curGameInfo = new TableView<>();
        curGameInfo.setPrefHeight(200);
        curGameInfo.setStyle("-fx-background-color: #4e4e4e; -fx-text-fill: white;");
        TableColumn<ServerInfo, String> gameNameColumn = new TableColumn<>("Game name");
        TableColumn<ServerInfo, String> areaSizeColumn = new TableColumn<>("Area size");
        TableColumn<ServerInfo, String> foodColumn = new TableColumn<>("Food");
        gameNameColumn.setCellValueFactory(new PropertyValueFactory<>("serverName"));
        areaSizeColumn.setCellValueFactory(new PropertyValueFactory<>("areaSize"));
        foodColumn.setCellValueFactory(new PropertyValueFactory<>("foodStatic"));
        gameNameColumn.setStyle("-fx-text-fill: black;");
        areaSizeColumn.setStyle("-fx-text-fill: black;");
        foodColumn.setStyle("-fx-text-fill: black;");
        curGameInfo.getColumns().addAll(gameNameColumn, areaSizeColumn, foodColumn);
        VBox curGameBox = new VBox(cGame, curGameInfo);

        HBox hBox1 = new HBox(leaderBox, curGameBox);
        hBox1.setSpacing(20);
        vbox.getChildren().add(hBox1);

        exitGameButton = new Button("Exit Game");
        exitGameButton.setPrefSize(200, 50);
        exitGameButton.setStyle("-fx-background-color: #5e5e5e; -fx-text-fill: white;");
        exitGameButton.setOnAction(event -> gameExit());

        createGameButton = new Button("Create Game");
        createGameButton.setPrefSize(200, 50);
        createGameButton.setStyle("-fx-background-color: #5e5e5e; -fx-text-fill: white;");
        createGameButton.setOnAction(event -> showCreateGameForm());

        HBox buttonBox = new HBox(exitGameButton, createGameButton);
        buttonBox.setSpacing(20);
        vbox.getChildren().add(buttonBox);

        Label serverListLabel = new Label("Available Games");
        serverListLabel.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        serverListLabel.setTextFill(Color.WHITE);
        serverList = new TableView<>();
        serverList.setPrefHeight(300);
        serverList.setStyle("-fx-background-color: #4e4e4e; -fx-text-fill: white;");
        TableColumn<ServerInfo, String> serverLeader = new TableColumn<>("Game name");
        TableColumn<ServerInfo, String> serverLeaderIP = new TableColumn<>("IP");
        TableColumn<ServerInfo, String> serverLeaderPort = new TableColumn<>("Port");
        TableColumn<ServerInfo, String> serverPlayersCount = new TableColumn<>("Players count");
        TableColumn<ServerInfo, String> serverAreaSize = new TableColumn<>("Area size");
        TableColumn<ServerInfo, String> serverFood = new TableColumn<>("Food");
        serverLeader.setCellValueFactory(new PropertyValueFactory<>("serverName"));
        serverLeaderIP.setCellValueFactory(new PropertyValueFactory<>("serverIP"));
        serverLeaderPort.setCellValueFactory(new PropertyValueFactory<>("serverPort"));
        serverPlayersCount.setCellValueFactory(new PropertyValueFactory<>("playersCount"));
        serverAreaSize.setCellValueFactory(new PropertyValueFactory<>("areaSize"));
        serverFood.setCellValueFactory(new PropertyValueFactory<>("foodStatic"));
        serverLeader.setStyle("-fx-text-fill: black;");
        serverLeaderIP.setStyle("-fx-text-fill: black;");
        serverLeaderPort.setStyle("-fx-text-fill: black;");
        serverPlayersCount.setStyle("-fx-text-fill: black;");
        serverAreaSize.setStyle("-fx-text-fill: black;");
        serverFood.setStyle("-fx-text-fill: black;");
        serverList.getColumns().addAll(serverLeader, serverLeaderIP, serverLeaderPort, serverPlayersCount, serverAreaSize, serverFood);

        serverList.setRowFactory(tv -> {
            TableRow<ServerInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) {
                    ServerInfo clickedRow = row.getItem();
                    if (!gameRunning) {
                        showJoinGameForm(clickedRow);
                    }
                }
            });
            return row;
        });

        vbox.getChildren().addAll(serverListLabel, serverList);
        return vbox;
    }
    private void showJoinGameForm(ServerInfo serverInfo) {
        TextInputDialog form = new TextInputDialog();
        form.setTitle("Join the game");
        form.setContentText("Your name:");

        Optional<String> result = form.showAndWait();
        result.ifPresent(name -> {
            generateGameField(serverInfo);
            try {
                controller.startClient(name, serverInfo);

                gameRunning = true;
                updateButtonsState();
                updateCurGameInfo(serverInfo);
            } catch (IOException e) {
                System.err.println("Start client error");
                e.printStackTrace();
            }
        });
    }
    private void generateGameField(ServerInfo serverInfo) {
        String[] numbers = serverInfo.areaSizeProperty().get().split("[^0-9]+");
        int width = Integer.parseInt(numbers[0]);
        int height = Integer.parseInt(numbers[1]);

        double availableWidth = Screen.getPrimary().getVisualBounds().getWidth() * 0.7;
        double availableHeight = Screen.getPrimary().getVisualBounds().getHeight() * 0.7;

        double cellWidth = availableWidth / width;
        double cellHeight = availableHeight / height;

        cellSize = Math.min(cellWidth, cellHeight);

        gameField = new GameField(serverInfo);
    }
    private void gameExit() {
        gameRunning = false;

        leaderboardTable.getItems().clear();
        curGameInfo.getItems().clear();
        if (gameField != null) clearGameGrid();

        controller.stopServer();

        updateButtonsState();
    }
    private void showCreateGameForm() {
        Stage createGameStage = new Stage();
        createGameStage.setTitle("Create Game");

        Label playerNameLabel = new Label("Your name:");
        TextField playerNameTextField = new TextField();

        Label gemaNameLabel = new Label("Game name:");
        TextField gameNameTextField = new TextField();

        Label widthLabel = new Label("Width (10 - 100):");
        TextField widthTextField = new TextField("30");
        Label heightLabel = new Label("Height (10 - 100):");
        TextField heightTextField = new TextField("30");

        Label foodStaticLabel = new Label("Food static:");
        TextField foodStaticTextField = new TextField("4");

        Label gameSpeedLabel = new Label("Game Speed (300 - 5000):");
        TextField gameSpeedTextField = new TextField("1000");

        Button startButton = new Button("Start");
        startButton.setOnAction(event -> {
            boolean dataIsValid = true;
            String playerName = "playerName", gameName = "gameName";
            int fieldWidth = 0, fieldHeight = 0, foodStatic = 0, gameSpeed = 0;
            try {
                playerName = playerNameTextField.getText();
                if (playerName.isEmpty()) throw new NumberFormatException("playerName");
                gameName = gameNameTextField.getText();
                if (gameName.isEmpty()) throw new NumberFormatException("gameName");
                for (ServerInfo server : serverList.getItems()) {
                    if (server.serverNameProperty().get().equals(gameName)) {
                        throw new NumberFormatException("gameName");
                    }
                }
                fieldWidth = Integer.parseInt(widthTextField.getText());
                if (fieldWidth < 10 || fieldWidth > 100) throw new NumberFormatException("width");
                fieldHeight = Integer.parseInt(heightTextField.getText());
                if (fieldHeight < 10 || fieldHeight > 100) throw new NumberFormatException("height");
                foodStatic = Integer.parseInt(foodStaticTextField.getText());
                if (foodStatic > fieldWidth * fieldHeight) throw new NumberFormatException("food");
                gameSpeed = Integer.parseInt(gameSpeedTextField.getText());
                if (gameSpeed < 100 || gameSpeed > 5000) throw new NumberFormatException("speed");
            } catch (NumberFormatException e) {
                switch (e.getMessage()) {
                    case "playerName" -> playerNameTextField.clear();
                    case "gameName" -> gameNameTextField.clear();
                    case "width" -> widthTextField.clear();
                    case "height" -> heightTextField.clear();
                    case "food" -> foodStaticTextField.clear();
                    case "speed" -> gameSpeedTextField.clear();
                }

                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText(null);
                alert.setContentText("Fix input data:\n" + e.getMessage());
                alert.showAndWait();

                dataIsValid = false;
            }

            if (dataIsValid) {
                try {
                    ServerInfo serverInfo = new ServerInfo(gameName,
                            0,
                            widthTextField.getText() + "x" + heightTextField.getText(),
                            foodStatic,
                            gameSpeed,
                            Controller.getAddress("eth0"),
                            0
                    );

                    generateGameField(serverInfo);

                    controller.startServer(playerName, serverInfo);

                    gameRunning = true;
                    updateButtonsState();
                    updateCurGameInfo(serverInfo);
                } catch (IOException e) {
                    System.err.println("Server create error...");
                    throw new RuntimeException(e);
                }
                createGameStage.close();
            }
        });

        VBox layout = new VBox();
        layout.setSpacing(10);
        layout.setPadding(new Insets(10));
        layout.getChildren().addAll(playerNameLabel, playerNameTextField, gemaNameLabel, gameNameTextField, widthLabel,
                widthTextField, heightLabel, heightTextField,
                foodStaticLabel, foodStaticTextField, gameSpeedLabel, gameSpeedTextField, startButton);

        Scene scene = new Scene(layout);
        createGameStage.setScene(scene);
        createGameStage.show();
    }
    private void handleKeyPress(KeyCode code) {
        if (gameRunning) {
            try {
                controller.sendSteerMsg(code);
            } catch (IOException e) {
                System.err.println("Steer send error!");
                e.printStackTrace();
            }
        }
    }
    private void render() {
        if (gameField == null) return;
        Platform.runLater(this::clearGameGrid);

        for (Snake snake : gameField.getSnakes()) {
            int counter = 0;
            for (SnakesProto.GameState.Coord p : snake.getBody()) {
                Color curColor;
                if (counter == 0) {
                    curColor = snake.getColor().darker();
                } else {
                    curColor = snake.getColor();
                }
                Rectangle rect = createSquare(p.getX(), p.getY(), curColor);
                Platform.runLater(() -> gameGrid.add(rect, p.getX(), p.getY()));
                counter++;
            }
        }

        for (SnakesProto.GameState.Coord food : gameField.getFoods()) {
            Rectangle rect = createSquare(food.getX(), food.getY(), Color.RED);
            Platform.runLater(() -> gameGrid.add(rect, food.getX(), food.getY()));
        }
    }
    private Rectangle createSquare(int x, int y, Color color) {
        Rectangle rect = new Rectangle(x * cellSize, y * cellSize, cellSize, cellSize);
        rect.setFill(color);
        return rect;
    }

    public static void main(String[] args) {
        launch(args);
    }
    @Override
    public void update(Object message, InetAddress address, int port) {
        if (message instanceof SnakesProto.GameMessage.StateMsg) {
            SnakesProto.GameMessage.StateMsg stateMsg = (SnakesProto.GameMessage.StateMsg) message;
            GameLogic.editGameFieldFromState(gameField, stateMsg);
            render();

            SnakesProto.GamePlayers gamePlayers = stateMsg.getState().getPlayers();
            updateLeaderboardTable(gamePlayers);
        } else if (message instanceof SnakesProto.GameMessage.AnnouncementMsg) {
            SnakesProto.GameMessage.AnnouncementMsg announcementMsg = (SnakesProto.GameMessage.AnnouncementMsg) message;
            updateServerList(announcementMsg, address, port);
        }
    }
    private void updateLeaderboardTable(SnakesProto.GamePlayers gamePlayers) {
        leaderboardTable.getItems().clear();

        for (SnakesProto.GamePlayer player : gamePlayers.getPlayersList()) {
            PlayerInfo playerInfo = new PlayerInfo(
                    getPlace(gamePlayers, player),
                    player.getName(),
                    player.getScore()

            );
            leaderboardTable.getItems().add(playerInfo);
        }
        leaderboardTable.refresh();
    }
    private String getPlace(SnakesProto.GamePlayers gamePlayers, SnakesProto.GamePlayer currentPlayer) {
        int currentPlayerScore = currentPlayer.getScore();
        int place = 1;

        for (SnakesProto.GamePlayer player : gamePlayers.getPlayersList()) {
            if (player.getScore() > currentPlayerScore) {
                place++;
            }
        }

        return Integer.toString(place);
    }
    private void updateServerList(SnakesProto.GameMessage.AnnouncementMsg announcementMsg, InetAddress address, int port) {
        for (SnakesProto.GameAnnouncement gameAnnouncement : announcementMsg.getGamesList()) {
            String gameName = gameAnnouncement.getGameName();
            boolean exists = false;

            String masterIp = null;
            int masterPort = -1;
            for (SnakesProto.GamePlayer player : gameAnnouncement.getPlayers().getPlayersList()) {
                if (player.getRole() == SnakesProto.NodeRole.MASTER) {
                    masterIp = player.getIpAddress();
                    masterPort = player.getPort();
                    break;
                }
            }

            if (masterIp == null || masterPort == -1) {
                masterIp = address.getHostAddress();
                masterPort = port;
            }

            for (ServerInfo server : serverList.getItems()) {
                if (server.serverNameProperty().get().equals(gameName)) {
                    exists = true;
                    updateServerInfo(server, gameAnnouncement, masterIp, masterPort);
                    resetTimerForServer(gameName);
                    break;
                }
            }

            if (!exists) {
                ServerInfo newServer = new ServerInfo(
                        gameName,
                        gameAnnouncement.getPlayers().getPlayersCount(),
                        String.format("%dx%d", gameAnnouncement.getConfig().getWidth(), gameAnnouncement.getConfig().getHeight()),
                        gameAnnouncement.getConfig().getFoodStatic(),
                        gameAnnouncement.getConfig().getStateDelayMs(),
                        masterIp,
                        masterPort
                );
                serverList.getItems().add(newServer);
                startTimerForServer(gameName);
            }
        }
    }
    private void updateServerInfo(ServerInfo server, SnakesProto.GameAnnouncement gameAnnouncement, String masterIp, int masterPort) {
        Platform.runLater(() -> {
            server.serverNameProperty().set(gameAnnouncement.getGameName());
            server.playersCountProperty().set((gameAnnouncement.getPlayers().getPlayersCount()));
            server.areaSizeProperty().set(String.format("%dx%d", gameAnnouncement.getConfig().getWidth(), gameAnnouncement.getConfig().getHeight()));
            server.foodStaticProperty().set(gameAnnouncement.getConfig().getFoodStatic());
            server.serverIPProperty().set(masterIp);
            server.serverPortProperty().set(masterPort);
        });
    }
    private void resetTimerForServer(String gameName) {
        Timer existingTimer = serverTimers.get(gameName);
        if (existingTimer != null) {
            existingTimer.cancel();
        }
        startTimerForServer(gameName);
    }
    private void startTimerForServer(String gameName) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    serverList.getItems().removeIf(server -> server.serverNameProperty().get().equals(gameName));
                });
            }
        }, threshold);
        serverTimers.put(gameName, timer);
    }
    private void updateCurGameInfo(ServerInfo serverInfo) {
        Platform.runLater(() -> {
            curGameInfo.getItems().clear();
            curGameInfo.getItems().add(serverInfo);
        });
    }
    private void updateButtonsState() {
        createGameButton.setDisable(gameRunning);
        exitGameButton.setDisable(!gameRunning);
    }
}