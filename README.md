# ExpenseTracker

A feature-rich desktop expense tracking application built with JavaFX. Track daily spending, manage budgets, import bank statements, scan receipts, and visualize spending patterns — all with a polished dark-themed UI.

## Features

### Expense Management
- Add, edit, and delete expenses with amount, category, date, and description
- Inline table editing (double-click any cell)
- Full undo/redo support (Ctrl+Z / Ctrl+Y)
- Search and filter by category, amount range, or text
- Month/year navigation with keyboard shortcuts

### Recurring Expenses
- Create recurring expenses with daily, weekly, monthly, or yearly frequency
- Optional end dates
- Auto-generates expense instances up to the current date

### Income & Budget Tracking
- Set recurring monthly income with per-month overrides
- Per-category budget limits with color-coded progress bars (green/yellow/red)
- Money saved calculation (income minus expenses)

### Import
- **Receipt Scanning** — OCR-based extraction from receipt images (JPG, PNG, BMP, TIFF) via Tesseract
- **Bank Statement Import** — PDF parsing (FNB format supported) and CSV with auto-delimiter detection
- **Auto-Categorization Rules** — keyword-based category assignment for imported transactions
- **Import History** — track and remove entire import batches
- Preview dialog to review and edit items before importing

### Analytics & Charts
- **Overview** — pie chart, monthly trend bar chart, category breakdown table
- **Income & Budget** — income vs expenses, budget vs actual comparisons
- **Spending Trends** — cumulative spending line chart, stacked area chart by category
- **Comparisons** — year-over-year trends, recurring vs one-time breakdown
- Configurable chart periods: All Time, Last 12/6 Months, By Year, By Month

### Dashboard
- Summary cards: Total Spent, Top Category, Budget Status, vs Last Month
- Real-time updates when switching periods

### Export
- Export to Excel (.xlsx) — all expenses or current filtered view

### Other
- Multi-currency support (R, $, €, £, ¥, CHF, kr, Rs)
- Automatic backup rotation (keeps last 5 backups)
- Atomic file writes for data safety

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

All data is stored in `~/.expenseTracker/` as plain text files:

| File | Contents |
|---|---|
| `expenses.txt` | All expense records |
| `categories.txt` | User-defined categories |
| `incomes.txt` | Monthly income entries |
| `budgets.txt` | Per-category budget limits |
| `categorizationRules.txt` | Auto-categorization keywords |
| `currencySymbol.txt` | Selected currency |

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
