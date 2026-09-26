package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what an imported bank line is — spending, income, a refund, or a transfer
 * between the user's own accounts — and gives it a sensible category, so a statement
 * can be imported without any manual review.
 *
 * Order of precedence for categories: the user's own rules (learned from their
 * corrections) first, then the built-in merchant list below, else "Uncategorized".
 */
public final class TransactionClassifier {

    public static final String UNCATEGORIZED = "Uncategorized";
    public static final String TRANSFERS = "Transfers";
    public static final String INCOME = "Income";
    public static final String BANK_FEES = "Bank Fees";
    public static final String PAYMENTS = "Payments";
    public static final String DEBIT_ORDERS = "Debit Orders";

    private TransactionClassifier() {}

    /** Own-account movements (savings pockets, investments, loan draw-downs). Matched against the raw description. */
    private static final List<String> TRANSFER_PATTERNS = List.of(
        "fnb app transfer from", "fnb app transfer to", "payment to investment",
        "transfer to investment", "transfer from investment", "investment deposit",
        "fnb ob coll fnbinvest", "byc debit", "bank your change", "trf from loan acc",
        "trf to loan acc", "credit int paid to",
        "internal transfer", "own account transfer", "inter account transfer",
        "transfer between accounts", "savings pocket"
    );

    private static final List<String> REFUND_PATTERNS = List.of(
        "refund", "reversal", "reversed", "credit voucher", "chargeback", "cashback",
        "dispute", "fee rebate"
    );

    /** Built-in merchant keywords → category, checked longest keyword first. */
    private static final Map<String, String> BUILT_IN = new LinkedHashMap<>();
    private static final List<Map.Entry<String, String>> BUILT_IN_SORTED;
    private static final java.util.Set<String> CHANNEL_CATEGORIES = java.util.Set.of(PAYMENTS, DEBIT_ORDERS);
    /**
     * Brand names that banks run into the next word when they truncate descriptions
     * ("Liquorshop", "Bobshopcoza"). Only these may match without a clean word ending;
     * everything else — and every user rule — must end at a word boundary.
     */
    private static final java.util.Set<String> RUN_ON_KEYWORDS = java.util.Set.of(
        "liquor", "bobshop", "studocu", "privateinternet", "takealot", "steamgames", "mrdfood",
        "aliexpress", "sheetstreet", "superbalist", "evetech", "dischem", "woolworths", "checkers",
        "mcdonald", "steam", "spotify", "netflix");
    /** Built-in spending categories; a user rule pointing at one of these never re-files income. */
    private static final java.util.Set<String> SPENDING_CATEGORIES;

    private static void rule(String category, String... keywords) {
        for (String k : keywords) BUILT_IN.put(squash(k), category);
    }

    /**
     * Lower-case with '*' and '.' as spaces, so "Uber *Eats", "Takealo*T" and "Netflix.com"
     * compare like the keywords "uber eats", "takealo t" and "netflix com".
     */
    private static String squash(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace('*', ' ').replace('.', ' ')
            .replaceAll("\\s+", " ").trim();
    }

