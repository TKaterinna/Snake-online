package ru.nsu.snake;

import ru.nsu.snake.model.GameField;
import ru.nsu.snake.model.GameLogic;
import ru.nsu.snake.model.Snake;
import ru.nsu.snake.ui.ServerInfo;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SnakeNet {
    public static final String MULTICAST_ADDRESS = "239.192.0.4";
    public static final int MULTICAST_PORT = 9192;
//    private static final int MAX_ATTEMPTS = 100;
    private final Observer observer;
    private final ConcurrentHashMap<Integer, Long> lastMsgSeqReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, MessageInfo>> sentMessages = new ConcurrentHashMap<>();
    private final long announcementDelayMS = 1000;
    private final long pingDelayMS = 100;
    private final long resendTime;
    private final long waitTime;
    private final Object lockGameField = new Object();
    private GameField gameField;
    private volatile SnakesProto.GameMessage.StateMsg lastStateMsg;
    private volatile int playerId = -1;
    private volatile int deputyId = -1;
    private volatile int masterId = -1;
    private volatile InetAddress serverAddress;
    private volatile int serverPort;
    private final AtomicLong msgSeq = new AtomicLong(0);
    private int stateOrder = 0;
    private final Object lockSnakeGame = new Object();
    private GameLogic snakeGame = null;
    private final ConcurrentHashMap<Integer, SnakesProto.GamePlayer> players = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetSocketAddress, Integer> addressToPlayerId = new ConcurrentHashMap<>();
    private volatile int currentMaxId = 0;
    private final int maxPlayerCount = 5;
    private DatagramSocket socket;
    private final MulticastSocket multicastSocket;
//    private final Object lockServerInfo = new Object();
    private final ServerInfo serverInfo;
    private Thread messageReceiverLoop;
    private Thread announcementSendThread;
    private Thread gameLoop;
    private Thread messageResenderThread;
    private Thread pingSender;
    private volatile SnakesProto.NodeRole nodeRole;

    public SnakeNet(ServerInfo serverInfo, Observer observer) throws IOException {
//        synchronized (lockServerInfo) {
            this.serverInfo = serverInfo;
//        }
        this.observer = observer;

//        synchronized (lockServerInfo) {
            this.resendTime = serverInfo.stateDelayMsProperty().get() / 10;
            this.waitTime = resendTime * 8;
            synchronized (lockGameField) {
                this.gameField = new GameField(serverInfo);
            }
//        }

        String serverAddress;
//        synchronized (lockServerInfo) {
            serverAddress = serverInfo.serverIPProperty().get();
//        }
        System.out.println("[SnakeNet] Address: " + serverAddress);

        multicastSocket = new MulticastSocket(MULTICAST_PORT);
        NetworkInterface networkInterface = Controller.findNetworkInterface("eth0");
        if (networkInterface == null) {
            System.err.println("Failed to find a network interface for multicast");
            return;
        }
        multicastSocket.setNetworkInterface(networkInterface);

        messageReceiverLoop = new Thread(this::receiveMessageLoop);
        messageResenderThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    long currentTime = System.currentTimeMillis();
                    for (ConcurrentHashMap<Long, MessageInfo> playerMessages : sentMessages.values()) {
//                        List<MessageInfo> messagesToRemove = new ArrayList<>();

                        for (MessageInfo message : playerMessages.values()) {
//                            if (message.isNeedToDelete()) {
//                                messagesToRemove.add(message);
//                                continue;
//                            }
                            System.out.println("Resender: curr time = " + currentTime + "   message time = " + message.getTimestamp());
                            boolean shouldResend = (currentTime - message.getTimestamp()) > resendTime;
                            if (shouldResend) {
                                message.setAttemptCount(message.getAttemptCount() + 1);
                                if (currentTime - message.getTimestamp() > waitTime) {
//                                if (message.getAttemptCount() >= MAX_ATTEMPTS) {
                                    System.err.println("Player " + message.getMessage().getReceiverId() + " doesn't answer...");
                                    handlePlayerDisconnection(message.getMessage().getReceiverId());
                                    break;
                                }
                                if (message.isToMaster()) {
                                    message.setPort(this.serverPort);
                                    message.setAddress(this.serverAddress);
                                }
                                sendGameMessage(message.getMessage(), message.getAddress(), message.getPort());
                            }
                        }

//                        for (MessageInfo message : messagesToRemove) {
//                            System.out.println("remove message");
//                            playerMessages.remove(message.getMessage().getMsgSeq());
//                        }
                    }

                    Thread.sleep(resendTime);
                } catch (InterruptedException e) {
                    System.err.println("Resend message error!");
                    break;
                }
            }
        });

        messageReceiverLoop.start();
        messageResenderThread.start();
    }

    public void startAsClient(String playerName, InetAddress serverAddress, int serverPort) throws IOException {
        this.nodeRole = SnakesProto.NodeRole.NORMAL;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.socket = new DatagramSocket();

        sendJoinRequest(playerName, serverAddress, serverPort);

        pingSender = new Thread(() -> {
            while (!pingSender.isInterrupted()) {
                try {
                    Thread.sleep(pingDelayMS);
                    if (masterId == -1) continue;
                    if (sentMessages.get(masterId).isEmpty()) {
                        SnakesProto.GameMessage pingMsg = createPingMessage();
                        sendGameMessage(pingMsg, serverAddress, serverPort);
                    }
                } catch (InterruptedException e) {
                    System.err.println("Ping sender error...");
                    break;
                } catch (Exception e) {
                    //ignore because no element in map
                }
            }
        });
        pingSender.start();
    }
    public void startAsServer(String playerName, InetAddress address, int port) throws IOException {
        this.nodeRole = SnakesProto.NodeRole.MASTER;
        synchronized (lockGameField) {
            synchronized (lockSnakeGame) {
                this.snakeGame = new GameLogic(gameField, lockGameField);
            }
        }
        this.serverAddress = address;
        this.serverPort = port;
        this.socket = new DatagramSocket(serverPort, serverAddress);
        serverPort = socket.getLocalPort();
//        synchronized (lockServerInfo) {
            serverInfo.setServerPort(serverPort);
//        }

        int playerId = addNewPlayer(playerName, address, serverPort, SnakesProto.NodeRole.MASTER);
        this.playerId = playerId;
        masterId = playerId;
        try {
            ArrayList<SnakesProto.GameState.Coord> initialPosition = new ArrayList<>();
            SnakesProto.Direction headDirection;
            synchronized (lockSnakeGame) {
                headDirection = snakeGame.getGameField().findValidSnakePosition(initialPosition);
            }
            System.out.println("NET headDirection = " + headDirection.name());
            Snake newSnake = new Snake(initialPosition, playerId);

            newSnake.setHeadDirection(headDirection);
            newSnake.setNextDirection(headDirection);

            synchronized (lockSnakeGame) {
                snakeGame.addSnake(newSnake);
                System.out.println("Add new snake, position " + initialPosition.get(0).getX() + " " + initialPosition.get(0).getY() + " snakes count " + snakeGame.getGameField().getSnakes().size());
            }

            startServerThreads();
        } catch (Exception e) {
            System.err.println("[Server] Player " + playerName + " cannot join the game");
            sendError("Cannot join the game: no space", address, port);
        }
    }
    private void startServerThreads() {
        if (pingSender != null) pingSender.interrupt();

        announcementSendThread = new Thread(() -> {
            while (!announcementSendThread.isInterrupted()) {
                try {
                    SnakesProto.GameMessage announcement = createAnnouncementMessage();
                    sendGameMessage(announcement, InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT);
                    Thread.sleep(announcementDelayMS);
                } catch (InterruptedException | IOException e) {
                    System.err.println("[Server] Announcement send error!");
                    break;
                }
            }
        });

        gameLoop = new Thread(() -> {
            while (!gameLoop.isInterrupted()) {
                try {
                    int delay;
                    synchronized (lockSnakeGame) {
                        delay = snakeGame.getGameField().getDelayMS();
                    }
                        Thread.sleep(delay);
                    synchronized (lockSnakeGame) {
                        snakeGame.update();
                    }
                    updatePlayersScore();
                    sendStateForAll();
                } catch (InterruptedException | IOException e) {
                    System.err.println("[Server] Game loop destroyed...");
                    break;
                }
            }
        });

        announcementSendThread.start();
        gameLoop.start();
    }
    private SnakesProto.GameMessage createAnnouncementMessage() {
        GameField field;
        synchronized (lockSnakeGame) {
            field = snakeGame.getGameField();
        }
        String serverName;
//        synchronized (lockServerInfo) {
            serverName = serverInfo.serverNameProperty().get();
//        }
        return SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setAnnouncement(SnakesProto.GameMessage.AnnouncementMsg.newBuilder()
                        .addGames(SnakesProto.GameAnnouncement.newBuilder()
                                .setPlayers(SnakesProto.GamePlayers.newBuilder()
                                        .addAllPlayers(players.values())
                                        .build())
                                .setConfig(SnakesProto.GameConfig.newBuilder()
                                        .setWidth(field.getWidth())
                                        .setHeight(field.getHeight())
                                        .setFoodStatic(field.getFoodStatic())
                                        .setStateDelayMs(field.getDelayMS())
                                        .build())
                                .setCanJoin(players.size() < maxPlayerCount)
                                .setGameName(serverName)
                                .build())
                        .build())
                .build();
    }
    private SnakesProto.GameMessage createPingMessage() {
        return SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setPing(SnakesProto.GameMessage.PingMsg.newBuilder().build())
                .build();
    }
    public void updatePlayersScore() {
        List<Snake> snakes;
        synchronized (lockSnakeGame) {
            snakes = snakeGame.getGameField().getSnakes();
        }
        for (Snake snake : snakes) {
            int playerId = snake.getPlayerID();
            SnakesProto.GamePlayer player = players.get(playerId);
            if (player != null) {
                SnakesProto.GamePlayer updatedPlayer = SnakesProto.GamePlayer.newBuilder()
                        .mergeFrom(player)
                        .setScore(snake.getScore())
                        .build();
                players.put(playerId, updatedPlayer);
            }
        }
    }
    private void removePlayer(int playerId) {
        if (!players.containsKey(playerId)) {
            System.err.println("Player ID " + playerId + " not found in the current players list.");
            return;
        }

        players.remove(playerId);
        addressToPlayerId.values().removeIf(id -> id == playerId);

//        if (sentMessages.get(playerId) != null) {
//            for (MessageInfo messageInfo : sentMessages.get(playerId).values()) {
//                messageInfo.setNeedToDelete();
//            }
//        }

        sentMessages.get(playerId).clear();

        System.out.println("Player " + playerId + " successfully removed!");
//        System.out.println("Attempting to remove player with ID: " + playerId);
//        if (!players.containsKey(playerId)) {
//            System.err.println("Player ID " + playerId + " not found in the current players list.");
//            return;
//        }
//
//        if (playerId == deputyId) { deputyId = -1; }
//
//        players.remove(playerId);
//        addressToPlayerId.values().removeIf(id -> id == playerId);
//        sentMessages.get(playerId).clear();
//        System.out.println("Player " + playerId + " successfully removed!");
    }
    private void receiveMessageLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            if (socket == null) continue;
            try {
                byte[] buffer = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getLength());

                InetAddress address = packet.getAddress();
                int port = packet.getPort();

                SnakesProto.GameMessage message = SnakesProto.GameMessage.parseFrom(data);
                if (players.get(message.getSenderId()) != null) {
                    System.out.println("listened " + message.getTypeCase() + "(rec: " + message.getReceiverId() + ", sen: " + message.getSenderId() + ", seq: " + message.getMsgSeq() + ") from " + address + ":" + port + "(" + players.get(message.getSenderId()).getRole() + ")");
                } else {
                    System.out.println("listened " + message.getTypeCase() + "(rec: " + message.getReceiverId() + ", sen: " + message.getSenderId() + ", seq: " + message.getMsgSeq() + ") from " + address + ":" + port);
                }
                System.out.println("Listen: curr time = " + System.currentTimeMillis());

                if (message.getTypeCase() != SnakesProto.GameMessage.TypeCase.ANNOUNCEMENT && message.getTypeCase() != SnakesProto.GameMessage.TypeCase.ACK && message.getTypeCase() != SnakesProto.GameMessage.TypeCase.JOIN) {
                    sendAck(message.getMsgSeq(), address, port);
                } else if (message.getTypeCase() == SnakesProto.GameMessage.TypeCase.ACK) {
                    handleAck(message, address, port);
                    continue;
                }

                int playerId = message.getSenderId();
                long playerMsgSeq = message.getMsgSeq();

                if (playerMsgSeq > lastMsgSeqReceived.getOrDefault(playerId, -1L)) {
                    if (playerId != -1) lastMsgSeqReceived.put(playerId, playerMsgSeq);
                    switch (message.getTypeCase()) {
                        case PING  -> handlePing(message, address, port);
                        case STEER -> handleSteer(message, address, port);
                        case JOIN  -> handleJoin(message, address, port);
                        case ANNOUNCEMENT -> handleAnnouncement(message, address, port);
                        case STATE -> handleState(message, address, port);
                        case ACK   -> handleAck(message, address, port);
                        case ERROR -> handleError(message, address, port);
                        case ROLE_CHANGE -> handleRoleChange(message, address, port);
                        default    -> {
                            System.err.println("Unknown message type (" + message.getTypeCase() + ") from " + address.toString() + port);
                            sendError("Unknown message type", address, port);
                            return;
                        }
                    }
                }

                System.out.println("AAAAAAAAAAAAAAAAAAAAAAAAAAA " + nodeRole + deputyId);
                if (nodeRole == SnakesProto.NodeRole.MASTER && deputyId == -1) {
                    System.out.println("FIND AAAAAAAAAAAAAAAAAAAAAAAAAAA");
                    selectNewDeputy();
                }
            } catch (IOException e) {
                System.err.println("Message receive error: " + e.getMessage());
                break;
            }
        }
    }
    public void sendJoinRequest(String playerName, InetAddress address, int port) {
        String serverName;
//        synchronized (lockServerInfo) {
            serverName = serverInfo.serverNameProperty().get();
//        }
        SnakesProto.GameMessage joinMessage = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setJoin(SnakesProto.GameMessage.JoinMsg.newBuilder()
                        .setPlayerName(playerName)
                        .setGameName(serverName)
                        .setRequestedRole(nodeRole)
                        .build())
                .build();

        sendGameMessage(joinMessage, address, port);
    }
    private void handlePing(SnakesProto.GameMessage message, InetAddress address, int port) {
        // Обработка Ping сообщения, например, обновление времени последнего активного взаимодействия с этим клиентом
    }
    private void handleSteer(SnakesProto.GameMessage message, InetAddress address, int port) {
        SnakesProto.GameMessage.SteerMsg steer = message.getSteer();
        int playerId = getPlayerIdByAddress(address, port);

        if (playerId != -1) {
            SnakesProto.Direction direction = steer.getDirection();
            synchronized (lockSnakeGame) {
                snakeGame.updateDirection(playerId, direction);
            }
        } else {
            System.err.println("[Server] Unknown player from " + address + ":" + port);
        }
    }
    private void handleJoin(SnakesProto.GameMessage message, InetAddress address, int port) throws IOException {
        SnakesProto.GameMessage.JoinMsg join = message.getJoin();
        boolean canJoin = players.size() < maxPlayerCount;
        try {
            if (canJoin) {
                int playerId = addNewPlayer(join.getPlayerName(), address, port, join.getRequestedRole());
                System.err.println("Player(" + playerId + ") can join to the game!");

                ArrayList<SnakesProto.GameState.Coord> initialPosition = new ArrayList<>();
                SnakesProto.Direction headDirection;
//                GameField
                synchronized (lockSnakeGame) {
                    headDirection = snakeGame.getGameField().findValidSnakePosition(initialPosition);
                }
                System.out.println("NET headDirection = " + headDirection.name());
                Snake newSnake = new Snake(initialPosition, playerId);
                newSnake.setHeadDirection(headDirection);
                newSnake.setNextDirection(headDirection);

                synchronized (lockSnakeGame) {
                    snakeGame.addSnake(newSnake);
                }
                System.out.println("Add new client snake, position " + initialPosition.get(0).getX() + " " + initialPosition.get(0).getY());
                sendAck(message.getMsgSeq(), address, port);
            } else {
                System.err.println("[Server] Player " + join.getPlayerName() + " cannot join the game");
                sendError("Cannot join the game: no space", address, port);
            }
        } catch (Exception e) {
            System.err.println("[Server] Player " + join.getPlayerName() + " cannot join the game");
            sendError("Cannot join the game: no space", address, port);
        }
    }
    public void sendSteer(SnakesProto.Direction direction) {
        SnakesProto.GameMessage steerMessage = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setSteer(SnakesProto.GameMessage.SteerMsg.newBuilder()
                        .setDirection(direction)
                        .build())
                .build();

        System.out.println("sendSteer");
        sendGameMessage(steerMessage, serverAddress, serverPort);
    }
    public void sendStateForAll() throws IOException {
        SnakesProto.GameMessage stateMessage = createStateMessage();
        for (SnakesProto.GamePlayer player : new ArrayList<>(players.values())) {
            sendGameMessage(stateMessage, InetAddress.getByName(player.getIpAddress()), player.getPort());
        }
    }
    private SnakesProto.GameMessage createStateMessage() {
        SnakesProto.GameState.Builder gameStateBuilder = SnakesProto.GameState.newBuilder()
                .setStateOrder(++stateOrder);

        synchronized (lockSnakeGame) {
            gameStateBuilder.addAllFoods(snakeGame.getGameField().getFoods());
        }

//        System.out.println("send state snakes " + snakeGame.getGameField().getSnakes().size());
        List<Snake> snakes;
        int width;
        int height;
        synchronized (lockSnakeGame) {
            snakes = snakeGame.getGameField().getSnakes();
            width = snakeGame.getGameField().getWidth();
            height = snakeGame.getGameField().getHeight();
        }
        for (Snake snake : snakes) {
            gameStateBuilder.addSnakes(Snake.generateSnakeProto(snake, height, width));
        }

        gameStateBuilder.setPlayers(SnakesProto.GamePlayers.newBuilder().addAllPlayers(players.values()).build());

        return SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setState(SnakesProto.GameMessage.StateMsg.newBuilder().setState(gameStateBuilder.build()).build())
                .build();
    }
    private void handleAnnouncement(SnakesProto.GameMessage message, InetAddress address, int port) {
        // игнорируем, потому что это сообщение должно приходит не на этот сокет
    }
    private void handleState(SnakesProto.GameMessage message, InetAddress address, int port) {
        SnakesProto.GameMessage.StateMsg stateMsg = message.getState();
        lastStateMsg = stateMsg;

        players.clear();

        stateMsg.getState().getPlayers().getPlayersList().forEach(player -> {
            try {
                if (player.getId() != -1) {
                    if (player.getRole() == SnakesProto.NodeRole.MASTER) {
                        serverAddress = InetAddress.getByName(player.getIpAddress());
                        serverPort = player.getPort();
                        masterId = player.getId();
                    } else if (player.getRole() == SnakesProto.NodeRole.DEPUTY) {
                        deputyId = player.getId();
                    }
                    players.put(player.getId(), player);
                    addressToPlayerId.put(new InetSocketAddress(InetAddress.getByName(player.getIpAddress()), player.getPort()), player.getId());
                    sentMessages.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                }
            } catch (UnknownHostException e) {
                System.err.println("Error updating server address: " + e.getMessage());
            }
        });

        observer.update(stateMsg, address, port);
    }
    private void handleAck(SnakesProto.GameMessage message, InetAddress address, int port) {
        if (this.playerId == -1) {
            this.playerId = message.getReceiverId();
            sentMessages.put(message.getSenderId(), new ConcurrentHashMap<>());
            addressToPlayerId.put(new InetSocketAddress(address, port), message.getSenderId());
        }
        try {
//            sentMessages.get(message.getSenderId()).get(message.getMsgSeq()).setNeedToDelete();
            sentMessages.get(message.getSenderId()).remove(message.getMsgSeq());
        } catch (Exception e) {
            //ignore because no element in map
        }
    }
    private void handleError(SnakesProto.GameMessage message, InetAddress address, int port) {
        SnakesProto.GameMessage.ErrorMsg error = message.getError();
        System.err.println("[Server] Error: " + error.getErrorMessage() + " from " + address.toString() + port);
//        handlePlayerDisconnection(getPlayerIdByAddress(address, port));
    }
    private void handleRoleChange(SnakesProto.GameMessage message, InetAddress address, int port) throws UnknownHostException {
        SnakesProto.GameMessage.RoleChangeMsg roleChangeMsg = message.getRoleChange();
        SnakesProto.NodeRole receiverRole = roleChangeMsg.getReceiverRole();
        SnakesProto.NodeRole senderRole = roleChangeMsg.getSenderRole();

        // Если мастер нам написал, значит обновляем информацию о текущем мастере
        if (nodeRole == receiverRole && senderRole == SnakesProto.NodeRole.MASTER) { // 1; 4 (если получатель уже как-то оказался депути)
            masterId = message.getSenderId();
            serverPort = port;
            serverAddress = address;
            System.out.println("new master port server " + serverPort + " " + serverAddress);
            players.put(masterId, SnakesProto.GamePlayer.newBuilder(players.get(masterId)).setRole(SnakesProto.NodeRole.MASTER).build());
            return;
        }

        // мастер отправил, что я теперь новый депути
        if (receiverRole == SnakesProto.NodeRole.DEPUTY) {
            deputyId = message.getReceiverId();
            if (playerId == deputyId) {
                nodeRole = SnakesProto.NodeRole.DEPUTY;
            }
            if (players.get(deputyId) == null) {
                return;
            }
            players.put(deputyId, SnakesProto.GamePlayer.newBuilder(players.get(deputyId)).setRole(SnakesProto.NodeRole.DEPUTY).build());

            if (masterId != message.getSenderId()) {
                masterId = message.getSenderId();
                serverPort = port;
                serverAddress = address;
            }
            return;
        }

        // мы депути и матер сказал его заменить
        if (receiverRole == SnakesProto.NodeRole.MASTER) {
            if (nodeRole != SnakesProto.NodeRole.DEPUTY) {
                System.out.println("STRANGE MESSAGE CHANGE ROLE: new receiverRole MASTER, but player isn't DEPUTY!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                return;
            }

            pingSender.interrupt();

            currentMaxId = players.values().stream()
                    .map(SnakesProto.GamePlayer::getId)
                    .max(Integer::compare)
                    .get();

            SnakesProto.GamePlayer oldPlayer = players.get(message.getReceiverId());
            SnakesProto.GamePlayer player = SnakesProto.GamePlayer.newBuilder(oldPlayer).setRole(SnakesProto.NodeRole.MASTER).build();
            players.put(player.getId(), player);

            removePlayer(masterId);

            masterId = player.getId();
            nodeRole = SnakesProto.NodeRole.MASTER;
            deputyId = -1;

            this.serverAddress = InetAddress.getByName(player.getIpAddress());
            this.serverPort = player.getPort();

            // раз мастер, то надо начать его треды
            synchronized (lockGameField) {
                GameLogic.editGameFieldFromState(gameField, lastStateMsg);
                synchronized (lockSnakeGame) {
                    snakeGame = new GameLogic(gameField, lockGameField);
                }
            }
            startServerThreads();
        }
    }
    private void sendError(String errorMessage, InetAddress address, int port) throws IOException {
        SnakesProto.GameMessage error = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setError(SnakesProto.GameMessage.ErrorMsg.newBuilder().setErrorMessage(errorMessage).build())
                .build();

        sendGameMessage(error, address, port);
    }
    private void sendAck(long msg_seq, InetAddress address, int port) throws IOException {
        SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msg_seq)
                .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build())
                .build();

        sendGameMessage(ack, address, port);
    }
    public void sendRoleChange(SnakesProto.NodeRole receiverRole, SnakesProto.NodeRole senderRole, int receiverId) throws IOException {
        SnakesProto.GameMessage roleChangeMessage = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.incrementAndGet())
                .setRoleChange(SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                        .setReceiverRole(receiverRole)
                        .setSenderRole(senderRole)
                        .build())
                .build();

        SnakesProto.GamePlayer receiver = players.get(receiverId);
        if (receiver != null) {
            sendGameMessage(roleChangeMessage, InetAddress.getByName(receiver.getIpAddress()), receiver.getPort());
        }
    }
    private void selectNewDeputy() {
        for (Map.Entry<Integer, SnakesProto.GamePlayer> entry : players.entrySet()) {
            SnakesProto.GamePlayer player = entry.getValue();
            if (player.getRole() == SnakesProto.NodeRole.NORMAL) {
                try {
                    deputyId = player.getId();
                    players.put(deputyId, SnakesProto.GamePlayer.newBuilder(players.get(deputyId)).setRole(SnakesProto.NodeRole.DEPUTY).build());
                    sendRoleChange(SnakesProto.NodeRole.DEPUTY, SnakesProto.NodeRole.MASTER, player.getId()); // Мастер сообщает узлу, что тот теперь депути
                    break;
                } catch (IOException e) {
                    System.err.println("Error sending RoleChangeMsg: " + e.getMessage());
                }
            }
        }
    }
    private void handlePlayerDisconnection(int disconnectedPlayerId) {
        System.out.println("Handling disconnection for player with ID: " + disconnectedPlayerId);
        if (disconnectedPlayerId == -1) {
            System.err.println("Invalid player ID for disconnection: " + disconnectedPlayerId);
            return;
        }

        if (nodeRole == SnakesProto.NodeRole.NORMAL) {
            if (disconnectedPlayerId != masterId) {
                System.err.println("STRANGE disconnectedPlayerId: I am NORMAL, but disconnectedPlayerId isn't MASTER!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                return;
            }
            if (deputyId != -1) {
                masterId = deputyId;
                serverPort = players.get(deputyId).getPort();
                try {
                    serverAddress = InetAddress.getByName(players.get(deputyId).getIpAddress());
                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                }
            } else {
                System.err.println("Master disconnected, and there is no deputy. Game over.");
            }

            removePlayer(disconnectedPlayerId);
            System.out.println("NORMAL player disconnection process completed for LAST MASTER player ID: " + disconnectedPlayerId);

            return;
        }

        if (nodeRole == SnakesProto.NodeRole.MASTER) {
            if (disconnectedPlayerId == deputyId) {
                deputyId = -1;
                selectNewDeputy();
                removePlayer(disconnectedPlayerId);
                System.out.println("MASTER player disconnection process completed for DEPUTY player ID: " + disconnectedPlayerId);
                return;
            }
            // if NORMAL
            removePlayer(disconnectedPlayerId);
            System.out.println("MASTER player disconnection process completed for NORMAL player ID: " + disconnectedPlayerId);
            return;
        }

        if (nodeRole == SnakesProto.NodeRole.DEPUTY) {
            if (disconnectedPlayerId != masterId) {
                System.err.println("STRANGE disconnectedPlayerId: I am DEPUTY, but disconnectedPlayerId isn't MASTER!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                return;
            }
            pingSender.interrupt();

            masterId = playerId;
            nodeRole = SnakesProto.NodeRole.MASTER;
            serverPort = players.get(playerId).getPort();
            try {
                serverAddress = InetAddress.getByName(players.get(playerId).getIpAddress());
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
            players.put(masterId, SnakesProto.GamePlayer.newBuilder(players.get(masterId)).setRole(SnakesProto.NodeRole.MASTER).build());

            removePlayer(disconnectedPlayerId);
            System.out.println("DEPUTY player disconnection process completed for MASTER player ID: " + disconnectedPlayerId);

            deputyId = -1;
            selectNewDeputy();

            System.out.println("I am new MASTER ID = " + masterId + " serverPort = " + serverPort + " serverAddress = " + serverAddress + " deputyId = " + deputyId);

            synchronized (lockGameField) {
                GameLogic.editGameFieldFromState(gameField, lastStateMsg);
                synchronized (lockSnakeGame) {
                    snakeGame = new GameLogic(gameField, lockGameField);
                }
            }
            currentMaxId = players.values().stream()
                    .map(SnakesProto.GamePlayer::getId)
                    .max(Integer::compare)
                    .get();
            startServerThreads();
            return;
        }

        System.err.println("STRANGE disconnectedPlayerId: I am VIEWER, disconnectedPlayerId is " + disconnectedPlayerId + "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }
    private void sendGameMessage(SnakesProto.GameMessage gameMessage, InetAddress address, int port) {
        SnakesProto.GameMessage message = SnakesProto.GameMessage.newBuilder(gameMessage)
                .setSenderId(this.playerId)
                .setReceiverId(getPlayerIdByAddress(address, port))
                .build();
        try {
            byte[] buffer = message.toByteArray();
            DatagramPacket data = new DatagramPacket(buffer, buffer.length, address, port);
            if (players.get(message.getReceiverId()) != null) {
                System.out.println("Send message " + message.getTypeCase() + "(rec: " + message.getReceiverId() + ", sen: " + message.getSenderId() + ", seq: " + message.getMsgSeq() + ") to " + address + ":" + port + "(" + players.get(message.getReceiverId()).getRole() + ")");
            } else {
                System.out.println("Send message " + message.getTypeCase() + "(rec: " + message.getReceiverId() + ", sen: " + message.getSenderId() + ", seq: " + message.getMsgSeq() + ") to " + address + ":" + port);
            }
            if (message.getTypeCase() == SnakesProto.GameMessage.TypeCase.ANNOUNCEMENT) {
                multicastSocket.send(data);
            } else {
                socket.send(data);
                if (message.getReceiverId() == -1 || message.getTypeCase() == SnakesProto.GameMessage.TypeCase.ACK) return;
                if (sentMessages.get(message.getReceiverId()) == null) {
                    sentMessages.put(message.getReceiverId(), new ConcurrentHashMap<>());
                }
                if (sentMessages.get(message.getReceiverId()).get(message.getMsgSeq()) == null)
                    sentMessages.get(message.getReceiverId()).put(message.getMsgSeq(), new MessageInfo(message, address, port, System.currentTimeMillis()));
            }
        } catch (IOException e) {
            System.err.println("Error sending message: " + e.getMessage());
            int player = getPlayerIdByAddress(address, port);
            handlePlayerDisconnection(player);
        } catch (Exception e) {
//            System.err.println("Exception sending message: " + e.getMessage());
            int player = message.getReceiverId();
//            System.out.println("Attempting to remove player with ID: " + playerId);
//            if (!players.containsKey(playerId)) {
//                System.err.println("Player ID " + playerId + " not found in the current players list.");
//                return;
//            }
//
            players.remove(player);
//            addressToPlayerId.values().removeIf(id -> id == playerId);
////            sentMessages.get(playerId).clear();
////            sentMessages.remove(playerId);
//            System.out.println("Player " + playerId + " successfully removed!");
        }
    }
    private int getPlayerIdByAddress(InetAddress address, int port) {
        return addressToPlayerId.getOrDefault(new InetSocketAddress(address, port), -1);
    }
    private int addNewPlayer(String playerName, InetAddress address, int port, SnakesProto.NodeRole requestedRole) {
        int playerId = ++currentMaxId;
        System.out.println("ROLE " + requestedRole);
        if (requestedRole == SnakesProto.NodeRole.MASTER && this.nodeRole != SnakesProto.NodeRole.MASTER) {
            return -1;
        }
        if (requestedRole == SnakesProto.NodeRole.DEPUTY) {
            if (deputyId == -1) {
                deputyId = playerId;
            } else {
                return -1;
            }
        }
        SnakesProto.GamePlayer player = SnakesProto.GamePlayer.newBuilder()
                .setId(playerId)
                .setName(playerName)
                .setRole(requestedRole)
                .setIpAddress(address.getHostAddress())
                .setPort(port)
                .setScore(0)
                .build();
        players.put(playerId, player);
        addressToPlayerId.put(new InetSocketAddress(address, port), playerId);
        sentMessages.put(playerId, new ConcurrentHashMap<>());
        return playerId;
    }
    public synchronized void stop() {
        if (nodeRole == SnakesProto.NodeRole.MASTER) {
            try {
                if (deputyId != -1) {
                    sendRoleChange(SnakesProto.NodeRole.MASTER, SnakesProto.NodeRole.MASTER, deputyId); // Мастер говорит депути стать новым мастером
//                    sendRoleChange(SnakesProto.NodeRole.MASTER, SnakesProto.NodeRole.VIEWER, deputyId); // Старый мастер говорит новому что он вьюер
                }
            } catch(IOException e) {
                System.err.println("Error to send role change!");
                e.printStackTrace();
            }
        }

        if (socket != null) {
            socket.close();
        }
        if (multicastSocket != null) {
            multicastSocket.close();
        }

        if (messageReceiverLoop != null) messageReceiverLoop.interrupt();
        if (announcementSendThread != null) announcementSendThread.interrupt();
        if (gameLoop != null) gameLoop.interrupt();
        if (messageResenderThread != null) messageResenderThread.interrupt();
        if (pingSender != null) pingSender.interrupt();
    }
}
