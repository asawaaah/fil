package com.asawaaah.fil

object TestFiles {
    fun bytes(path: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "Missing test file $path" }.use { it.readBytes() }

    fun text(path: String): String = String(bytes(path), Charsets.UTF_8)
}
