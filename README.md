# ExpenseTracker

A desktop app (JavaFX) that turns your bank statements into a clear picture of where your money goes. Drop in a statement and it does the rest: every line is checked against the statement's balances, transfers between your own accounts are set aside, and spending is categorised automatically.

## Features

### Import — the main way in
- **Drag and drop** PDF, CSV, OFX/QFX or QIF statements (several at once), or pick them with *Choose files…*
- **Statements folder** — point the app at the folder where you save statements; new ones are imported whenever the app opens (and when you come back to it). Removed imports aren't brought back.
- **Balance check** — for statements that print balances (e.g. FNB), opening balance + money in − money out must equal the closing balance. Each import shows ✓ Matches or ⚠ Check. Lines that don't move the balance (such as failed debit-order notices) are skipped.
- **Classification** — own-account transfers (pockets, savings, investments, loan draw-downs) are excluded from totals; credits are income or refunds; fee reversals are refunds of bank fees.
- **Categories** — built-in list of common South African merchants, plus rules learned from you: change a transaction's category once and similar transactions (and future imports) follow. Right-click → *This is one of my own accounts* turns a payee into a transfer.
- **No double counting** — re-importing a file, or importing overlapping statements (even a PDF and a CSV of the same period), only adds new transactions; genuine repeated same-day charges are kept.
- One undo (Ctrl+Z) reverses a whole import. CSV columns and date formats are detected automatically; a mapping dialog appears only when they can't be.
- Receipt scanning (OCR via Tesseract) is still available for till slips.

### Overview
- Money in, money out, what's left over, and your latest account balance for the selected month
- "Where your money went" — categories as bars, with budgets (right-click a category to set one)
- Money in vs out for the last six months
- Notes that need attention: uncategorised transactions, statements that don't add up, an out-of-date latest statement, categories over budget
- Upcoming bills, unusual spending, savings goals and debts

### Transactions
- One searchable table; switch between *This month* and *All time*, filter by type (spending, income, refunds, transfers), category, amount or tag
- Double-click to edit; right-click for refunds, tags, receipts, recurring and own-account actions
- Undo/redo everything (Ctrl+Z / Ctrl+Y); export to Excel

### Planning
- **Recurring** bills and income (daily → yearly, per-occurrence skip/edit), and detection of repeating payments in your statements
- **Analytics** — spending by category, trends, income vs spending, budgets, projections and a cash-flow calendar
- **Debts** — amortisation schedules; link a debt to a keyword so its debit orders count as payments
- **Savings goals**, multiple profiles, multi-currency with exchange rates

## Prerequisites

- **Java 17** or later
- **Maven 3.6+**

## Build & Run

```bash
# Clone the repository
git clone https://github.com/your-username/ExpenseTracker.git
cd ExpenseTracker

# Run the application
mvn javafx:run

# Run tests
mvn test

# Package
mvn package
```

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| Ctrl+N | New expense (focus amount field) |
| Ctrl+Z | Undo |
| Ctrl+Y | Redo |
| Ctrl+E | Export |
| Ctrl+F | Search |
| Alt+Left | Previous month |
| Alt+Right | Next month |

## Data Storage

All data is stored per profile in `~/.expenseTracker/profiles/<name>/` as UTF-8 text files. Nothing is sent anywhere.

| File | Contents |
|---|---|
| `expenses.txt` | All expense records |
| `categories.txt` | User-defined categories |
| `incomes.txt` | Monthly income entries |
| `budgets.txt` | Per-category budget limits |
| `categorization_rules.txt` | Categorisation rules (including ones learned from your edits) |
| `statements.txt` | Imported statements: period, balances, balance check |
| `fingerprints.txt` | Identities of imported transactions (prevents double counting) |
| `auto_import.txt` / `dismissed.txt` | Statements folder, and removed imports the folder scan should skip |
| `settings.txt`, `exchange_rates.txt` | Base currency, recurring income, exchange rates |
| `debts.txt`, `goals.txt`, `import_log.txt`, … | Debts, savings goals, import history, and other features |

## Tech Stack

- **JavaFX 21** — UI framework with FXML layout
- **Apache POI 5.2.5** — Excel export
- **Tess4j 5.11.0** — OCR for receipt scanning
- **Apache PDFBox 2.0.31** — PDF bank statement parsing
- **JUnit 5** — testing

## Architecture

The application follows an MVC pattern with a Command-based undo/redo system:

- **Models** — `Expense`, `RecurringExpense`, `CategoryTotal`, `ImportItem`
- **Controller** — `MainController` handles all UI logic
- **Manager** — `ExpenseManager` encapsulates business logic
- **Storage** — `FileStorage` for persistence, `ExcelExporter` for export
- **Commands** — `AddExpenseCommand`, `DeleteExpenseCommand`, `EditExpenseCommand`, etc.
- **Parsers** — `FnbPdfParser`, `CsvStatementParser`, `ReceiptScanner`

## License

This project is for personal use.