    static {
        rule("Groceries", "checkers", "spar", "woolworths", "pick n pay", "picknpay", "pnp ", "shoprite",
            "food lover", "boxer", "usave", "okfoods", "ok foods", "fruit & veg", "fruit and veg",
            "superspar", "kwikspar", "makro", "game store", "cambridge food", "sixty60", "dis-chem food");
        rule("Eating Out", "mr d food", "mrd ", "mrdfood", "mr delivery", "uber eats", "ubereats", "mcd ", "mcdonald",
            "kfc", "steers", "nandos", "nando's", "debonairs", "wimpy", "spur", "burger king", "bk ", "roman's",
            "romans pizza", "ocean basket", "starbucks", "vida e", "mugg & bean", "mugg and bean", "fishaways",
            "pizza", "restaurant", " cafe", "coffee", "bakery", "chicken licken", "hungry lion", "galito",
            "col'cacchio", "rocomamas", "panarottis", "john dory", "news cafe", "tashas");
        rule("Shopping", "takealot", "takealo*t", "bobshop", "amazon", "shein", "temu", "makro online",
            "superbalist", "mr price", "mrp ", "pep ", "ackermans", "edgars", "truworths", "h&m", "zara",
            "cotton on", "sportscene", "totalsports", "incredible connection", "evetech", "samsung",
            "istore", "game ", "builders", "leroy merlin", "cashbuild", "bargain books", "exclusive books",
            "cna ", "payfast*", "clicks home", "paypal", "aliexpress", "sheetstreet", "sheet street",
            "incredible", "hifi corp", "takealot.com", "wish.com");
        rule("Fuel", "fuel purchase", "engen", "shell", "sasol", "caltex", "astron", "totalenergies",
            "bp ", "puma energy");
        rule("Transport", "uber", "bolt", "gautrain", "e-toll", "sanral", "parking", "taxify", "didi");
        rule("Travel", "flysafair", "safair", "airlink", "lift airline", "kulula", "british airways",
            "airbnb", "booking.com", "hotel", "lodge", "guest house", "guesthouse", "emirates", "qatar air");
        rule("Utilities", "prepaid electricity", "electricity prepaid", "electricity", "municipal", "munic ",
            "water & lights", "eskom", "city of ", "rates and taxes");
        rule("Phone & Internet", "airtime", "data bundle", "connect topup", "hybrid subscription fee",
            "vodacom", "vodashop", "mtn ", "telkom", "cell c", "rain ", "afrihost", "webafrica", "vumatel",
            "openserve", "wifi", "internet", "fibre", "coolideas", "mweb");
        rule("Subscriptions", "netflix", "spotify", "showmax", "dstv", "multichoice", "youtube",
            "disney", "apple.com", "itunes", "prime video", "deezer", "icloud",
            "google one", "google play", "google ", "xbox", "playstation", "psn ", "nintendo");
        rule("Software & AI", "claude.ai", "anthropic", "openai", "chatgpt", "neon.tech", "fly.io",
            "google cloud", "github", "privateinternet", "private internet", "vpn", "digitalocean", "aws ", "vercel",
            "cursor", "jetbrains", "microsoft", "adobe", "notion.so", "x corp");
        rule("Games", "steam", "steamgames", "epic games", "riot games", "xsolla",
            "blizzard", "ea games", "g2a", "instant gaming");
        rule("Health", "dischem", "dis-chem", "clicks", "pharmacy", "apteek", "medirite", "doctor",
            "dentist", "optometrist", "spec-savers", "specsavers", "hospital", "netcare",
            "mediclinic", "life healthcare", "pathcare", "lancet", "ampath", "medical aid", "discovery health");
        rule("Pets", "clickapet", "vet ", "veterinary", "dierekliniek", "pet shop", "petshop");
        rule("Insurance", "insurance", "outsurance", "santam", "discovery insure", "miway", "old mutual",
            "sanlam", "momentum", "hollard", "king price", "budget insurance", "1life", "clientele");
        rule("Tax", "sars", "sarseflng", "sars efiling");
        rule("Debt Repayments", "internal d/o fnbcc", "fnbcc", "fnb ploan", "credit card payment",
            "loan repayment", "home loan", "vehicle finance", "wesbank", "mfc ", "rcs ", "edcon");
        rule("Cash", "atm cash", "cash withdrawal", "atm withdrawal", "cash @ till", "cashsend");
        rule("Alcohol", "liquor", "tops ", "ultra liquors", "norman goodfellows", "bottle store");
        rule("Education", "university", "unisa", "nwu", "tuition", "school fees", "udemy", "coursera",
            "eduvos", "studocu");
        rule("Entertainment", "ster-kinekor", "ster kinekor", "sterkinekor", "nu metro", "computicket", "ticketpro",
            "quicket", "howler", "webtickets");
        rule(BANK_FEES, "bank charges", "service fee", "monthly account fee", "#monthly", "admin fee",
            "transaction fee", "sms notification", "notific fee", "honouring fee", "unpaid fee",
            "cash deposit fee", "card replacement");
        rule("Rent", "rent", "huur");
        // Interest charged on an overdraft or card is a cost of banking.
        rule(BANK_FEES, "interest adjustment", "debit interest", "overdraft interest", "int on overdraft",
            "interest on overdraft", "int on debit balance", "interest on debit balance", "fee reversal");
        rule(DEBIT_ORDERS, "magtape debit", "debit order", "debicheck");
        // Payments to other people/accounts (EFT, PayShap). Specific payees are learned from the user's corrections.
        rule(PAYMENTS, "fnb app payment to", "payshap account off-us", "internet pmt to", "fnb app rtc",
            "fnb ob pmt", "eft payment", "immediate payment", "payment to ");

        BUILT_IN_SORTED = new ArrayList<>(BUILT_IN.entrySet());
        BUILT_IN_SORTED.sort((a, b) -> b.getKey().length() - a.getKey().length());
        java.util.Set<String> spending = new java.util.HashSet<>(BUILT_IN.values());
        spending.removeAll(CHANNEL_CATEGORIES);
        SPENDING_CATEGORIES = java.util.Collections.unmodifiableSet(spending);
    }

    /** Built-in categories, in a sensible display order, for seeding a new profile's category list. */
    public static List<String> builtInCategories() {
        List<String> cats = new ArrayList<>();
        for (String c : BUILT_IN.values()) if (!cats.contains(c)) cats.add(c);
        cats.add(INCOME);
        cats.add(TRANSFERS);
        return cats;
    }

