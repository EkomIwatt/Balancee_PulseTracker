package com.example.balancee_pulsetracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "PulseDB", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE PulseTable (id INTEGER PRIMARY KEY, count INTEGER)")
        db.execSQL("INSERT INTO PulseTable (id, count) VALUES (1, 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun saveCount(count: Int) {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("count", count) }
        db.update("PulseTable", values, "id=?", arrayOf("1"))
    }

    fun getLastCount(): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT count FROM PulseTable WHERE id=1", null)
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }
}