package scraper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fetches card metadata from the official Square Enix FFTCG card browser API.
 *
 * Usage:
 *   CardScraper scraper = new CardScraper();
 *   List<ScrapedCard> opus1 = scraper.scrapeSet("1");   // Opus I
 *   List<ScrapedCard> all   = scraper.scrapeAllSets(1, 22);
 *
 * NOTE: If any field names look wrong, open the card browser in your browser's
 * DevTools → Network tab, trigger a search, and inspect the POST payload/response
 * to confirm the exact field names used by the current API version.
 */
public class CardScraper {

    private static final Map<String, String> TRANSLATIONS = Map.of(
            "火", "Fire",
            "氷", "Ice",
            "風", "Wind",
            "雷", "Lightning",
            "土", "Earth",
            "水", "Water",
            "光", "Light",
            "闇", "Dark",
            "ダル", "Dull"
    );

    private static final String API_URL =
            "https://fftcg.square-enix-games.com/na/get-cards";
    private static final String IMAGE_BASE_URL =
            "https://fftcg.cdn.sewest.net/images/cards/full/";

    /** Cards returned per API page. The SE API accepts up to ~60. */
    private static final int PAGE_SIZE = 60;

    /** Polite delay between pages so we don't hammer the server (ms). */
    private static final long DELAY_MS = 500;

    private final HttpClient http;

