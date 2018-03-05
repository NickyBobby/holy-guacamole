package io.holyguacamole.bot.message

import io.holyguacamole.bot.controller.EventCallback
import io.holyguacamole.bot.controller.EventCallbackSubType.MESSAGE_DELETED
import io.holyguacamole.bot.controller.EventCallbackType.APP_MENTION
import io.holyguacamole.bot.controller.EventCallbackType.MEMBER_JOINED_CHANNEL
import io.holyguacamole.bot.controller.EventCallbackType.MESSAGE
import io.holyguacamole.bot.controller.EventCallbackType.USER_CHANGE
import io.holyguacamole.bot.controller.JoinedChannelEvent
import io.holyguacamole.bot.controller.MessageEvent
import io.holyguacamole.bot.controller.UserChangeEvent
import io.holyguacamole.bot.message.ContentCrafter.AVOCADO_REMINDER
import io.holyguacamole.bot.message.ContentCrafter.AVOCADO_TEXT
import io.holyguacamole.bot.message.ContentCrafter.TACO_TEXT
import io.holyguacamole.bot.message.ContentCrafter.notEnoughAvocados
import io.holyguacamole.bot.message.ContentCrafter.receivedAvocadoMessage
import io.holyguacamole.bot.message.ContentCrafter.sentAvocadoMessage
import io.holyguacamole.bot.message.ContentCrafter.welcomeMessage
import io.holyguacamole.bot.slack.SlackUser
import io.holyguacamole.bot.slack.toUser
import io.holyguacamole.bot.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class EventService(
        val repository: AvocadoReceiptRepository,
        val slackClient: SlackClient,
        val userService: UserService,
        @Value("\${bot.userId}") val bot: String
) {

    private val log = LoggerFactory.getLogger(this.javaClass)

    @Async
    fun process(eventCallback: EventCallback) {
            when (eventCallback.event.type) {
                APP_MENTION -> processAppMentionEvent(eventCallback.event as MessageEvent)
                MESSAGE -> processMessageEvent(eventCallback.eventId, eventCallback.event as MessageEvent)
                USER_CHANGE -> processUserChangeEvent((eventCallback.event as UserChangeEvent).user)
                MEMBER_JOINED_CHANNEL -> processMemberJoinedChannelEvent(eventCallback.event as JoinedChannelEvent)
            }
    }

    private fun processMessageEvent(eventId: String, event: MessageEvent): Boolean {

        event.previousMessage?.let {
            when (event.subType) {
                MESSAGE_DELETED -> repository.deleteBySenderAndTimestamp(
                        sender = it.user,
                        timestamp = it.ts.toDouble().toLong()
                )
            }
            return true
        }

        val mentions = event.findMentionedPeople()
        val avocadosInMessage = event.countGuacamoleIngredients()

        if (mentions.isNotEmpty() && avocadosInMessage == 0 && event.tacoCheck()) {
            slackClient.postEphemeralMessage(
                    channel = event.channel,
                    user = event.user,
                    text = AVOCADO_REMINDER
            )
            return false
        }
        if (avocadosInMessage == 0 || mentions.isEmpty()) return false

        val sender = userService.findByUserIdOrGetFromSlack(event.user)
        if (sender == null || sender.isBot) return false

        val avocadosSentToday = repository.findBySenderToday(sender.userId).size
        if ((avocadosSentToday + (avocadosInMessage * mentions.size)) > 5) {
            val remainingAvocados = 5 - avocadosSentToday
            slackClient.postEphemeralMessage(event.channel, event.user, notEnoughAvocados(remainingAvocados))
            return false
        }

        if (repository.findByEventId(eventId).isNotEmpty()) return false

        mentions.filter {
            userService.findByUserIdOrGetFromSlack(it)?.isBot == false
        }.flatMap { mention ->
            mapUntil(avocadosInMessage) {
                AvocadoReceipt(
                        eventId = eventId,
                        sender = event.user,
                        receiver = mention,
                        timestamp = event.ts.toDouble().toLong())
            }
        }.executeIfNotEmpty {
            it.save()
            sendReceiptMessage(event.channel, event.user, avocadosSentToday, it)
        }
        return true
    }

    private fun <T> List<T>.executeIfNotEmpty(fn: (List<T>) -> Unit): List<T> {
        if (this.isNotEmpty()) fn(this)
        return this
    }

    private fun List<AvocadoReceipt>.save() {
        if (this.isNotEmpty()) {
            repository.saveAll(this)
            log.info("Avocado sent")
        }
    }

    private fun sendReceiptMessage(channel: String, sender: String, avocadosSentToday: Int, avocadoReceipts: List<AvocadoReceipt>) {

        val uniqueReceivers = avocadoReceipts.map { it.receiver }.distinct()
        val avocadosEach = avocadoReceipts.size / uniqueReceivers.size
        val remainingAvocados = 5 - avocadosSentToday - uniqueReceivers.size * avocadosEach

        slackClient.postEphemeralMessage(
                channel = channel,
                user = sender,
                text = sentAvocadoMessage(uniqueReceivers, avocadosEach, remainingAvocados)
        )

        uniqueReceivers.map {
            slackClient.sendDirectMessage(it, receivedAvocadoMessage(avocadosEach, sender))
        }
    }

    private fun processAppMentionEvent(event: MessageEvent): Boolean {
        if (event.text?.toLowerCase()?.contains("leaderboard") == true) {
            slackClient.postMessage(
                    channel = event.channel,
                    text = craftLeaderboardMessage(repository.getLeaderboard())
            )
        }
        return true
    }

    private fun craftLeaderboardMessage(avocadoCounts: List<AvocadoCount>): String =
            avocadoCounts.joinToString(separator = "\n") {
                val user = userService.findByUserIdOrGetFromSlack(it.receiver)?.name ?: it.receiver
                "$user: ${it.count}"
            }

    private fun processUserChangeEvent(slackUser: SlackUser): Boolean {
        userService.replace(slackUser.toUser())
        return true
    }

    private fun processMemberJoinedChannelEvent(event: JoinedChannelEvent): Boolean {
        if (event.user == bot) {
            slackClient.postMessage(
                    channel = event.channel,
                    attachments = listOf(welcomeMessage)
            )
        }
        return true
    }
}

fun <T> mapUntil(end: Int, fn: () -> T): List<T> = (0 until end).map { fn() }

fun MessageEvent.countGuacamoleIngredients(): Int = (this.text?.split(AVOCADO_TEXT)?.size ?: 1) - 1
fun MessageEvent.findMentionedPeople(): List<String> = Regex("<@([0-9A-Z]*?)>")
        .findAll(this.text ?: "")
        .mapNotNull { it.groups[1]?.value }
        .filter { it != this.user }
        .toList()

fun MessageEvent.tacoCheck(): Boolean = this.text?.contains(TACO_TEXT) ?: false
