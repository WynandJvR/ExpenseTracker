package com.wyn.expensetracker;

import java.util.List;

public interface BankStatementParser {
    boolean canParse(String text);
    List<ImportItem> parse(String text);
    String getBankName();
}
