package site.asm0dey.calit.google;

/** The identity claims read from a Google id_token during sign-in. */
public record GoogleIdentity(String sub, String email, boolean emailVerified) {}
