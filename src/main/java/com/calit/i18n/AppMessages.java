package com.calit.i18n;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * Type-safe UI string bundle. English is the default value here; German lives in
 * src/main/resources/messages/msg_de.properties keyed by method name. Missing German
 * key falls back to the English default automatically. Template namespace: {msg:key}.
 *
 * Keys follow <area>_<screen>_<what>: pub_*, adm_*, auth_*, email_*, common_*.
 */
@MessageBundle // default namespace "msg"
public interface AppMessages {

    @Message("Cancel")
    String common_cancel();
}
