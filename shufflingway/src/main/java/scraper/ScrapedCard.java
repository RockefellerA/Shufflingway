package scraper;

/**
 * Represents raw card metadata scraped from the Square Enix FFTCG card browser API.
 * Fields map directly to the API response; no game-logic is applied here.
 */
public class ScrapedCard {

    public String serial;       // e.g. "1-001H"
    public String nameEn;
    public String typeEn;       // "Forward", "Backup", "Summon", "Monster"
    public String element;      // "Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark"
    public int    cost;
    public Integer power;       // null for Backups and Summons
    public String rarity;       // "C", "R", "H", "L", "S", "P"
    public String jobEn;
    public String category1;    // primary game category, e.g. "VII"
    public String category2;    // secondary game category, or empty
    public boolean exBurst;
    public boolean multicard;
    public String textEn;       // card ability text
    public String imageUrl;     // full CDN URL

    @Override
    public String toString() {
        return serial + " – " + nameEn + " [" + typeEn + "/" + element + "/" + rarity + "]";
    }
}