    /**
     * Classifies and categorises an item in place. Sets transfer/refund/income flags
     * from the direction and wording, then fills the category if it's still empty.
     */
    public static void classify(ImportItem item, CategorizationRules userRules) {
        String desc = item.getDescription() == null ? "" : item.getDescription();
        String lower = pad(desc);

        item.setTransfer(false);
        item.setRefund(false);
        item.setIncome(false);

        String userCategory = userRules != null ? userRules.categorize(desc) : null;

        // Own-account movement: a built-in pattern, or a payee the user marked as "one of my accounts".
        if (containsAny(lower, TRANSFER_PATTERNS) || TRANSFERS.equals(userCategory)) {
            item.setTransfer(true);
            item.setCategory(TRANSFERS);
            item.setStatus("Transfer");
            return;
        }

        if (item.isCredit()) {
            boolean feeReversal = BANK_FEES.equals(item.getCategory());
            if (feeReversal || containsAny(lower, REFUND_PATTERNS) || containsWord(lower, "pos purchase")) {
                // Money back for something bought: reduces spend in that category.
                item.setRefund(true);
                String cat = userCategory != null ? userCategory
                    : feeReversal ? BANK_FEES : builtInCategory(desc);
                item.setCategory(cat != null && !INCOME.equals(cat) ? cat : UNCATEGORIZED);
                item.setStatus("Refund");
            } else {
                // Income keeps an income category: a spending rule (e.g. a shop that is also
                // the employer) must not file a salary under Groceries.
                item.setIncome(true);
                String incomeCategory = userRules != null
                    ? userRules.categorize(desc, c -> !SPENDING_CATEGORIES.contains(c) && !TRANSFERS.equals(c)) : null;
                boolean usable = incomeCategory != null;
                item.setCategory(usable ? incomeCategory : INCOME);
                item.setStatus(usable ? "Auto-categorized" : "Income");
            }
            return;
        }

        if (userCategory != null && !INCOME.equals(userCategory)) {
            item.setCategory(userCategory);
            item.setStatus("Auto-categorized");
            return;
        }
        if (item.getCategory() != null && !item.getCategory().isBlank()) {
            item.setStatus("Auto-categorized"); // parser already knew (e.g. bank charges)
            return;
        }
        String builtIn = builtInCategory(desc);
        if (builtIn != null) {
            item.setCategory(builtIn);
            item.setStatus("Auto-categorized");
        } else {
            item.setCategory(UNCATEGORIZED);
            item.setStatus("Uncategorized");
        }
    }

    /**
     * The built-in category for a description, or null. Keywords match whole words, longest
     * first. A payment-channel phrase ("Internet Pmt To", "Payment To") only says how money
     * moved, so it is removed before looking for a merchant and used only as a fallback:
     * "Internet Pmt To SARS" is Tax, not Phone &amp; Internet.
     */
    public static String builtInCategory(String description) {
        if (description == null) return null;
        String text = " " + squash(description) + " ";
        String fallback = null;
        for (Map.Entry<String, String> e : BUILT_IN_SORTED) {
            if (!CHANNEL_CATEGORIES.contains(e.getValue())) continue;
            int at = indexOfWord(text, e.getKey(), false);
            if (at >= 0) {
                if (fallback == null) fallback = e.getValue();
                text = text.substring(0, at) + " ".repeat(e.getKey().length()) + text.substring(at + e.getKey().length());
            }
        }
        for (Map.Entry<String, String> e : BUILT_IN_SORTED) {
            if (CHANNEL_CATEGORIES.contains(e.getValue())) continue;
            if (indexOfWord(text, e.getKey(), RUN_ON_KEYWORDS.contains(e.getKey())) >= 0) return e.getValue();
        }
        return fallback;
    }

    public static boolean isTransferDescription(String description) {
        return description != null && containsAny(pad(description), TRANSFER_PATTERNS);
    }

    private static String pad(String s) {
        return " " + s.toLowerCase(java.util.Locale.ROOT) + " ";
    }

    private static boolean containsAny(String lower, List<String> patterns) {
        for (String p : patterns) {
            if (containsWord(lower, p)) return true;
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        return indexOfWord(text, word) >= 0;
    }

    /**
     * Position of {@code word} in {@code text} where it isn't glued to a letter
     * on either side ("spar" matches "Spar Riversdal" but not "Sparks"), or -1.
     */
    static int indexOfWord(String text, String word) {
        return indexOfWord(text, word, false);
    }

    /** @param mayRunOn the word may continue straight into the next one ("Liquorshop") */
    static int indexOfWord(String text, String word, boolean mayRunOn) {
        if (word.isEmpty()) return -1;
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) return -1;
            // Only letters count as glue: bank text often runs digits into names ("Spar2Uss").
            boolean startOk = !Character.isLetter(word.charAt(0))
                || i == 0 || !Character.isLetter(text.charAt(i - 1));
            int end = i + word.length();
            boolean endOk = mayRunOn || !Character.isLetter(word.charAt(word.length() - 1))
                || end >= text.length() || !Character.isLetter(text.charAt(end));
            if (startOk && endOk) return i;
            from = i + 1;
        }
    }
}
