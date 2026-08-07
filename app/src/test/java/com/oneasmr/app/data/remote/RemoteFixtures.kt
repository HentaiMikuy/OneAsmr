package com.oneasmr.app.data.remote

import java.nio.charset.StandardCharsets

/** Loads the hand-written kikoeru wire-format fixtures from src/test/resources/remote/. */
internal object RemoteFixtures {

    fun json(name: String): String {
        val stream = RemoteFixtures::class.java.getResourceAsStream("/remote/$name.json")
            ?: error("missing fixture /remote/$name.json")
        return stream.readBytes().toString(StandardCharsets.UTF_8)
    }
}
