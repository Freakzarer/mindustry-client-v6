package mindustry.client.storage

import mindustry.client.utils.buffer
import mindustry.client.utils.toBytes
import java.io.IOException
import kotlin.jvm.Throws

abstract class StorageSystem {

    companion object {
        const val VERSION = 0
    }

    abstract val locked: Boolean

    protected abstract fun getByte(index: Int): Byte
    protected abstract fun setByte(index: Int, value: Byte)

    protected abstract fun getRange(range: IntRange): ByteArray
    protected abstract fun setRange(range: IntRange, value: ByteArray)

    private lateinit var metadataBytes: ByteSection
    private lateinit var mainBytes: ByteSection

    private class StorageSystemByteSection(private val storageSystem: StorageSystem, private val startingIndex: Int, override val size: Int) : ByteSection {
        override fun get(index: Int) = storageSystem.getByte(index + startingIndex)

        override fun get(range: IntRange) = storageSystem.getRange(range.first + startingIndex..range.last + startingIndex)

        override fun set(index: Int, byte: Byte) { storageSystem.setByte(index + startingIndex, byte) }

        override fun set(range: IntRange, value: ByteArray) { storageSystem.setRange(range.first + startingIndex..range.last + startingIndex, value) }

        override fun all() = storageSystem.getRange(startingIndex..startingIndex + size)

        override fun iterator() = all().iterator()
    }

    fun init() {
        metadataBytes = StorageSystemByteSection(this, 0, 1024)
        mainBytes = StorageSystemByteSection(this, 1025, 2048)
    }

    fun all(): List<Storable> {
        val output = mutableListOf<Storable>()
        for (inode in allINodes()) {
            output.add(retrieveStorable(inode))
        }
        return output
    }

    fun getById(id: Long) = retrieveStorable(allINodes().single { it.id == id })

    private fun getLock(timeout: Int = 30_000) {
        while (locked) {
            Thread.sleep(10L)
        }
        lock(timeout)
    }

    private fun retrieveFrom(inode: INode): ByteArray {
        val output = ByteArray(inode.address.sumBy { it.count() })
        var count = 0
        for (address in inode.address) {
            for (byte in mainBytes[address]) {
                output[count++] = byte
            }
        }
        return output
    }

    private fun retrieveStorable(inode: INode) =
        Storable.StorableRegistrator.instantiate(inode.type, inode.id).apply { deserialize(retrieveFrom(inode)) }

    @Throws(IOException::class)
    fun store(storable: Storable) {
        getLock()

        val encoded = storable.serialize()

        val spots = freeSpots()

        val finalSpots: List<IntRange>
        val chunks = mutableListOf<ByteArray>()

        if (spots.any { it.count() >= encoded.size }) {  // Enough space to store in a single spot
            finalSpots = listOf(spot(encoded.size))
            chunks.add(encoded)
        } else {  // Need to split it up
            var count = 0
            val found = mutableListOf<IntRange>()
            for (range in spots.sortedByDescending { it.count() }) {
                val before = count
                count += range.count()
                if (count < encoded.size) {
                    found.add(range)
                    chunks.add(encoded.sliceArray(before until count))
                    continue
                }
                chunks.add(encoded.sliceArray(before until count))
                found.add(range.first..encoded.size - before)
                break
            }
            finalSpots = found
        }

        val inode = INode(finalSpots, Storable.StorableRegistrator.id(storable), VERSION, storable.id)

        storeINode(inode, extraUsedChunks = finalSpots)

        for ((index, spot) in finalSpots.withIndex()) {
            mainBytes[spot] = chunks[index]
        }

        unlock()
    }

    private fun storeINode(inode: INode, extraUsedChunks: List<IntRange> = listOf()) {
        val encoded = inode.serialize()

        val spot = spot(encoded.size, extraUsedChunks = extraUsedChunks)

        val initialSize = metadataBytes.buffer().int
        metadataBytes[0 until 4] = (initialSize + 1).toBytes()
        val metadataAddress = (initialSize * 8) + Int.SIZE_BYTES
        metadataBytes[metadataAddress until metadataAddress + 4] = spot.first.toBytes()
        metadataBytes[metadataAddress + 4 until metadataAddress + 8] = spot.last.toBytes()
        mainBytes[spot] = encoded
    }

    private fun inodeAddresses(): List<IntRange> {
        val buf = metadataBytes.buffer()
        val count = buf.int
        val output = mutableListOf<IntRange>()
        for (i in 0 until count) {
            output.add(buf.int..buf.int)
        }
        return output
    }

    private fun allINodes(): List<INode> {
        val output = mutableListOf<INode>()
        for (address in inodeAddresses()) {
            val bytes = mainBytes[address]
            output.add(INode(bytes.buffer()))
        }

        return output
    }

    private fun spot(size: Int, extraUsedChunks: List<IntRange> = listOf()): IntRange {
        val spot = freeSpots(size, extraUsedChunks = extraUsedChunks).random()
        return spot.first until spot.first + size
    }

    private fun freeSpots(minSize: Int = 1, extraUsedChunks: List<IntRange> = listOf()): List<IntRange> {
        val inodeAddresses = inodeAddresses()
        val inodes = allINodes()

        // Merge the addresses of all storables into a list (plus inodes) and sort by the start of the range
        val usedRanges = inodes.asSequence().map { it.address }.flatten()
            .plus(extraUsedChunks).plus(inodeAddresses)
            .sortedBy { it.first }.toList()

        val output = mutableListOf<IntRange>()
        var last = 0
        for (range in usedRanges) {
            if (range.first - last >= minSize) {
                output.add(last + 1..range.first)
            }
            last = range.last
        }
        output.add(last + 1..mainBytes.size)

        return output
    }

    // TODO: Add support for locking ranges instead of the entire filesystem
    abstract fun lock(timeout: Int)

    abstract fun unlock()
}
