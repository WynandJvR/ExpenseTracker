module com.wyn.expensetracker {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;

    // Open the package to JavaFX FXML (if using FXML)
    opens com.wyn.expensetracker to javafx.fxml;
    exports com.wyn.expensetracker;
}