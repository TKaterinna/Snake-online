package ru.nsu.snake.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ServerInfo {
    private final StringProperty serverName;
    private final IntegerProperty playersCount;
    private final StringProperty areaSize;
    private final IntegerProperty foodStatic;
    private final StringProperty serverIP;
    private IntegerProperty serverPort;
    private final IntegerProperty stateDelayMs;

    public ServerInfo(String serverName, int playersCount, String areaSize, int foodStatic, int gameSpeed, String serverIP, int serverPort) {
        this.serverName = new SimpleStringProperty(serverName);
        this.playersCount = new SimpleIntegerProperty(playersCount);
        this.areaSize = new SimpleStringProperty(areaSize);
        this.foodStatic = new SimpleIntegerProperty(foodStatic);
        this.stateDelayMs = new SimpleIntegerProperty(gameSpeed);
        this.serverIP = new SimpleStringProperty(serverIP);
        this.serverPort = new SimpleIntegerProperty(serverPort);
    }

    public StringProperty serverIPProperty() { return serverIP; }
    public IntegerProperty serverPortProperty() { return serverPort; }
    public StringProperty serverNameProperty() {
        return serverName;
    }
    public IntegerProperty playersCountProperty() {
        return playersCount;
    }
    public StringProperty areaSizeProperty() {
        return areaSize;
    }
    public IntegerProperty foodStaticProperty() {
        return foodStatic;
    }
    public IntegerProperty stateDelayMsProperty() { return stateDelayMs; }
    public void setServerPort(int port) { serverPort = new SimpleIntegerProperty(port); }
}

