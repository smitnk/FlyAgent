package com.smitnk.flyagent

import java.text.DateFormat
import java.util.Date

/** Built-in replies so basic chat works offline even before a local model is installed. */
object SmallTalk {
    fun reply(text: String): String? {
        val t = text.lowercase().trim().trimEnd('?', '!', '.')
        Regex("""^(?:what is |calculate |)\s*(-?\d+(?:\.\d+)?)\s*([+\-*/x×])\s*(-?\d+(?:\.\d+)?)$""").find(t)?.let { m ->
            val (a, op, b) = m.destructured; val x = a.toDouble(); val y = b.toDouble()
            val r = when (op) { "+" -> x + y; "-" -> x - y; "/" -> if (y == 0.0) return "Can't divide by zero."; else x / y; else -> x * y }
            return if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toString()
        }
        return when {
            t in setOf("hi", "hello", "hey", "hii", "namaste", "namaskar", "yo", "hola") || t.startsWith("good morning") || t.startsWith("good evening") ->
                "Hi! I'm FlyAgent. How can I help you today?"
            t.contains("how are you") -> "I'm running well, thanks! What would you like to do?"
            t.contains("who are you") || t.contains("your name") || t.contains("what are you") ->
                "I'm FlyAgent, an AI assistant that runs on your phone. I can chat, search the web, read images, save files and learn skills from your feedback."
            t in setOf("thanks", "thank you", "thx", "ok thanks", "dhanyavad") -> "You're welcome!"
            t in setOf("bye", "goodbye", "see you") -> "Bye! Your chat is saved in memory."
            t.contains("time") && t.length < 30 -> "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
            t.contains("date") && t.length < 30 || t.contains("what day") -> "Today is ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}."
            t == "help" || t.contains("what can you do") -> HELP
            else -> null
        }
    }
    const val HELP = "Without internet I can:\n• chat (basic replies now — download Qwen3 from ⋮ → Local LLM for full offline chat)\n• read text in an image (📎)\n• search <query> / fetch <url> (when online)\n• write <name.txt>: <text>, read <name>, files\n• recall <words> from memory\n• brain sugar — run the fruit-fly connectome simulation\n\nAdd a Claude, ChatGPT or NVIDIA key in ⋮ → Cloud APIs for full answers."
}
