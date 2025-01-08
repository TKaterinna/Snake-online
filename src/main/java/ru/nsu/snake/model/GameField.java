package ru.nsu.snake.model;

import ru.nsu.snake.SnakesProto;
import ru.nsu.snake.ui.ServerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameField {
    private final int width;
    private final int height;
    private final int foodStatic;
    private final int delayMS;
    private volatile List<Snake> snakes;
    private ArrayList<SnakesProto.GameState.Coord> foods;
    public GameField(ServerInfo serverInfo) {
        String[] numbers = serverInfo.areaSizeProperty().get().split("[^0-9]+");
        this.width = Integer.parseInt(numbers[0]);
        this.height = Integer.parseInt(numbers[1]);
        this.foodStatic = serverInfo.foodStaticProperty().get();
        this.delayMS = serverInfo.stateDelayMsProperty().get();
        this.snakes = new ArrayList<>();
        this.foods = new ArrayList<>();
    }

    public SnakesProto.Direction findValidSnakePosition(ArrayList<SnakesProto.GameState.Coord> initialPosition) {
        Random random = new Random();
        int maxAttempts = 100000;
        int squareSize = 5;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int centerX = random.nextInt(width);
            int centerY = random.nextInt(height);

            boolean squareIsFree = true;
            for (int dx = -squareSize / 2; dx <= squareSize / 2; dx++) {
                for (int dy = -squareSize / 2; dy <= squareSize / 2; dy++) {
                    int x = (centerX + dx + width) % width;
                    int y = (centerY + dy + height) % height;

                    SnakesProto.GameState.Coord cell = SnakesProto.GameState.Coord.newBuilder()
                            .setX(x)
                            .setY(y)
                            .build();

                    if (isCellOccupied(cell)) {
                        squareIsFree = false;
                        break;
                    }
                }
                if (!squareIsFree) break;
            }

            if (squareIsFree) {
                SnakesProto.GameState.Coord head = SnakesProto.GameState.Coord.newBuilder()
                        .setX(centerX)
                        .setY(centerY)
                        .build();

                int direction = random.nextInt(4);

//                int tailX = (centerX - 1 + width) % width;
//                int tailY = centerY;

                int tailX = centerX;
                int tailY = centerY;
                SnakesProto.Direction headDirection = SnakesProto.Direction.RIGHT;

                switch (direction) { // 0 - вверх, 1 - вправо, 2 - вниз, 3 - влево
                    case 0 -> {
                        tailY = (centerY - 1 + height) % height;
                        headDirection = SnakesProto.Direction.DOWN;
                    }
                    case 1 -> {
                        tailX = (centerX + 1) % width;
                        headDirection = SnakesProto.Direction.LEFT;
                    }
                    case 2 -> {
                        tailY = (centerY + 1) % height;
                        headDirection = SnakesProto.Direction.UP;
                    }
                    case 3 -> {
                        tailX = (centerX - 1 + width) % width;
                        headDirection = SnakesProto.Direction.RIGHT;
                    }
                }

                System.out.println("Field headDirection = " + headDirection.name());

                SnakesProto.GameState.Coord tail = SnakesProto.GameState.Coord.newBuilder()
                        .setX(tailX)
                        .setY(tailY)
                        .build();

                if (!isCellOccupied(head) && !isCellOccupied(tail)) {
                    initialPosition.add(head);
                    initialPosition.add(tail);
                    return headDirection;
                }
            }
        }

        throw new RuntimeException("No space");
    }
    public boolean isCellOccupied(SnakesProto.GameState.Coord cell) {
        for (Snake snake : snakes) {
            if (snake.getBody().contains(cell)) {
                return true;
            }
        }
        return foods.contains(cell);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }
    public void updateSnake(Snake snake) {
        for (Snake snk : snakes) {
            if (snk.getPlayerID() == snake.getPlayerID()) {
                snk.setBody(snake.getBody());
                snk.setHeadDirection(snake.getHeadDirection());
                snk.setUpdated();
                return;
            }
        }
        addSnake(snake);
        snake.setUpdated();
    }
    public void addSnake(Snake snake) {
        snakes.add(snake);
        System.out.println("field count sn " + snakes.size());
    }
    public synchronized void removeSnake(Snake snake) {
        Random random = new Random();
        for (SnakesProto.GameState.Coord body : snake.getBody()) {
            if (body.equals(snake.getHead())) continue;
            if (random.nextInt(100) < 50) {
                foods.add(body);
            }
        }
        snakes.remove(snake);
    }
    public int amountOfFoodNeeded(int playerCount) {
        return playerCount + foodStatic;
    }
    public int getFoodStatic() {
        return foodStatic;
    }
    public List<Snake> getSnakes() {
        return new ArrayList<>(snakes);
    }
    public ArrayList<SnakesProto.GameState.Coord> getFoods() {
        return foods;
    }
    public int getDelayMS() {
        return delayMS;
    }
    public boolean hasPlace() {
        int foodSize = foods.size();
        int snakeSize = 0;
        for (Snake snake : getSnakes()) {
            snakeSize += snake.getBody().size();
        }
        return (foodSize + snakeSize) < (width * height - 1);
    }
}
