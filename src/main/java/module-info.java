module ru.nsu.snake {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.protobuf;


    opens ru.nsu.snake to javafx.fxml;
    exports ru.nsu.snake;
    exports ru.nsu.snake.model;
    exports ru.nsu.snake.ui;
}