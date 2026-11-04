package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kształt z żywych odpowiedzi /api/mqtt/status (live.meshcorekk.xyz, analyzer.marwoj.net). */
class MqttStatusApiTest {
    private val now = 1_793_807_810_000L
    private fun src(name: String, connected: Boolean, lastPacketAgoS: Long = 2) =
        """{"name":"$name","broker":"tcp://$name:1883","connected":$connected,"lastPacketUnix":${now / 1000 - lastPacketAgoS}}"""
    private fun body(vararg sources: String, sampleAgoS: Long = 3) =
        """{"sources":[${sources.joinToString(",")}],"sampleAt":"${java.time.Instant.ofEpochMilli(now - sampleAgoS * 1000)}","watchdogLastTickUnix":${now / 1000 - 5}}"""

    @Test fun singleConnectedSourceIsAlive() =
        assertEquals(Health.GOOD, MqttStatusApi.parse("live.meshcorekk.xyz", body(src("local", true)), now))

    @Test fun multiSourceAggregatorIsAliveWhenAnySourceConnected() =
        assertEquals(Health.GOOD, MqttStatusApi.parse("analyzer.marwoj.net",
            body(src("meshstats-pl", false), src("local-mosquitto", true), src("loramc2", true)), now))

    @Test fun allSourcesDisconnectedIsDead() =
        assertEquals(Health.BAD, MqttStatusApi.parse("h", body(src("a", false), src("b", false)), now))

    @Test fun frozenStatusIsDead() =
        assertEquals(Health.BAD, MqttStatusApi.parse("h", body(src("a", true), sampleAgoS = 600), now))

    @Test fun noPacketsForLongIsDead() =
        assertEquals(Health.BAD, MqttStatusApi.parse("h", body(src("a", true, lastPacketAgoS = 3600)), now))

    @Test fun garbageIsUnknown() =
        assertEquals(Health.UNKNOWN, MqttStatusApi.parse("h", "<html>", now))
}
