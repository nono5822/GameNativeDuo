package app.gamenative.duo.community

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature **verification** (RFC 8032, section 5.1.7), after the RFC's reference implementation. Used to
 * trust the community index (signed by its CI). Android's own crypto only has Ed25519 on recent versions, and adding
 * a crypto library would touch upstream's build; verification needs no secrets and no constant-time code, so a small
 * BigInteger implementation is enough: a few milliseconds per index download.
 */
internal object Ed25519 {
    // First: the constants below use it. Not BigInteger.TWO, which Android only has from API 33.
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val Q: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** Extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val x = recoverX(y, 0) ?: error("no base point")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** True when [signature] (64 bytes) is [publicKey]'s (32 bytes) signature of [message]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val h = MessageDigest.getInstance("SHA-512").run {
            update(rBytes)
            update(publicKey)
            update(message)
            littleEndian(digest()).mod(Q)
        }
        return equal(mul(s, BASE), add(r, mul(h, a)))
    }

    private fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var q = IDENTITY
        var p = point
        var s = scalar
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Point, q: Point): Boolean =
        p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0 &&
            p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val y2 = y.multiply(y)
        val x2 = y2.subtract(BigInteger.ONE).multiply(inv(D.multiply(y2).add(BigInteger.ONE))).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P.subtract(x)
        return x
    }

    private fun decompress(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        var y = littleEndian(bytes)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun inv(v: BigInteger): BigInteger = v.modPow(P.subtract(TWO), P)

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
}
