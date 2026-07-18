package com.example.healthmonitoringapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    // Database Constants
    private static final String DATABASE_NAME = "UserHealth.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_NAME = "user_profile";

    // Column Names
    public static final String COL_ID = "ID";
    public static final String COL_NAME = "NAME";
    public static final String COL_GENDER = "GENDER";
    public static final String COL_AGE = "AGE";
    public static final String COL_DETAILS = "DETAILS";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                COL_ID + " INTEGER PRIMARY KEY, " +
                COL_NAME + " TEXT, " +
                COL_GENDER + " TEXT, " +
                COL_AGE + " TEXT, " +
                COL_DETAILS + " TEXT)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    public void saveUser(String name, String gender, String age, String details) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_ID, 1);
        values.put(COL_NAME, name);
        values.put(COL_GENDER, gender);
        values.put(COL_AGE, age);
        values.put(COL_DETAILS, details);

        db.replace(TABLE_NAME, null, values);
        db.close(); // Good practice to close the database after writing
    }

    public Cursor getUserData() {
        SQLiteDatabase db = this.getReadableDatabase();
        return db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE " + COL_ID + " = 1", null);
    }

    public boolean hasData() {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE " + COL_ID + " = 1", null);
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }
}