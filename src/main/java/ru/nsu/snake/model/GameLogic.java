package ru.nsu.snake.model;

import ru.nsu.snake.SnakesProto;

import java.util.ArrayList;
import java.util.List;

public class GameLogic {
    private final GameField gameField;
    private final Object lock;

    public GameLogic(GameField gameField, Object lock) {
        this.gameField = gameField;
        this.lock = lock;
    }

    public static void editGameFieldFromState(GameField gameField, SnakesProto.GameMessage.StateMsg stateMsg) {
        if (gameField == null || stateMsg == null) return;
        gameField.getFoods().clear();
        for (SnakesProto.GameState.Coord food : stateMsg.getState().getFoodsList()) {
            gameField.getFoods().add(food);
        }
        System.out.println("edit field, snakes count " + gameField.getSnakes().size() + " new " + stateMsg.getState().getSnakesList().size());
        for (Snake snake : gameField.getSnakes()) {
            snake.clearUpdated();
        }
        for (SnakesProto.GameState.Snake snake : stateMsg.getState().getSnakesList()) {
            gameField.updateSnake(Snake.parseSnake(snake, gameField.getHeight(), gameField.getWidth()));
        }
        for (Snake snake : gameField.getSnakes()) {
            if (!snake.isUpdated()) {
                gameField.removeSnake(snake);
            }
        }
    }
    public void update() {
//        System.out.println("logic update count sn " + gameField.getSnakes().size());
//        GameField field;
//        synchronized (lock) {
//            field = gameField;
//        }

            if (gameField.getFoods().size() < gameField.amountOfFoodNeeded(gameField.getSnakes().size())) {
                int foodNeeded = gameField.amountOfFoodNeeded(gameField.getSnakes().size()) - gameField.getFoods().size();
                System.err.println("[GameLogic] Needed " + foodNeeded + " food");
                for (int i = 0; i < foodNeeded; ++i) {
                    if (gameField.hasPlace()) {
                        placeFood();
                    } else {
                        System.err.println("[GameLogic] Place not found...!");
                        break;
                    }
                }
            }
//        System.out.println("logic update count sn " + gameField.getSnakes().size());

        List<Snake> snakesToRemove = new ArrayList<>();

        for (Snake snake : gameField.getSnakes()) {
            if (!snake.move(gameField)) {
//                System.out.println("no move");
                snakesToRemove.add(snake);
                continue;
            }

            if (gameField.getFoods().contains(snake.getHead())) {
//                System.out.println("food");
                snake.addScore(1);

                gameField.getFoods().remove(snake.getHead());
            } else {
//                System.out.println("else");
                snake.getBody().removeLast();
            }
        }

        for (Snake snakeToRemove : snakesToRemove) {
            System.out.println("remove snake");
            gameField.removeSnake(snakeToRemove);
        }

//        System.out.println("logic update count sn " + gameField.getSnakes().size());

        snakesToRemove.clear();

        for (Snake snake : gameField.getSnakes()) {
            for (Snake anotherSnake : gameField.getSnakes()) {
                if (anotherSnake.equals(snake)) continue;
                if (anotherSnake.getHead().equals(snake.getHead())) {
                    snakesToRemove.add(snake);
                    snakesToRemove.add(anotherSnake);
                    continue;
                }
                if (anotherSnake.getBody().contains(snake.getHead())) {
                    anotherSnake.addScore(1);
                    snakesToRemove.add(snake);
                }
            }
        }

        for (Snake snakeToRemove : snakesToRemove) {
            System.out.println("remove snake");
            gameField.removeSnake(snakeToRemove);
        }
//        System.out.println("logic update count sn " + gameField.getSnakes().size());
    }
    public void updateDirection(int playerId, SnakesProto.Direction newDirection) {
        for (Snake snake : gameField.getSnakes()) {
            if (snake.getPlayerID() == playerId) {
                snake.setNextDirection(newDirection);
                return;
            }
        }
        System.err.println("[GameLogic] updateDirection: PlayerID " + playerId + " not found!");
    }
    public void placeFood() {
        int x = (int) (Math.random() * gameField.getWidth());
        int y = (int) (Math.random() * gameField.getHeight());
        SnakesProto.GameState.Coord foodPosition = SnakesProto.GameState.Coord.newBuilder()
                .setX(x)
                .setY(y)
                .build();
        if (!gameField.isCellOccupied(foodPosition)) {
            gameField.getFoods().add(foodPosition);
        } else {
            placeFood();
        }
    }
    public void addSnake(Snake snake) {
        gameField.addSnake(snake);
    }
    public GameField getGameField() {
        return gameField;
    }
}
