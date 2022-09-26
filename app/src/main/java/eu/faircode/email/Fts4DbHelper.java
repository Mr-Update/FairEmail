package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2022 by Marcel Bokhorst (M66B)
*/

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.mail.Address;

// https://www.sqlite.org/fts3.html
// fts4 requires sqlite version 3.7.4, API 21 Android
public class Fts4DbHelper extends SQLiteOpenHelper {
    private Context context;

    @SuppressLint("StaticFieldLeak")
    private static Fts4DbHelper instance = null;

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "fts4.db";

    private Fts4DbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
    }

    static SQLiteDatabase getInstance(Context context) {
        if (instance == null) {
            if (!context.getDatabasePath(DATABASE_NAME).exists()) {
                Fts5DbHelper.delete(context);
                DB.getInstance(context).message().resetFts();
            }
            instance = new Fts4DbHelper(context);
        }
        return instance.getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.i("FTS create");
        db.execSQL("CREATE VIRTUAL TABLE `message`" +
                " USING fts4" +
                " (`account`" +
                ", `folder`" +
                ", `time`" +
                ", `address`" +
                ", `subject`" +
                ", `keyword`" +
                ", `text`" +
                ", `notes`" +
                ", notindexed=`account`" +
                ", notindexed=`folder`" +
                ", notindexed=`time`" +
                ", tokenize=unicode61 \"remove_diacritics=2\")");
        // https://www.sqlite.org/fts3.html#tokenizer
        // https://unicode.org/reports/tr29/

        // https://www.sqlite.org/fts3.html#fts4aux
        db.execSQL("CREATE VIRTUAL TABLE message_terms USING fts4aux('message');");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i("FTS upgrade from " + oldVersion + " to " + newVersion);

        db.execSQL("DROP TABLE IF EXISTS `message`");
        db.execSQL("DROP TABLE IF EXISTS `message_terms`");

        onCreate(db);

        DB.getInstance(context).message().resetFts();
    }

    static void insert(SQLiteDatabase db, EntityMessage message, String text) {
        Log.i("FTS insert id=" + message.id);
        List<Address> address = new ArrayList<>();
        if (message.from != null)
            address.addAll(Arrays.asList(message.from));
        if (message.to != null)
            address.addAll(Arrays.asList(message.to));
        if (message.cc != null)
            address.addAll(Arrays.asList(message.cc));
        if (message.bcc != null)
            address.addAll(Arrays.asList(message.bcc));

        delete(db, message.id);

        ContentValues cv = new ContentValues();
        cv.put("rowid", message.id);
        cv.put("account", message.account);
        cv.put("folder", message.folder);
        cv.put("time", message.received);
        cv.put("address", MessageHelper.formatAddresses(address.toArray(new Address[0]), true, false));
        cv.put("subject", message.subject == null ? "" : message.subject);
        cv.put("keyword", TextUtils.join(", ", message.keywords));
        cv.put("text", text);
        cv.put("notes", message.notes);
        db.insertWithOnConflict("message", null, cv, SQLiteDatabase.CONFLICT_FAIL);
    }

    static void delete(SQLiteDatabase db) {
        db.delete("message", null, null);
    }

    static void delete(SQLiteDatabase db, long id) {
        db.delete("message", "rowid = ?", new String[]{Long.toString(id)});
    }

    static List<String> getSuggestions(SQLiteDatabase db, String query, int max) {
        List<String> result = new ArrayList<>();

        try (Cursor cursor = db.rawQuery(
                "SELECT term FROM message_terms" +
                        " WHERE term LIKE ?" +
                        " GROUP BY term" +
                        " ORDER BY SUM(occurrences) DESC" +
                        " LIMIT " + max,
                new String[]{query})) {
            while (cursor != null && cursor.moveToNext())
                result.add(cursor.getString(0));
        }

        return result;
    }

    static List<Long> match(
            SQLiteDatabase db,
            Long account, Long folder, long[] exclude,
            BoundaryCallbackMessages.SearchCriteria criteria) {

        List<String> word = new ArrayList<>();
        List<String> plus = new ArrayList<>();
        List<String> minus = new ArrayList<>();
        List<String> opt = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (String w : criteria.query.trim().split("\\s+")) {
            if (all.length() > 0)
                all.append(' ');

            if (w.length() > 1 && w.startsWith("+")) {
                plus.add(w.substring(1));
                all.append(w.substring(1));
            } else if (w.length() > 1 && w.startsWith("-")) {
                minus.add(w.substring(1));
                all.append(w.substring(1));
            } else if (w.length() > 1 && w.startsWith("?")) {
                opt.add(w.substring(1));
                all.append(w.substring(1));
            } else {
                word.add(w);
                all.append(w);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (plus.size() + minus.size() + opt.size() > 0) {
            if (word.size() > 0)
                sb.append(escape(TextUtils.join(" ", word)));

            for (String p : plus) {
                if (sb.length() > 0)
                    sb.append(" AND ");
                sb.append(escape(p));
            }

            for (String m : minus) {
                if (sb.length() > 0)
                    sb.append(" NOT ");
                sb.append(escape(m));
            }

            if (sb.length() > 0) {
                sb.insert(0, '(');
                sb.append(')');
            }

            for (String o : opt) {
                if (sb.length() > 0)
                    sb.append(" OR ");
                sb.append(escape(o));
            }
        }

        String search = (sb.length() > 0 ? sb.toString() : escape(criteria.query));

        String select = "";
        if (account != null)
            select += "account = " + account + " AND ";
        if (folder != null)
            select += "folder = " + folder + " AND ";
        if (exclude.length > 0) {
            select += "NOT folder IN (";
            for (int i = 0; i < exclude.length; i++) {
                if (i > 0)
                    select += ", ";
                select += exclude[i];
            }
            select += ") AND ";
        }
        if (criteria.after != null)
            select += "time > " + criteria.after + " AND ";
        if (criteria.before != null)
            select += "time < " + criteria.before + " AND ";

        Log.i("FTS select=" + select + " search=" + search);
        List<Long> result = new ArrayList<>();
        try (Cursor cursor = db.query(
                "message", new String[]{"rowid"},
                select + "message MATCH ?",
                new String[]{search},
                null, null, "time DESC", null)) {
            while (cursor != null && cursor.moveToNext())
                result.add(cursor.getLong(0));
        }
        Log.i("FTS result=" + result.size());
        return result;
    }

    private static String escape(String word) {
        return "\"" + word.replaceAll("\"", "\"\"") + "\"";
    }

    static Cursor getIds(SQLiteDatabase db) {
        return db.query(
                "message", new String[]{"rowid"},
                null, null,
                null, null, "time");
    }

    static long size(Context context) {
        return context.getDatabasePath(DATABASE_NAME).length();
    }

    static void optimize(SQLiteDatabase db) {
        Log.i("FTS optimize");
        db.execSQL("INSERT INTO message (message) VALUES ('optimize')");
    }

    static void delete(Context context) {
        context.getDatabasePath(DATABASE_NAME).delete();
    }
}
