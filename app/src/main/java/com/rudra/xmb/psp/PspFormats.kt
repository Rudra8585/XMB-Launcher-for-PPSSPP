package com.rudra.xmb.psp

import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/** Random-access byte source over a game image. */
interface ByteSource : Closeable {
    val size: Long
    fun read(pos: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size): Int

    fun readFully(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = read(pos + done, out, done, len - done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) out else out.copyOf(done)
    }
}

class ChannelSource(private val stream: FileInputStream) : ByteSource {
    private val channel: FileChannel = stream.channel
    override val size: Long = channel.size()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        return channel.read(ByteBuffer.wrap(dst, off, len), pos)
    }

    override fun close() = stream.close()
}

/** Compressed ISO (CISO v1): deflate-compressed blocks behind an index table. */
class CsoSource(private val raw: ByteSource) : ByteSource {
    private val blockSize: Int
    private val align: Int
    private val index: IntArray
    override val size: Long
    private val inflater = Inflater(true)
    private var cachedBlock = -1
    private var cache = ByteArray(0)

    init {
        val h = ByteBuffer.wrap(raw.readFully(0, 24)).order(ByteOrder.LITTLE_ENDIAN)
        require(h.getInt(0) == 0x4F534943) { "Not a CSO" } // "CISO"
        size = h.getLong(8)
        blockSize = h.getInt(16)
        align = h.get(21).toInt() and 0xFF
        val blocks = ((size + blockSize - 1) / blockSize).toInt()
        val idx = ByteBuffer.wrap(raw.readFully(24, (blocks + 1) * 4)).order(ByteOrder.LITTLE_ENDIAN)
        index = IntArray(blocks + 1) { idx.getInt(it * 4) }
        cache = ByteArray(blockSize)
    }

    private fun loadBlock(b: Int) {
        if (b == cachedBlock) return
        val start = (index[b].toLong() and 0x7FFFFFFF) shl align
        val end = (index[b + 1].toLong() and 0x7FFFFFFF) shl align
        val plain = index[b] < 0
        if (plain) {
            val data = raw.readFully(start, blockSize)
            System.arraycopy(data, 0, cache, 0, data.size)
        } else {
            val comp = raw.readFully(start, (end - start).toInt().coerceAtLeast(0) + 64)
            inflater.reset()
            inflater.setInput(comp)
            inflater.inflate(cache, 0, blockSize)
        }
        cachedBlock = b
    }

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        var p = pos
        var o = off
        var left = minOf(len.toLong(), size - pos).toInt()
        val total = left
        while (left > 0) {
            val b = (p / blockSize).toInt()
            val inBlock = (p % blockSize).toInt()
            loadBlock(b)
            val n = minOf(left, blockSize - inBlock)
            System.arraycopy(cache, inBlock, dst, o, n)
            p += n; o += n; left -= n
        }
        return total
    }

    override fun close() {
        inflater.end()
        raw.close()
    }
}

/** Minimal ISO9660 reader: just enough to pull files out of /PSP_GAME. */
class Iso9660(private val src: ByteSource) {
    data class Entry(val name: String, val lba: Long, val length: Int, val isDir: Boolean)

    private val root: Entry

