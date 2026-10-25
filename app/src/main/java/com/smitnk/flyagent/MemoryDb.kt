package com.smitnk.flyagent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** On-device persistent memory: episodes (episodic), facts/feedback (semantic), skills (procedural, versioned). */
class MemoryDb(ctx: Context) : SQLiteOpenHelper(ctx, "flyagent.db", null, 3), Store {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE episodes(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, task TEXT, steps TEXT, outcome TEXT, success INTEGER, controller TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, content TEXT, source TEXT, confidence REAL)")
        createSkills(db); createPages(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) { db.execSQL("ALTER TABLE episodes ADD COLUMN controller TEXT DEFAULT ''"); createSkills(db) }
        if (o < 3) createPages(db)
    }
    private fun createPages(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pages(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, title TEXT, body TEXT)")
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, title TEXT, dot TEXT)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, chat INTEGER, role TEXT, text TEXT, episode INTEGER, cloud INTEGER)")
    }

    override fun savePage(title: String, body: String, id: Long): Long {
        val v = ContentValues().apply { put("ts", System.currentTimeMillis()); put("title", title); put("body", body) }
        return if (id > 0) { writableDatabase.update("pages", v, "id=?", arrayOf(id.toString())); id } else writableDatabase.insert("pages", null, v)
    }
    override fun pages() = readableDatabase.rawQuery("SELECT id,ts,title,body FROM pages ORDER BY ts DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Page(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) } }
    override fun deletePage(id: Long) { writableDatabase.delete("pages", "id=?", arrayOf(id.toString())) }

    // chat history (conversation list in the drawer)
    data class Chat(val id: Long, val ts: Long, val title: String, val dot: String)
    data class Msg(val role: String, val text: String, val episode: Long, val cloud: Boolean)
    fun newChat(title: String, dot: String) = writableDatabase.insert("chats", null, ContentValues().apply {
        put("ts", System.currentTimeMillis()); put("title", title.take(60)); put("dot", dot) })
    fun chats() = readableDatabase.rawQuery("SELECT id,ts,title,dot FROM chats ORDER BY ts DESC LIMIT 100", null).use { c ->
        buildList { while (c.moveToNext()) add(Chat(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))) } }
    fun deleteChat(id: Long) { writableDatabase.delete("messages", "chat=?", arrayOf(id.toString())); writableDatabase.delete("chats", "id=?", arrayOf(id.toString())) }
    fun addMessage(chat: Long, role: String, text: String, episode: Long = -1, cloud: Boolean = false) {
        writableDatabase.insert("messages", null, ContentValues().apply { put("chat", chat); put("role", role); put("text", text); put("episode", episode); put("cloud", if (cloud) 1 else 0) })
        writableDatabase.execSQL("UPDATE chats SET ts=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), chat))
    }
    fun messages(chat: Long) = readableDatabase.rawQuery("SELECT role,text,episode,cloud FROM messages WHERE chat=? ORDER BY id", arrayOf(chat.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Msg(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3) == 1)) } }
    private fun createSkills(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE skills(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, name TEXT, trigger TEXT, steps TEXT, version INTEGER, status TEXT)")

    override fun addEpisode(task: String, steps: String, outcome: String, success: Boolean, controller: String) =
        writableDatabase.insert("episodes", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("task", task); put("steps", steps)
            put("outcome", outcome); put("success", if (success) 1 else 0); put("controller", controller)
        })

    private fun ep(c: Cursor) = Episode(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) == 1, c.getString(6) ?: "")
    private val cols = "id,ts,task,steps,outcome,success,controller"
    override fun episode(id: Long) = readableDatabase.rawQuery("SELECT $cols FROM episodes WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) ep(it) else null }
    override fun recent(n: Int) = readableDatabase.rawQuery("SELECT $cols FROM episodes ORDER BY id DESC LIMIT ?", arrayOf(n.toString())).use { c -> buildList { while (c.moveToNext()) add(ep(c)) } }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM episodes", null).use { it.moveToFirst(); it.getInt(0) }

    override fun addFact(content: String, source: String, confidence: Double) = writableDatabase.insert("facts", null, ContentValues().apply {
        put("ts", System.currentTimeMillis()); put("content", content); put("source", source); put("confidence", confidence)
    })
    override fun facts() = readableDatabase.rawQuery("SELECT content FROM facts ORDER BY id DESC LIMIT 50", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    override fun saveSkill(name: String, trigger: String, steps: String, status: String): Skill {
        val v = readableDatabase.rawQuery("SELECT MAX(version) FROM skills WHERE name=?", arrayOf(name)).use { it.moveToFirst(); it.getInt(0) } + 1
        val id = writableDatabase.insert("skills", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("name", name); put("trigger", trigger); put("steps", steps); put("version", v); put("status", status)
        })
        return Skill(id, name, trigger, steps, v, status)
    }
    // latest version of each skill only
    override fun skills() = readableDatabase.rawQuery(
        "SELECT id,name,trigger,steps,version,status FROM skills s WHERE version=(SELECT MAX(version) FROM skills WHERE name=s.name)", null
    ).use { c -> buildList { while (c.moveToNext()) add(Skill(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5))) } }
}
