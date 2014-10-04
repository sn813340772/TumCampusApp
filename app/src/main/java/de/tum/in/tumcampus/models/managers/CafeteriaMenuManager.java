package de.tum.in.tumcampus.models.managers;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.tum.in.tumcampus.auxiliary.NetUtils;
import de.tum.in.tumcampus.auxiliary.Utils;
import de.tum.in.tumcampus.models.CafeteriaMenu;

/**
 * Cafeteria Menu Manager, handles database stuff, external imports
 */
public class CafeteriaMenuManager {
	/**
	 * Last insert counter
	 */
	public static int lastInserted = 0;

	private static final int TIME_TO_SYNC = 86400; // 1 day

	/**
	 * Convert JSON object to CafeteriaMenu
	 * 
	 * Example JSON: e.g.
	 * {"id":"25544","mensa_id":"411","date":"2011-06-20","type_short"
	 * :"tg","type_long":"Tagesgericht 3","type_nr":"3","name":
	 * "Cordon bleu vom Schwein (mit Formfleischhinterschinken) (S) (1,2,3,8)"}
	 *
	 * @param json see above
	 * @return CafeteriaMenu
	 * @throws Exception
	 */
	private static CafeteriaMenu getFromJson(JSONObject json) throws Exception {

		return new CafeteriaMenu(json.getInt("id"), json.getInt("mensa_id"),
				Utils.getDate(json.getString("date")),
				json.getString("type_short"), json.getString("type_long"),
				json.getInt("type_nr"), json.getString("name"));
	}

	/**
	 * Convert JSON object to CafeteriaMenu (addendum)
	 * 
	 * Example JSON: e.g.
	 * {"mensa_id":"411","date":"2011-07-29","name":"Pflaumenkompott"
	 * ,"type_short":"bei","type_long":"Beilagen"}
	 *
	 * @param json see above
	 * @return CafeteriaMenu
	 * @throws Exception
	 */
	private static CafeteriaMenu getFromJsonAddendum(JSONObject json)
			throws Exception {

		return new CafeteriaMenu(0, json.getInt("mensa_id"), Utils.getDate(json
				.getString("date")), json.getString("type_short"),
				json.getString("type_long"), 10, json.getString("name"));
	}

	/**
	 * Database connection
	 */
	private final SQLiteDatabase db;

	/**
	 * Constructor, open/create database, create table if necessary
	 *
	 * @param context Context
	 */
	public CafeteriaMenuManager(Context context) {
		db = DatabaseManager.getDb(context);

		// create table if needed
		db.execSQL("CREATE TABLE IF NOT EXISTS cafeterias_menus ("
				+ "id INTEGER, mensaId INTEGER KEY, date VARCHAR, typeShort VARCHAR, "
				+ "typeLong VARCHAR, typeNr INTEGER, name VARCHAR)");
	}

	/**
	 * Removes all old items (older than 7 days)
	 */
    void cleanupDb() {
		db.execSQL("DELETE FROM cafeterias_menus WHERE date < date('now','-7 day')");
	}

