package com.wyn.expensetracker;

public interface Command {
    void execute();
    void undo();
}