    init {
        val pvd = src.readFully(16 * 2048L, 2048)
        require(pvd.size == 2048 && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") { "Not an ISO9660 image" }
        root = parseRecord(pvd, 156)!!
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun parseRecord(b: ByteArray, o: Int): Entry? {
        val len = b[o].toInt() and 0xFF
        if (len == 0) return null
        val lba = le32(b, o + 2)
        val size = le32(b, o + 10).toInt()
        val flags = b[o + 25].toInt()
        val nameLen = b[o + 32].toInt() and 0xFF
        val name = String(b, o + 33, nameLen, Charsets.US_ASCII).substringBefore(';')
        return Entry(name, lba, size, flags and 2 != 0)
    }

    fun list(dir: Entry): List<Entry> {
        val data = src.readFully(dir.lba * 2048, dir.length)
        val out = ArrayList<Entry>()
        var o = 0
        while (o < data.size) {
            val len = data[o].toInt() and 0xFF
            if (len == 0) {
                o = (o / 2048 + 1) * 2048 // records never straddle sectors
                continue
            }
            parseRecord(data, o)?.let { if (it.name != "\u0000" && it.name != "\u0001") out += it }
            o += len
        }
        return out
    }

    fun find(path: String): Entry? {
        var cur = root
        for (part in path.trim('/').split('/')) {
            cur = list(cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return cur
    }

    fun readFile(e: Entry, maxBytes: Int = Int.MAX_VALUE): ByteArray = src.readFully(e.lba * 2048, minOf(e.length, maxBytes))
}

/** PARAM.SFO key/value table. */
object ParamSfo {
    fun parse(data: ByteArray): Map<String, Any> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 20 || bb.getInt(0) != 0x46535000) return emptyMap() // "\0PSF"
        val keyTable = bb.getInt(8)
        val dataTable = bb.getInt(12)
        val count = bb.getInt(16)
        val out = LinkedHashMap<String, Any>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            val keyOff = bb.getShort(e).toInt() and 0xFFFF
            val fmt = bb.getShort(e + 2).toInt() and 0xFFFF
            val len = bb.getInt(e + 4)
            val dataOff = bb.getInt(e + 12)
            var k = keyTable + keyOff
            val sb = StringBuilder()
            while (k < data.size && data[k] != 0.toByte()) sb.append(data[k++].toInt().toChar())
            val p = dataTable + dataOff
            if (p + len > data.size) continue
            out[sb.toString()] = when (fmt) {
                0x0404 -> bb.getInt(p)
                else -> String(data, p, len, Charsets.UTF_8).trimEnd('\u0000')
            }
        }
        return out
    }
}

/** Everything the XMB shows for one game, pulled from the image. */
class GameAssets(
    val sfo: Map<String, Any>,
    val icon0: ByteArray?,
    val icon1: ByteArray?,
    val pic0: ByteArray?,
    val pic1: ByteArray?,
    val snd0: ByteArray?,
)

object GameImage {
    private const val MAX_ASSET = 8 * 1024 * 1024

    /**
     * Extracts the XMB assets from [raw]. The format comes from the file's magic bytes, not its name:
     * CSO images are often named .iso.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extract(raw: ByteSource, fileName: String): GameAssets {
        val magic = String(raw.readFully(0, 4), Charsets.ISO_8859_1)
        return when (magic) {
            "\u0000PBP" -> extractPbp(raw)
            "CISO" -> extractIso(CsoSource(raw))
            else -> extractIso(raw)
        }
    }

    private fun extractIso(src: ByteSource): GameAssets {
        val iso = Iso9660(src)
        val dir = iso.find("PSP_GAME") ?: error("No PSP_GAME folder")
        val files = iso.list(dir).associateBy { it.name.uppercase() }
        fun get(n: String) = files[n]?.takeIf { !it.isDir && it.length > 0 }?.let { iso.readFile(it, MAX_ASSET) }
        return GameAssets(
            sfo = get("PARAM.SFO")?.let { ParamSfo.parse(it) } ?: emptyMap(),
            icon0 = get("ICON0.PNG"),
            icon1 = get("ICON1.PMF"),
            pic0 = get("PIC0.PNG"),
            pic1 = get("PIC1.PNG"),
            snd0 = get("SND0.AT3"),
        )
    }

    private fun extractPbp(src: ByteSource): GameAssets {
        val h = ByteBuffer.wrap(src.readFully(0, 40)).order(ByteOrder.LITTLE_ENDIAN)
        require(h.getInt(0) == 0x50425000) { "Not a PBP" } // "\0PBP"
        val offs = LongArray(9) { if (it < 8) h.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL else src.size }
        fun part(i: Int): ByteArray? {
            val len = (offs[i + 1] - offs[i]).coerceAtMost(MAX_ASSET.toLong()).toInt()
            return if (len > 0) src.readFully(offs[i], len) else null
        }
        return GameAssets(
            sfo = part(0)?.let { ParamSfo.parse(it) } ?: emptyMap(),
            icon0 = part(1), icon1 = part(2), pic0 = part(3), pic1 = part(4), snd0 = part(5),
        )
    }
}
