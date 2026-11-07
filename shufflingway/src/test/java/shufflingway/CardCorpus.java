package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads every card in {@code shufflingway.db} as {@link CardData}, in a stable order.
 *
 * <p>The database is not checked in (see {@code .gitignore}), so callers must handle its
 * absence — {@link #load()} returns an empty list rather than failing, and tests that need
 * the corpus skip themselves when it comes back empty.
 */
final class CardCorpus {

	/** One card plus the serial it was loaded under, which CardData itself does not carry. */
	record Entry(String serial, CardData card) {}

	private static final String COLUMNS =
			"SELECT serial, name_en, element, cost, power, type_en, ex_burst, multicard, " +
			"limit_break, lb_cost, image_url, text_en, job_en, category_1, category_2 FROM cards";

	private static final String SELECT_ALL = COLUMNS + " ORDER BY serial";

	private static final String SELECT_ONE = COLUMNS + " WHERE serial = ?";

	private CardCorpus() {}

	/** Absolute path of the card database, whether or not it exists. */
	static File dbFile() {
		return new File("shufflingway.db").getAbsoluteFile();
	}

	/** Every card with non-blank text, ordered by serial; empty when the database is absent. */
	static List<Entry> load() throws Exception {
		List<Entry> out = new ArrayList<>();
		File db = dbFile();
		if (!db.exists()) return out;
		try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
		     Statement st = conn.createStatement();
		     ResultSet rs = st.executeQuery(SELECT_ALL)) {
			while (rs.next()) {
				String textEn = rs.getString("text_en");
				if (textEn == null || textEn.isBlank()) continue;
				out.add(new Entry(rs.getString("serial"), build(rs, textEn)));
			}
		}
		return out;
	}

	/**
	 * The card printed as {@code serial}, built exactly as {@link #load()} builds it; {@code null}
	 * when the database is absent or has no such card. For a test that needs one printed record:
	 * the whole corpus takes seconds to parse, and this reads a single row.
	 */
	static CardData bySerial(String serial) throws Exception {
		File db = dbFile();
		if (!db.exists()) return null;
		try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
		     PreparedStatement st = conn.prepareStatement(SELECT_ONE)) {
			st.setString(1, serial);
			try (ResultSet rs = st.executeQuery()) {
				if (!rs.next()) return null;
				String textEn = rs.getString("text_en");
				return build(rs, textEn == null ? "" : textEn);
			}
		}
	}

	private static CardData build(ResultSet rs, String textEn) throws Exception {
		String typeEn = rs.getString("type_en");
		String nameEn = rs.getString("name_en");
		return new CardData(
				rs.getString("image_url"),
				nameEn,
				rs.getString("element"),
				rs.getInt("cost"),
				rs.getInt("power"),
				typeEn,
				rs.getInt("limit_break") != 0,
				rs.getObject("lb_cost") != null ? rs.getInt("lb_cost") : 0,
				rs.getInt("ex_burst") != 0,
				rs.getInt("multicard") != 0,
				CardData.parseTraits(textEn, nameEn),
				CardData.parseWarpValue(textEn),
				CardData.parseWarpCost(textEn),
				CardData.parsePrimingTarget(textEn),
				CardData.parsePrimingCost(textEn),
				CardData.parseActionAbilities(textEn),
				CardData.parseAutoAbilities(textEn),
				CardData.parseFieldAbilities(textEn, typeEn),
				CardData.parseIfControlBoosts(textEn, typeEn),
				CardData.parseFieldPowerGrants(textEn, typeEn, nameEn),
				CardData.parseScalingSelfPowerBoosts(textEn, typeEn, nameEn),
				CardData.parseFieldCostReductions(textEn, typeEn),
				CardData.parseSelfCostModifiers(textEn),
				CardData.parseFieldPrimingAnyElements(textEn, typeEn),
				CardData.parseFieldPartyAnyElements(textEn, typeEn),
				CardData.parseWarpCostAnyElement(textEn),
				CardData.parseCanFormPartyAnyElement(textEn),
				CardData.parseFieldCannotBeBlockedByCost(textEn, nameEn),
				CardData.parseCannotBeBlockedByHigherPower(textEn, nameEn),
				CardData.parseCannotBlockAtAll(textEn, nameEn),
				CardData.parseCannotBlockHigherPower(textEn, nameEn),
				CardData.parseCannotBlockParty(textEn, nameEn),
				CardData.parseCannotAttackOrBlock(textEn, nameEn),
				CardData.parseMaxAttacksPerTurn(textEn, nameEn),
				rs.getString("job_en"),
				rs.getString("category_1"),
				rs.getString("category_2"),
				textEn);
	}
}