	/**
	 * Download cafeteria menus from external interface (JSON)
	 *
	 * @param force True to force download over normal sync period, else false
	 * @throws Exception
	 */
	public void downloadFromExternal(Context context, boolean force) throws Exception {

		if (!force && !SyncManager.needSync(db, this, TIME_TO_SYNC)) {
			return;
		}
		cleanupDb();
		int count = Utils.dbGetTableCount(db, "cafeterias_menus");

		Cursor c = db
				.rawQuery(
						"SELECT 1 FROM cafeterias_menus WHERE date > date('now', '+6 day') LIMIT 1",
						null);
		if (c.getCount() > 0) {
			c.close();
			return;
		}
		c.close();

		String url = "http://lu32kap.typo3.lrz.de/mensaapp/exportDB.php?mensa_id=all";
		JSONObject json = NetUtils.downloadJson(context, url);

		removeCache();
		db.beginTransaction();
		try {
			JSONArray menu = json.getJSONArray("mensa_menu");
			for (int j = 0; j < menu.length(); j++) {
				replaceIntoDb(getFromJson(menu.getJSONObject(j)));
			}

			JSONArray beilagen = json.getJSONArray("mensa_beilagen");
			for (int j = 0; j < beilagen.length(); j++) {
				replaceIntoDb(getFromJsonAddendum(beilagen.getJSONObject(j)));
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
		SyncManager.replaceIntoDb(db, this);

		// update last insert counter
		lastInserted += Utils.dbGetTableCount(db, "cafeterias_menus") - count;
	}

	/**
	 * Get all distinct menu dates from the database
	 * 
	 * @return Database cursor (date_de, _id)
	 */
	public Cursor getDatesFromDb() {
		return db.rawQuery("SELECT DISTINCT strftime('%d.%m.%Y', date) as date_de, date as _id "
				+ "FROM cafeterias_menus WHERE date >= date('now','localtime') ORDER BY date", null);
	}

	/**
	 * Get all types and names from the database for a special date and a special cafeteria
	 *
	 * @param mensaId Mensa ID, e.g. 411
	 * @param date ISO-Date, e.g. 2011-12-31
	 * @return Database cursor (typeLong, names, _id, typeShort)
	 */
    public Cursor getTypeNameFromDbCard(int mensaId, String date) {
        return db.rawQuery("SELECT typeLong, group_concat(name, '\n') as names, id as _id, typeShort "
                                + "FROM cafeterias_menus WHERE mensaId = ? AND "
                                + "date = ? GROUP BY typeLong ORDER BY typeShort=\"tg\" DESC, typeShort ASC, typeNr",
                        new String[] { ""+mensaId, date });
    }

    /**
     * Get all types and names from the database for a special date and a special cafeteria
     *
     * @param mensaId Mensa ID, e.g. 411
     * @param dateStr ISO-Date, e.g. 2011-12-31
     * @param date Date
     * @return List of cafeteria menus
     */
    public List<CafeteriaMenu> getTypeNameFromDbCardList(int mensaId, String dateStr, Date date) {
        Cursor cursorCafeteriaMenu = getTypeNameFromDbCard(mensaId, dateStr);
        ArrayList<CafeteriaMenu> menus = new ArrayList<CafeteriaMenu>();
        if(cursorCafeteriaMenu.moveToFirst()) {
            do {
                CafeteriaMenu menu = new CafeteriaMenu(Integer.parseInt(cursorCafeteriaMenu.getString(2)),
                        mensaId, date, cursorCafeteriaMenu.getString(3), cursorCafeteriaMenu.getString(0),
                        0, cursorCafeteriaMenu.getString(1));
                menus.add(menu);
            } while (cursorCafeteriaMenu.moveToNext());
        }
        cursorCafeteriaMenu.close();
        return menus;
    }

	/**
	 * Removes all cache items
	 */
	public void removeCache() {
		db.execSQL("DELETE FROM cafeterias_menus");
	}

	/**
	 * Replace or Insert a cafeteria menu in the database
	 *
	 * @param c CafeteriaMenu object
	 * @throws Exception
	 */
    void replaceIntoDb(CafeteriaMenu c) throws Exception {
		if (c.cafeteriaId <= 0) {
			throw new Exception("Invalid cafeteriaId.");
		}
		if (c.name.length() == 0) {
			throw new Exception("Invalid name.");
		}
		if (c.typeLong.length() == 0) {
			throw new Exception("Invalid typeLong.");
		}
		if (c.typeShort.length() == 0) {
			throw new Exception("Invalid typeShort.");
		}
		if (c.date.before(Utils.getDate("2012-01-01"))) {
			throw new Exception("Invalid date.");
		}
		db.execSQL("REPLACE INTO cafeterias_menus (id, mensaId, date, typeShort, "
						+ "typeLong, typeNr, name) VALUES (?, ?, ?, ?, ?, ?, ?)",
				new String[] { String.valueOf(c.id),
						String.valueOf(c.cafeteriaId),
						Utils.getDateString(c.date), c.typeShort, c.typeLong,
						String.valueOf(c.typeNr), c.name });
	}
}