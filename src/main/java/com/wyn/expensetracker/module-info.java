module com.wyn.expensetracker {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    opens com.wyn.expensetracker to javafx.fxml;
    exports com.wyn.expensetracker;
}