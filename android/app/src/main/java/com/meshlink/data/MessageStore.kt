package com.meshlink.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local SQLite store for offline message history. */
class MessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PEER_ID TEXT NOT NULL,
                $COL_PEER_NAME TEXT NOT NULL,
                $COL_BODY TEXT NOT NULL,
                $COL_SENT_BY_ME INTEGER NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_peer ON $TABLE($COL_PEER_ID, $COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(message: ChatMessage): ChatMessage {
        val values = ContentValues().apply {
            put(COL_PEER_ID, message.peerId)
            put(COL_PEER_NAME, message.peerName)
            put(COL_BODY, message.body)
            put(COL_SENT_BY_ME, if (message.sentByMe) 1 else 0)
            put(COL_TIMESTAMP, message.timestampMs)
        }
        val id = writableDatabase.insert(TABLE, null, values)
        return message.copy(id = id)
    }

    fun messagesForPeer(peerId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_PEER_ID, COL_PEER_NAME, COL_BODY, COL_SENT_BY_ME, COL_TIMESTAMP),
            "$COL_PEER_ID = ?",
            arrayOf(peerId),
            null,
            null,
            "$COL_TIMESTAMP ASC"
        ).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
            val peerIdIdx = cursor.getColumnIndexOrThrow(COL_PEER_ID)
            val peerNameIdx = cursor.getColumnIndexOrThrow(COL_PEER_NAME)
            val bodyIdx = cursor.getColumnIndexOrThrow(COL_BODY)
            val sentIdx = cursor.getColumnIndexOrThrow(COL_SENT_BY_ME)
            val timeIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            while (cursor.moveToNext()) {
                messages.add(
                    ChatMessage(
                        id = cursor.getLong(idIdx),
                        peerId = cursor.getString(peerIdIdx),
                        peerName = cursor.getString(peerNameIdx),
                        body = cursor.getString(bodyIdx),
                        sentByMe = cursor.getInt(sentIdx) == 1,
                        timestampMs = cursor.getLong(timeIdx)
                    )
                )
            }
        }
        return messages
    }

    fun clearAllMessages() {
        writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val DB_NAME = "meshlink_messages.db"
        private const val DB_VERSION = 1
        private const val TABLE = "messages"
        private const val COL_ID = "id"
        private const val COL_PEER_ID = "peer_id"
        private const val COL_PEER_NAME = "peer_name"
        private const val COL_BODY = "body"
        private const val COL_SENT_BY_ME = "sent_by_me"
        private const val COL_TIMESTAMP = "timestamp_ms"
    }
}
