package io.holyguacamole.bot.message

import io.holyguacamole.bot.AVOCADO_TEXT
import io.holyguacamole.bot.controller.MessageEvent
import io.holyguacamole.bot.controller.MessageEventRequest
import org.springframework.stereotype.Service

@Service
class MessageService(val repository: AvocadoReceiptRepository) {

    fun process(messageEvent: MessageEventRequest): Boolean {
        val mentions = messageEvent.event.findMentionedPeople()
        val count = messageEvent.event.countGuacamoleIngredients()
        if (count == 0 && mentions.isEmpty()) return false

        repository.saveAll(
                mapUntil(count) {
                    AvocadoReceipt(
                            eventId = messageEvent.event_id,
                            sender = messageEvent.event.user,
                            receiver = mentions.first(),
                            timestamp = messageEvent.event.ts.toDouble().toLong())
                }
        )

        return true
    }
}

fun <T> mapUntil(end: Int, fn: () -> T): List<T> = (0 until end).map { fn() }

fun MessageEvent.countGuacamoleIngredients(): Int = this.text.split(AVOCADO_TEXT).size - 1
fun MessageEvent.findMentionedPeople(): List<String> = Regex("<@(U[0-9A-Z]*?)>")
        .findAll(this.text)
        .mapNotNull { it.groups[1]?.value }
        .toList()
