package site.asm0dey.calit.notify;

import org.alexmond.notify4j.Message;

/**
 * One async delivery: ONE channel row, its already-decrypted URL, and the message rendered for its
 * owner. One event per channel row (not per host) so a slow Telegram cannot delay a Slack, and the
 * outcome attributes to the row we already identified. The URL travels in the payload so the async
 * side needs no entity, no session and no transaction to read it.
 */
public record ChannelDelivery(Long channelId, String url, Message message) {}
