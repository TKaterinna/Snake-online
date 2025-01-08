package ru.nsu.snake;

import javafx.scene.input.KeyCode;
import ru.nsu.snake.SnakesProto;
import ru.nsu.snake.ui.ServerInfo;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;

public class Controller {
    private final Observer UI;
    private MulticastSocket multicastSocket = null;

    private SnakeNet snakeNet = null;

    Thread serverListListener;

    public Controller(Observer UI) {
        this.UI = UI;
    }
    public static String getAddress(String networkName) throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (iface.isLoopback() || !iface.isUp())
                continue;

            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    String ip = addr.getHostAddress();
                    if (iface.getDisplayName().contains(networkName)) return ip;
                }
            }
        }
        return "localhost";
    }
    public static NetworkInterface findNetworkInterface(String networkName) throws SocketException {
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (iface.isUp() && !iface.isLoopback()) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (addr.getAddress() instanceof Inet4Address) {
                        if (iface.getDisplayName().contains(networkName)) {
                            return iface;
                        }
                    }
                }
            }
        }
        return null;
    }
    public void close() {
        if (multicastSocket != null) multicastSocket.close();
        if (serverListListener != null) serverListListener.interrupt();
        if (snakeNet != null) snakeNet.stop();
    }
    public void start() { // receive ANNOUNCEMENT
        serverListListener = new Thread(() -> {
            try {
                multicastSocket = new MulticastSocket(SnakeNet.MULTICAST_PORT);
                InetAddress group = InetAddress.getByName(SnakeNet.MULTICAST_ADDRESS);

                NetworkInterface networkInterface = findNetworkInterface("eth0");
                if (networkInterface == null) {
                    System.err.println("Failed to find a suitable network interface");
                    return;
                }

                SocketAddress socketAddress = new InetSocketAddress(group, SnakeNet.MULTICAST_PORT);
                multicastSocket.joinGroup(socketAddress, networkInterface);

                while (!serverListListener.isInterrupted()) {
                    try {
                        byte[] buf = new byte[256];
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        multicastSocket.receive(packet);

                        byte[] trimmedData = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getLength());

                        SnakesProto.GameMessage message = SnakesProto.GameMessage.parseFrom(trimmedData);
                        System.out.println("[Controller] Get Multicast message: " + message.getTypeCase() + " from " + packet.getAddress() + ":" + packet.getPort());
                        if (message.getTypeCase() != SnakesProto.GameMessage.TypeCase.ANNOUNCEMENT) continue;

                        UI.update(message.getAnnouncement(), packet.getAddress(), packet.getPort());
                    } catch (IOException ex) {
                        System.err.println("[Controller] Announcement receive error!");
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("[Controller] MulticastServer create error!");
            }
        });
        serverListListener.start();
    }
    public void startClient(String playerName, ServerInfo serverInfo) throws IOException {
        snakeNet = new SnakeNet(serverInfo, UI);
        snakeNet.startAsClient(playerName, InetAddress.getByName(serverInfo.serverIPProperty().get()), serverInfo.serverPortProperty().get());
    }
    public void stopServer() {
        if (snakeNet != null) {
            snakeNet.stop();
            snakeNet = null;
        }
    }
    public void startServer(String playerName, ServerInfo serverInfo) throws IOException {
        snakeNet = new SnakeNet(serverInfo, UI);
        snakeNet.startAsServer(playerName, InetAddress.getByName(serverInfo.serverIPProperty().get()), serverInfo.serverPortProperty().get());
    }
    public void sendSteerMsg(KeyCode code) throws IOException {
        System.out.println("TYPE KEY");
        switch (code) {
            case A, LEFT, KP_LEFT -> snakeNet.sendSteer(SnakesProto.Direction.LEFT);
            case S, DOWN, KP_DOWN -> snakeNet.sendSteer(SnakesProto.Direction.DOWN);
            case D, RIGHT, KP_RIGHT -> snakeNet.sendSteer(SnakesProto.Direction.RIGHT);
            case W, UP, KP_UP -> snakeNet.sendSteer(SnakesProto.Direction.UP);
        }
    }
}