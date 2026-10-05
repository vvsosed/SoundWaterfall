package com.example.soundwaterfall.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumSnapshotTest {

    @Test
    fun readReportsNoDataBeforeTheFirstPublish() {
        val snap = SpectrumSnapshot(4)
        val dest = FloatArray(4) { 7f }
        assertFalse(snap.readInto(dest))
        assertArrayEquals("dest must be left alone", FloatArray(4) { 7f }, dest, 0f)
    }

    @Test
    fun readReturnsTheMostRecentlyPublishedFrame() {
        val snap = SpectrumSnapshot(3)
        snap.publish(floatArrayOf(-1f, -2f, -3f))
        val dest = FloatArray(3)
        assertTrue(snap.readInto(dest))
        assertArrayEquals(floatArrayOf(-1f, -2f, -3f), dest, 0f)

        snap.publish(floatArrayOf(-9f, -8f, -7f))
        assertTrue(snap.readInto(dest))
        assertArrayEquals(floatArrayOf(-9f, -8f, -7f), dest, 0f)
    }

    @Test
    fun repeatedReadsWithoutAPublishReturnTheSameFrame() {
        val snap = SpectrumSnapshot(2)
        snap.publish(floatArrayOf(-4f, -5f))
        val a = FloatArray(2)
        val b = FloatArray(2)
        snap.readInto(a)
        snap.readInto(b)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun alternatesBuffersSoAPublishDoesNotOverwriteWhatIsBeingRead() {
        // Publishing twice must use two different backing arrays; if it reused one,
        // the second publish would corrupt a concurrent read of the first.
        val snap = SpectrumSnapshot(1)
        snap.publish(floatArrayOf(1f))
        val first = snap.frontBufferForTest()
        snap.publish(floatArrayOf(2f))
        val second = snap.frontBufferForTest()
        assertFalse("publish must alternate buffers", first === second)
    }

    @Test
    fun survivesConcurrentPublishAndRead() {
        val snap = SpectrumSnapshot(256)
        val producer = Thread {
            val frame = FloatArray(256)
            for (i in 0 until 20_000) {
                frame.fill(-i.toFloat())
                snap.publish(frame)
            }
        }
        var failure: Throwable? = null
        val consumer = Thread {
            val dest = FloatArray(256)
            try {
                for (i in 0 until 20_000) {
                    if (snap.readInto(dest)) {
                        // Every element of a published frame is identical, so a
                        // torn read would show up as a mismatch here.
                        val v = dest[0]
                        for (x in dest) {
                            if (x != v) throw AssertionError("torn frame: $v vs $x")
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
        producer.start(); consumer.start()
        producer.join(10_000); consumer.join(10_000)
        failure?.let { throw it }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAPublishOfTheWrongLength() {
        SpectrumSnapshot(4).publish(FloatArray(2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAReadIntoTheWrongLength() {
        val snap = SpectrumSnapshot(4)
        snap.publish(FloatArray(4))
        snap.readInto(FloatArray(2))
    }
}
