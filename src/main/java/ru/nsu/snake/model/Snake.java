package ru.nsu.snake.model;

import javafx.scene.paint.Color;
import ru.nsu.snake.SnakesProto;

import java.util.*;
 
public class Snake {
    private Deque<SnakesProto.GameState.Coord> body;
    private SnakesProto.Direction direction;
    private final Queue<SnakesProto.Direction> nextDirection;
    private SnakesProto.GameState.Snake.SnakeState state;
    private boolean updated;
    private final Color color;
    private final int playerID;
    private int score;

    public Snake(ArrayList<SnakesProto.GameState.Coord> initialPosition, int playerID) {
        body = new ArrayDeque<>();
        body.addAll(initialPosition);
        this.playerID = playerID;
        this.direction = SnakesProto.Direction.RIGHT;
        this.nextDirection = new LinkedList<>();
        this.state = SnakesProto.GameState.Snake.SnakeState.ALIVE;
        score = 0;
        Random random = new Random();
        this.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    public static Snake parseSnake(SnakesProto.GameState.Snake snake, int height, int width) {
        ArrayList<SnakesProto.GameState.Coord> bodySnake = new ArrayList<>();
        SnakesProto.GameState.Coord head = snake.getPoints(0);
        bodySnake.add(head);

        int x = head.getX();
        int y = head.getY();

        for (int i = 1; i < snake.getPointsCount(); i++) {
            SnakesProto.GameState.Coord offset = snake.getPoints(i);

            for (int j = 0; j < Math.abs(offset.getX()); j++) {
                x += Integer.signum(offset.getX());
                if (x < 0) x += width;
                else if (x >= width) x -= width;
                bodySnake.add(SnakesProto.GameState.Coord.newBuilder().setX(x).setY(y).build());
            }

            for (int j = 0; j < Math.abs(offset.getY()); j++) {
                y += Integer.signum(offset.getY());
                if (y < 0) y += height;
                else if (y >= height) y -= height;
                bodySnake.add(SnakesProto.GameState.Coord.newBuilder().setX(x).setY(y).build());
            }
        }

        return new Snake(bodySnake, snake.getPlayerId());
    }
    public static SnakesProto.GameState.Snake generateSnakeProto(Snake snake, int height, int width) {
        SnakesProto.GameState.Snake.Builder snakeBuilder = SnakesProto.GameState.Snake.newBuilder();
        List<SnakesProto.GameState.Coord> body = new ArrayList<>(snake.getBody());

        if (!body.isEmpty()) {
            snakeBuilder.addPoints(body.get(0));

            int prevX = body.get(0).getX();
            int prevY = body.get(0).getY();
            int cumulativeX = 0;
            int cumulativeY = 0;

            for (int i = 1; i < body.size(); i++) {
                int currentX = body.get(i).getX();
                int currentY = body.get(i).getY();

                int deltaX = currentX - prevX;
                int deltaY = currentY - prevY;

                if (Math.abs(deltaX) > width / 2) {
                    deltaX = width - Math.abs(deltaX);
                    if (currentX > prevX) {
                        deltaX = -deltaX;
                    }
                }

                if (Math.abs(deltaY) > height / 2) {
                    deltaY = height - Math.abs(deltaY);
                    if (currentY > prevY) {
                        deltaY = -deltaY;
                    }
                }

                if ((deltaX != 0 && cumulativeY != 0) || (deltaY != 0 && cumulativeX != 0)) {
                    snakeBuilder.addPoints(SnakesProto.GameState.Coord.newBuilder().setX(cumulativeX).setY(cumulativeY).build());
                    cumulativeX = 0;
                    cumulativeY = 0;
                }

                cumulativeX += deltaX;
                cumulativeY += deltaY;

                prevX = currentX;
                prevY = currentY;
            }

            if (cumulativeX != 0 || cumulativeY != 0) {
                snakeBuilder.addPoints(SnakesProto.GameState.Coord.newBuilder().setX(cumulativeX).setY(cumulativeY).build());
            }
        }

        snakeBuilder.setPlayerId(snake.getPlayerID());
        snakeBuilder.setHeadDirection(snake.getHeadDirection());
        snakeBuilder.setState(snake.getState());
        return snakeBuilder.build();
    }
    public synchronized boolean move(GameField gameField) {
        SnakesProto.GameState.Coord head = body.peekFirst();
        assert head != null;
        int dx = 0, dy = 0;

            SnakesProto.Direction lastDirection = direction;
            while (!nextDirection.isEmpty()) {
                SnakesProto.Direction dir = nextDirection.remove();
                if (dir == direction) continue;
                switch (dir) {
                    case LEFT -> {
                        if (direction != SnakesProto.Direction.RIGHT) lastDirection = SnakesProto.Direction.LEFT;
                    }
                    case RIGHT -> {
                        if (direction != SnakesProto.Direction.LEFT) lastDirection = SnakesProto.Direction.RIGHT;
                    }
                    case UP -> {
                        if (direction != SnakesProto.Direction.DOWN) lastDirection = SnakesProto.Direction.UP;
                    }
                    case DOWN -> {
                        if (direction != SnakesProto.Direction.UP) lastDirection = SnakesProto.Direction.DOWN;
                    }
                }
            }
            direction = lastDirection;

            switch (direction) {
                case UP -> dy = -1;
                case DOWN -> dy = 1;
                case LEFT -> dx = -1;
                case RIGHT -> dx = 1;
            }
            SnakesProto.GameState.Coord newHead = SnakesProto.GameState.Coord.newBuilder()
                    .setX((gameField.getWidth() + head.getX() + dx) % gameField.getWidth())
                    .setY((gameField.getHeight() + head.getY() + dy) % gameField.getHeight())
                    .build();
            if (body.contains(newHead)) {
                return false;
            }
            body.addFirst(newHead);

        return true;
    }
    public synchronized void setNextDirection(SnakesProto.Direction newDirection) { nextDirection.add(newDirection); }
    public Deque<SnakesProto.GameState.Coord> getBody() {
        return body;
    }
    public void setBody(Deque<SnakesProto.GameState.Coord> newBody) {
        this.body = newBody;
    }
    public SnakesProto.GameState.Coord getHead() {
        return body.peekFirst();
    }
    public void setState(SnakesProto.GameState.Snake.SnakeState state) {
        this.state = state;
    }
    public Color getColor() {
        return color;
    }

    public SnakesProto.Direction getHeadDirection() {
        return direction;
    }
    public void setHeadDirection(SnakesProto.Direction direction) {
        this.direction = direction;
    }
    public SnakesProto.GameState.Snake.SnakeState getState() {
        return state;
    }
    public boolean isUpdated() {
        return updated;
    }

    public void setUpdated() {
        updated = true;
    }
    public void clearUpdated() {
        updated = false;
    }
    public int getPlayerID() {
        return playerID;
    }
    public void setScore(int score) {
        this.score = score;
    }
    public int getScore() {
        return score;
    }
    public void addScore(int val) {
        score += val;
    }
}

