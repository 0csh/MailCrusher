package com.mailcrusher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class HistoryDb extends SQLiteOpenHelper {
    public HistoryDb(Context c) { super(c, "mailcrusher.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sent_history (id INTEGER PRIMARY KEY AUTOINCREMENT, recipient TEXT NOT NULL, subject TEXT NOT NULL, sent_at INTEGER NOT NULL, status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE accounts (email TEXT PRIMARY KEY, added_at INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void addAccount(String email) {
        ContentValues v = new ContentValues();
        v.put("email", email); v.put("added_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("accounts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<String> accounts() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT email FROM accounts ORDER BY added_at DESC", null)) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return result;
    }

    public void removeAccount(String email) {
        getWritableDatabase().delete("accounts", "email=?", new String[]{email});
    }

    public void addHistory(String recipient, String subject, String status) {
        ContentValues v = new ContentValues();
        v.put("recipient", recipient); v.put("subject", subject);
        v.put("sent_at", System.currentTimeMillis()); v.put("status", status);
        getWritableDatabase().insert("sent_history", null, v);
    }

    public List<String> history() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT recipient, subject, sent_at, status FROM sent_history ORDER BY sent_at DESC LIMIT 100", null)) {
            while (c.moveToNext()) {
                result.add(c.getString(0) + " • " + c.getString(1) + " • " +
                        android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", c.getLong(2)) +
                        " • " + c.getString(3));
            }
        }
        return result;
    }
}
