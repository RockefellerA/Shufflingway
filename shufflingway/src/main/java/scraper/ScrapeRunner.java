package scraper;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Smoke test for the card scraper pipeline.
 *
 * Run this class directly (right-click → Run in VS Code) to verify:
 *   1. The Square Enix API is reachable and returns parseable JSON
 *   2. Card fields are mapped correctly
 *   3. SQLite round-trip works (write → read back)
 *   4. One image can be downloaded from the CDN
 *
 * Only the first page of Opus I is fetched (~60 cards) so it completes quickly.
 * The database is written to shufflingway_test.db in the working directory.
 */
public class ScrapeRunner {

    private static final String TEST_SET    = "1";   // Opus I
    private static final String TEST_DB     = "shufflingway_test.db";
    private static final int    CARDS_TO_PRINT = 5;

    public static void main(String[] args) {

        System.out.println("=== FFTCG Scraper Smoke Test ===\n");

        // ── Step 0: print raw JSON of one card to verify field names ─────────
        System.out.println("[0] Raw JSON of first card (field name reference):");
        try {
            CardScraper scraper = new CardScraper();
            System.out.println(scraper.fetchRawFirstCard(TEST_SET));
        } catch (IOException | InterruptedException e) {
            System.err.println("FAIL - could not fetch raw card:");
            e.printStackTrace();
            return;
        }
        System.out.println();

        // ── Step 1: fetch one page from the API ───────────────────────────────
        System.out.println("[1] Fetching first page of Opus " + TEST_SET + " from API…");
        List<ScrapedCard> cards;
        try {
            CardScraper scraper = new CardScraper();
            cards = scraper.scrapeOnePage(TEST_SET);
        } catch (IOException | InterruptedException e) {
            System.err.println("FAIL - API request threw an exception:");
            e.printStackTrace();
            return;
        }

        if (cards.isEmpty()) {
            System.err.println("FAIL - API returned 0 cards. Check the request body in CardScraper.");
            return;
        }
        System.out.println("  OK  – received " + cards.size() + " cards\n");

        // ── Sanity check: warn if serials are empty (field name mismatch) ─────
        long blankSerials = cards.stream().filter(c -> c.serial == null || c.serial.isBlank()).count();
        if (blankSerials > 0) {
            System.err.printf("WARN - %d / %d cards have a blank serial. " +
                    "The 'Serial' field name in CardScraper.parseCard() is probably wrong — " +
                    "check the raw JSON printed in step [0] above.%n%n", blankSerials, cards.size());
        }

        // ── Step 2: print a sample ────────────────────────────────────────────
        System.out.println("[2] Sample cards:");
        cards.stream().limit(CARDS_TO_PRINT).forEach(c ->
                System.out.printf("  %-8s  %-22s  %-8s  %-10s  cost=%-2d  power=%s%n",
                        c.serial, c.nameEn, c.typeEn, c.element,
                        c.cost, c.power != null ? c.power.toString() : "-"));
        System.out.println();

        // ── Step 3: SQLite round-trip ─────────────────────────────────────────
        System.out.println("[3] Saving to " + TEST_DB + " and reading back…");
        try (CardDatabase db = new CardDatabase(TEST_DB)) {
            db.saveCards(cards);

            List<String> savedSerials = cards.stream()
                    .filter(c -> c.serial != null && !c.serial.isBlank())
                    .map(c -> c.serial)
                    .distinct()
                    .toList();
            int readBack = 0;
            for (String serial : savedSerials) {
                if (db.getCard(serial) != null) readBack++;
            }
            if (readBack != savedSerials.size()) {
                System.err.printf("FAIL - wrote %d cards but read back %d%n",
                        savedSerials.size(), readBack);
                return;
            }
            System.out.println("  OK  - " + readBack + " cards round-tripped correctly\n");
        } catch (SQLException e) {
            System.err.println("FAIL - database error:");
            e.printStackTrace();
            return;
        }

        // ── Step 4: image download ────────────────────────────────────────────
        ScrapedCard sample = cards.stream()
                .filter(c -> c.imageUrl != null && !c.imageUrl.isBlank())
                .findFirst()
                .orElse(null);

        if (sample == null) {
            System.out.println("[4] SKIP - no imageUrl found on any card (check images.full field mapping)");
        } else {
            System.out.println("[4] Downloading image for " + sample.serial + ": " + sample.imageUrl);
            try {
                CardScraper scraper = new CardScraper();
                byte[] img = scraper.downloadImage(sample.imageUrl);
                System.out.printf("  OK  - downloaded %,d bytes%n%n", img.length);
            } catch (IOException | InterruptedException e) {
                System.err.println("FAIL - image download error:");
                e.printStackTrace();
                return;
            }
        }

        System.out.println("=== All checks passed ===");
    }
}