    public CardScraper() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches only the first page of a set — used for smoke-testing the pipeline
     * without waiting for a full scrape.
     */
    public List<ScrapedCard> scrapeOnePage(String setNumber) throws IOException, InterruptedException {
        String body = buildRequestBody(setNumber, 0, PAGE_SIZE);
        JSONObject json = post(body);
        JSONArray cards = json.optJSONArray("cards");
        List<ScrapedCard> result = new ArrayList<>();
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                result.add(parseCard(cards.getJSONObject(i), setNumber));
            }
        }
        return result;
    }

    /**
     * Returns a pretty-printed JSON string of the first card from the API.
     * Use this to inspect actual field names when the mapping looks wrong.
     */
    public String fetchRawFirstCard(String setNumber) throws IOException, InterruptedException {
        String body = buildRequestBody(setNumber, 0, 1);
        JSONObject json = post(body);
        JSONArray cards = json.optJSONArray("cards");
        if (cards != null && !cards.isEmpty()) {
            return cards.getJSONObject(0).toString(2);
        }
        return "(no cards returned — check top-level response keys: " + json.keySet() + ")";
    }

    /**
     * Scrapes all cards for a single set / Opus number.
     *
     * @param setNumber Opus number as a string, e.g. "1" for Opus I, "22" for Opus XXII.
     *                  Use "PR" or "PROMO" for promotional cards (verify exact value).
     */
    public List<ScrapedCard> scrapeSet(String setNumber) throws IOException, InterruptedException {
        List<ScrapedCard> result = new ArrayList<>();
        int searchStart = 0;
        int totalCount  = Integer.MAX_VALUE;

        System.out.printf("Scraping set %s…%n", setNumber);

        while (searchStart < totalCount) {
            String body = buildRequestBody(setNumber, searchStart, PAGE_SIZE);
            JSONObject json = post(body);

            totalCount = json.optInt("count", 0);
            JSONArray cards = json.optJSONArray("cards");

            if (cards == null || cards.isEmpty()) break;

            for (int i = 0; i < cards.length(); i++) {
                result.add(parseCard(cards.getJSONObject(i), setNumber));
            }

            System.out.printf("  … %d / %d%n", result.size(), totalCount);
            searchStart += PAGE_SIZE;

            if (searchStart < totalCount) {
                Thread.sleep(DELAY_MS);
            }
        }

        return result;
    }

    /**
     * Scrapes a consecutive range of Opus sets (inclusive on both ends).
     *
     * @param firstOpus e.g. 1
     * @param lastOpus  e.g. 22
     */
    public List<ScrapedCard> scrapeAllSets(int firstOpus, int lastOpus)
            throws IOException, InterruptedException {
        List<ScrapedCard> all = new ArrayList<>();
        for (int i = firstOpus; i <= lastOpus; i++) {
            all.addAll(scrapeSet(String.valueOf(i)));
        }
        return all;
    }

    /**
     * Scrapes all cards and saves them to the database.
     * Suitable for initial population or incremental updates.
     *
     * @param db        open {@link CardDatabase} to write into
     * @return total number of cards saved across all sets
     */
    public int scrapeAndSave(CardDatabase db)
            throws IOException, InterruptedException, java.sql.SQLException {
        int total = 0;
        List<ScrapedCard> cards = scrapeSet("1");
            db.saveCards(cards);
            long saved = cards.stream()
                    .filter(c -> c.serial != null && !c.serial.isBlank())
                    .map(c -> c.serial)
                    .distinct()
                    .count();
            System.out.printf("Saved %d cards%n", saved);
            total += (int) saved;
        return total;
    }

    /**
     * Downloads the raw bytes of a card image from the CDN.
     * Store the bytes in SQLite (BLOB) or write them to disk as you prefer.
     */
    public byte[] downloadImage(String imageUrl) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() != 200) {
            throw new IOException("Image download failed (HTTP " + resp.statusCode() + "): " + imageUrl);
        }
        return resp.body();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private JSONObject post(String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // Identify ourselves honestly
                .header("User-Agent", "FFTCG-Card-Scraper/1.0 (personal project)")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IOException("API error (HTTP " + resp.statusCode() + "): " + resp.body());
        }
        return new JSONObject(resp.body());
    }

    private String buildRequestBody(String setNumber, int searchStart, int count) {
        // Outer sort array: [{"sort": "Serial", "order": "asc"}]
        JSONArray sort = new JSONArray()
                .put(new JSONObject().put("sort", "Serial").put("order", "asc"));

        return new JSONObject()
                .put("language",     "en")
                .put("text",         "")
                .put("setNum",       setNumber)
                .put("type",         new JSONArray())
                .put("element",      new JSONArray())
                .put("cost",         new JSONArray())
                .put("rarity",       new JSONArray())
                .put("power",        new JSONArray())
                .put("category",     new JSONArray())
                .put("multicard",    false)
                .put("ex_burst",     false)
                .put("search_start", searchStart)
                .put("count",        count)
                .put("sort",         sort)
                .toString();
    }

    private ScrapedCard parseCard(JSONObject j, String setNumber) {
        ScrapedCard c = new ScrapedCard();

        c.serial    = j.optString("code",       "");
        c.nameEn    = j.optString("name_en",    "");
        c.typeEn    = j.optString("type_en",    "");
        c.rarity    = j.optString("rarity",     "");
        c.jobEn     = j.optString("job_en",     "");
        String cat1 = j.optString("category_1", "");
        int middot = cat1.indexOf("&middot;");
        c.category1 = (middot >= 0 ? cat1.substring(0, middot) : cat1).trim();
        c.category2 = j.optString("category_2", "");
        String rawTextEn = j.optString("text_en", "");
        for (Map.Entry<String, String> entry : TRANSLATIONS.entrySet())
            rawTextEn = rawTextEn.replace(entry.getKey(), entry.getValue());
        c.textEn = rawTextEn;
        c.setNumber = setNumber;

        // element is an array, e.g. ["Fire"] or ["火", "水"] for dual-element cards.
        // The API may return Japanese kanji — translate to English here.
        JSONArray elementArr = j.optJSONArray("element");
        if (elementArr != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < elementArr.length(); i++) {
                if (i > 0) sb.append("/");
                sb.append(TRANSLATIONS.getOrDefault(elementArr.optString(i), elementArr.optString(i)));
            }
            c.element = sb.toString();
        }

        // ex_burst and multicard arrive as "0" / "1" strings.
        // Fall back to checking the ability text for the [[ex]] markup because the API
        // occasionally returns ex_burst=0 for genuine EX Burst cards.
        c.exBurst   = "1".equals(j.optString("ex_burst",  "0"))
                   || c.textEn.contains("[[ex]]");
        c.multicard = "1".equals(j.optString("multicard", "0"));

        // cost and power arrive as strings
        try { c.cost = Integer.parseInt(j.optString("cost", "0").trim()); }
        catch (NumberFormatException ignored) {}

        String powerStr = j.optString("power", "");
        if (!powerStr.isBlank()) {
            try { c.power = Integer.parseInt(powerStr.trim()); }
            catch (NumberFormatException ignored) {}
        }

        // image URLs come from images.full[0] and images.thumbs[0]
        JSONObject images = j.optJSONObject("images");
        if (images != null) {
            JSONArray full = images.optJSONArray("full");
            if (full != null && !full.isEmpty()) {
                c.imageUrl = full.optString(0);
            }
            JSONArray thumbs = images.optJSONArray("thumbs");
            if (thumbs != null && !thumbs.isEmpty()) {
                String thumbUrl = thumbs.optString(0);
                c.thumbName = thumbUrl.substring(thumbUrl.lastIndexOf('/') + 1);
            }
        }

        return c;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        try (CardDatabase db = new CardDatabase(AppPaths.dbPath())) {
            CardScraper scraper = new CardScraper();
            List<ScrapedCard> total = scraper.scrapeOnePage("1");
            db.saveCards(total);
        } catch (SQLException e) {
            System.err.println("FAIL - database error:");
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
