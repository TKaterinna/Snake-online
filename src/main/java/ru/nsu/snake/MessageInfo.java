package ru.nsu.snake;

import java.net.InetAddress;

public class MessageInfo {
    private final SnakesProto.GameMessage message;
    private InetAddress address;
    private int port;
    private final long timestamp;
    private int attemptCount = 0;
    private boolean toMaster = false;

    MessageInfo(SnakesProto.GameMessage message, InetAddress address, int port, long timestamp) {
        this.message = message;
        this.address = address;
        this.port = port;
        this.timestamp = timestamp;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAddress(InetAddress address) {
        this.address = address;
    }

    public boolean isToMaster() {
        return toMaster;
    }

    public int getAttemptCount() {
        return attemptCount;
    }
    public void setAttemptCount(int count) {
        attemptCount = count;
    }

    public SnakesProto.GameMessage getMessage() {
        return message;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
