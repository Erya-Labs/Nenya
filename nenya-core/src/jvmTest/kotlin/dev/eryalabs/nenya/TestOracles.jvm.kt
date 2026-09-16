package dev.eryalabs.nenya

import java.security.MessageDigest

internal actual fun oracleSha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
