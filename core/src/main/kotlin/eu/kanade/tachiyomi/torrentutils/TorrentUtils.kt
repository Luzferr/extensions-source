package eu.kanade.tachiyomi.torrentutils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

object TorrentUtils {
    private val client by lazy { OkHttpClient() }

    fun getTorrentInfo(url: String, @Suppress("UNUSED_PARAMETER") extension: String): TorrentInfo {
        val bytes = client.newCall(
            Request.Builder()
                .url(url)
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch torrent: HTTP ${response.code}")
            }
            response.body.bytes()
        }

        val parser = BencodeParser(bytes)
        val root = parser.parseAny() as? BencodeValue.Dict
            ?: throw IllegalStateException("Invalid torrent file")

        val info = root.entries["info"] as? BencodeValue.Dict
            ?: throw IllegalStateException("Missing torrent info")
        val infoBytes = bytes.copyOfRange(info.startIndex, info.endIndex)
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(infoBytes)
            .joinToString("") { "%02x".format(it) }

        val trackers = buildList {
            (root.entries["announce"] as? BencodeValue.Bytes)?.asString()?.takeIf(String::isNotBlank)?.let(::add)
            val announceList = root.entries["announce-list"] as? BencodeValue.List
            announceList?.values
                ?.flatMap { tier ->
                    when (tier) {
                        is BencodeValue.List -> tier.values.mapNotNull { (it as? BencodeValue.Bytes)?.asString() }
                        is BencodeValue.Bytes -> listOf(tier.asString())
                        else -> emptyList()
                    }
                }
                ?.filter(String::isNotBlank)
                ?.forEach(::add)
        }.distinct()

        val files = parseFiles(info)

        return TorrentInfo(
            hash = hash,
            trackers = trackers,
            files = files,
        )
    }

    private fun parseFiles(info: BencodeValue.Dict): List<TorrentFile> {
        val multiFiles = (info.entries["files"] as? BencodeValue.List)?.values
            ?.mapIndexedNotNull { index, fileValue ->
                val file = fileValue as? BencodeValue.Dict ?: return@mapIndexedNotNull null
                val path = (file.entries["path"] as? BencodeValue.List)
                    ?.values
                    ?.mapNotNull { (it as? BencodeValue.Bytes)?.asString() }
                    ?.joinToString("/")
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapIndexedNotNull null
                val size = (file.entries["length"] as? BencodeValue.Integer)?.value ?: return@mapIndexedNotNull null
                TorrentFile(
                    path = path,
                    indexFile = index,
                    size = size,
                )
            }
            .orEmpty()

        if (multiFiles.isNotEmpty()) {
            return multiFiles
        }

        val fileName = (info.entries["name"] as? BencodeValue.Bytes)?.asString()
            ?: throw IllegalStateException("Missing torrent file name")
        val size = (info.entries["length"] as? BencodeValue.Integer)?.value
            ?: throw IllegalStateException("Missing torrent file size")

        return listOf(
            TorrentFile(
                path = fileName,
                indexFile = 0,
                size = size,
            ),
        )
    }
}

data class TorrentInfo(
    val hash: String,
    val trackers: List<String>,
    val files: List<TorrentFile>,
)

data class TorrentFile(
    val path: String,
    val indexFile: Int,
    val size: Long,
)

private class BencodeParser(private val bytes: ByteArray) {
    private var index = 0

    fun parseAny(): BencodeValue = when (val current = bytes[index].toInt().toChar()) {
        'd' -> parseDict()
        'l' -> parseList()
        'i' -> parseInt()
        in '0'..'9' -> parseBytes()
        else -> throw IllegalStateException("Unexpected bencode token: $current")
    }

    private fun parseDict(): BencodeValue.Dict {
        val start = index
        index++
        val entries = linkedMapOf<String, BencodeValue>()
        while (bytes[index].toInt().toChar() != 'e') {
            val key = parseBytes().asString()
            entries[key] = parseAny()
        }
        index++
        return BencodeValue.Dict(entries, start, index)
    }

    private fun parseList(): BencodeValue.List {
        val start = index
        index++
        val values = mutableListOf<BencodeValue>()
        while (bytes[index].toInt().toChar() != 'e') {
            values += parseAny()
        }
        index++
        return BencodeValue.List(values, start, index)
    }

    private fun parseInt(): BencodeValue.Integer {
        val start = index
        index++
        val end = bytes.indexOfFirst(index) { it.toInt().toChar() == 'e' }
        val value = bytes.decodeToString(index, end).toLong()
        index = end + 1
        return BencodeValue.Integer(value, start, index)
    }

    private fun parseBytes(): BencodeValue.Bytes {
        val start = index
        val separator = bytes.indexOfFirst(index) { it.toInt().toChar() == ':' }
        val length = bytes.decodeToString(index, separator).toInt()
        index = separator + 1
        val end = index + length
        val value = bytes.copyOfRange(index, end)
        index = end
        return BencodeValue.Bytes(value, start, end)
    }

    private inline fun ByteArray.indexOfFirst(fromIndex: Int, predicate: (Byte) -> Boolean): Int {
        for (i in fromIndex until size) {
            if (predicate(this[i])) return i
        }
        throw IllegalStateException("Malformed bencode data")
    }
}

private sealed class BencodeValue(val startIndex: Int, val endIndex: Int) {
    class Dict(val entries: Map<String, BencodeValue>, startIndex: Int, endIndex: Int) : BencodeValue(startIndex, endIndex)
    class List(val values: kotlin.collections.List<BencodeValue>, startIndex: Int, endIndex: Int) : BencodeValue(startIndex, endIndex)
    class Integer(val value: Long, startIndex: Int, endIndex: Int) : BencodeValue(startIndex, endIndex)
    class Bytes(private val value: ByteArray, startIndex: Int, endIndex: Int) : BencodeValue(startIndex, endIndex) {
        fun asString(): String = value.toString(Charsets.UTF_8)
    }
}
