package graphics.scenery.insitu

import graphics.scenery.volumes.vdi.VDIDataIO
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorOutputStream
import org.apache.commons.compress.compressors.snappy.SnappyCompressorOutputStream
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree
import org.lwjgl.util.lz4.LZ4.*
import org.lwjgl.util.lz4.LZ4HC.LZ4HC_CLEVEL_DEFAULT
import org.lwjgl.util.lz4.LZ4HC.LZ4_compress_HC
import org.lwjgl.util.zstd.Zstd.*
import org.lwjgl.util.zstd.ZstdX.ZSTD_findDecompressedSize
import org.xerial.snappy.Snappy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.system.measureNanoTime


class VDICompressionBenchmarks {



    companion object {

        @JvmStatic
        fun compressSnappy(color: ByteBuffer, depth: ByteBuffer, maxSizeRatio: Int = 1) {
            val compressedColors: ByteBuffer? = ByteBuffer.allocateDirect(color.capacity() / maxSizeRatio)
            Snappy.compress(color, compressedColors)

            val compressedDepths: ByteBuffer? = ByteBuffer.allocateDirect(depth.capacity() / maxSizeRatio)
            Snappy.compress(depth, compressedDepths)
        }

        @JvmStatic
        fun compressSnappy(color: ByteArray, depth: ByteArray) {
            val compressedColor = Snappy.compress(color)
            val compressedDepth = Snappy.compress(depth)

            println("Snappy! color size: ${compressedColor.size/(1024*1024)} and depth size: ${compressedDepth.size/(1024*1024)}")
        }

        @JvmStatic
        fun compressSnappyRaw(color: ByteBuffer, depth: ByteBuffer) {
            val compressedColor = MemoryUtil.memAlloc(color.remaining())
            val compressedDepth = MemoryUtil.memAlloc(color.remaining())
            Snappy.compress(color, compressedColor)
            Snappy.compress(depth, compressedDepth)

            println("Snappy! color size: ${compressedColor.remaining()/(1024*1024)} and depth size: ${compressedDepth.remaining()/(1024*1024)}")
        }

        @JvmStatic
        fun compressLZ4(color: ByteArray, depth: ByteArray) {
            val factory: LZ4Factory = LZ4Factory.fastestInstance()

            var decompressedLength = color.size

            val compressor: LZ4Compressor = factory.fastCompressor()
            var maxCompressedLength: Int = compressor.maxCompressedLength(decompressedLength)
            var compressed = ByteArray(maxCompressedLength)
            val compressedColorLength: Int =
                compressor.compress(color, 0, decompressedLength, compressed, 0, maxCompressedLength)


            decompressedLength = depth.size

            maxCompressedLength = compressor.maxCompressedLength(decompressedLength)
            compressed = ByteArray(maxCompressedLength)
            val compressedDepthLength: Int =
                compressor.compress(depth, 0, decompressedLength, compressed, 0, maxCompressedLength)

            println("LZ4! color size: ${compressedColorLength/(1024*1024)} and depth size: ${compressedDepthLength/(1024*1024)}")

        }

        @JvmStatic
        fun compressLZ4_LWJGL(color: ByteBuffer, depth: ByteBuffer) {
            var compressedColor = memAlloc(LZ4_compressBound(color.remaining()))
            var compressedDepth = memAlloc(LZ4_compressBound(depth.remaining()))

            val colorSize = LZ4_compress_fast(color, compressedColor, 5).toLong()
            compressedColor.limit(colorSize.toInt())
            compressedColor = compressedColor.slice()

            val depthSize = LZ4_compress_fast(depth, compressedDepth, 5).toLong()
            compressedDepth.limit(depthSize.toInt())
            compressedDepth = compressedDepth.slice()

            println("LZ4 LWJGL! color size: ${colorSize.toFloat()/(1024f*1024f)} and depth size: ${depthSize.toFloat()/(1024f*1024f)}")
        }

        @JvmStatic
        fun compressLZ4_LWJGL_HC(color: ByteBuffer, depth: ByteBuffer) {
            var compressedColor = memAlloc((color.capacity()))
            var compressedDepth = memAlloc((depth.capacity()))

            val colorSize = LZ4_compress_HC(color, compressedColor, LZ4HC_CLEVEL_DEFAULT).toLong()
            compressedColor.limit(colorSize.toInt())
            compressedColor = compressedColor.slice()

            val depthSize = LZ4_compress_HC(depth, compressedDepth, LZ4HC_CLEVEL_DEFAULT).toLong()
            compressedDepth.limit(depthSize.toInt())
            compressedDepth = compressedDepth.slice()

            println("LZ4 LWJGL HC! color size: ${colorSize.toFloat()/(1024*1024)} and depth size: ${depthSize.toFloat()/(1024*1024)}")
        }

        @JvmStatic
        fun compressZSTD(color: ByteBuffer, depth: ByteBuffer, compressedColor: ByteBuffer, compressedDepth: ByteBuffer) {
            val colorSize = ZSTD_compress(color, compressedColor, ZSTD_CLEVEL_DEFAULT) //TODO: min_CLevel produces faster compression
            compressedColor.limit(colorSize.toInt())
            compressedColor = compressedColor.slice()

            val depthSize = ZSTD_compress(depth, compressedDepth, ZSTD_CLEVEL_DEFAULT)
            compressedDepth.limit(depthSize.toInt())
            compressedDepth = compressedDepth.slice()

            println("ZSTD LWJGL! color size: ${colorSize.toFloat()/(1024f*1024f)} and depth size: ${depthSize.toFloat()/(1024f*1024f)}")
            println("color buffer size: ${compressedColor.remaining().toFloat()/(1024f*1024f)} and depth buffer size: ${compressedDepth.remaining().toFloat()/(1024f*1024f)}")
        }

        @JvmStatic
        fun compressLZ4Apache(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
//            colorOut.write(color)
            val fColor = FramedLZ4CompressorOutputStream(colorOut)
            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
//            depthOut.write(depth)
            val fDepth = FramedLZ4CompressorOutputStream(depthOut)
            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressSnappyApacheFramed(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
//            colorOut.write(color)
            val fColor = FramedSnappyCompressorOutputStream(colorOut)
            fColor.write(color, 0, color.size)

//            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
//            depthOut.write(depth)
            val fDepth = FramedSnappyCompressorOutputStream(depthOut)
            fDepth.write(depth)

//            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressSnappyApache(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
//            colorOut.write(color)
            val fColor = SnappyCompressorOutputStream(colorOut, color.size.toLong(), 32768 / 2)
            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
//            depthOut.write(depth)
            val fDepth = SnappyCompressorOutputStream(depthOut, depth.size.toLong(), 32768 / 2)
            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressLZ4Apache(colorBuffer: ByteBuffer, depthBuffer: ByteBuffer) {

            val color = ByteArray(colorBuffer.remaining())
            colorBuffer.get(color)
            colorBuffer.flip()

            val colorOut = ByteArrayOutputStream(color.size)
            colorOut.write(color)
            val fColor = FramedLZ4CompressorOutputStream(colorOut)
//            fColor.write(color, 0, color.size)

//            fColor.flush()

            val depth = ByteArray(depthBuffer.remaining())
            depthBuffer.get(depth)
            depthBuffer.flip()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = FramedLZ4CompressorOutputStream(depthOut)
//            fDepth.write(depth)

//            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressLZMA(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
//            colorOut.write(color)
            val fColor = LZMACompressorOutputStream(colorOut)
            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
//            depthOut.write(depth)
            val fDepth = LZMACompressorOutputStream(depthOut)
            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressGzip(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
            colorOut.write(color)
            val fColor = GzipCompressorOutputStream(colorOut)
            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = GzipCompressorOutputStream(depthOut)
            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        private fun checkZSTD(errorCode: Long): Long {
            check(!ZSTD_isError(errorCode)) { "Zstd error: " + errorCode + " | " + ZSTD_getErrorName(errorCode) }
            return errorCode
        }

        private fun verifyDecompressed(color: ByteBuffer, depth: ByteBuffer, compressedColor: ByteBuffer, compressedDepth: ByteBuffer) {
            val decompressedColor = memAlloc(color.remaining() + 1024)
            val decompressedDepth = memAlloc(color.remaining() + 1024)
            try {
                checkZSTD(ZSTD_findDecompressedSize(compressedColor))
                val decompressedSize = ZSTD_decompress(decompressedColor, compressedColor)
                checkZSTD(decompressedSize)
                check(
                    decompressedSize == color.remaining().toLong()
                ) {
                    String.format(
                        "Decompressed size %d != uncompressed size %d",
                        decompressedSize,
                        color.remaining()
                    )
                }
                for (i in 0 until color.remaining()) {
                    check(decompressedColor[i] == color[i]) { "Color Decompressed != uncompressed at: $i" }
                }
            } finally {
                memFree(decompressedColor)
            }

            try {
                checkZSTD(ZSTD_findDecompressedSize(compressedDepth))
                val decompressedSize = ZSTD_decompress(decompressedDepth, compressedDepth)
                checkZSTD(decompressedSize)
                check(
                    decompressedSize == depth.remaining().toLong()
                ) {
                    String.format(
                        "Decompressed size %d != uncompressed size %d",
                        decompressedSize,
                        depth.remaining()
                    )
                }
                for (i in 0 until color.remaining()) {
                    check(decompressedDepth[i] == depth[i]) { "Depth Decompressed != uncompressed at: $i" }
                }
            } finally {
                memFree(decompressedDepth)
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            println("Hello World")

            val numSupersegments = 30

            val buff: ByteArray
            val depthBuff: ByteArray?

            var dataset = "Kingsnake"

//        dataset += "_${commSize}_${rank}"


//        val basePath = "/home/aryaman/Repositories/DistributedVis/cmake-build-debug/"
        val basePath = "/home/aryaman/Repositories/scenery-insitu/"
//            val basePath = "/home/aryaman/Repositories/scenery_vdi/scenery/"
//        val basePath = "/home/aryaman/TestingData/"
//        val basePath = "/home/aryaman/TestingData/FromCluster/"

            val file = FileInputStream(File(basePath + "${dataset}vdidump4"))
//        val comp = GZIPInputStream(file, 65536)

            val vdiData = VDIDataIO.read(file)


//        val vdiType = "Sub"
//        val vdiType = "Composited"
//        val vdiType = "SetOf"
//        val vdiType = "Final"
            val vdiType = ""

            buff = File(basePath + "${dataset}${vdiType}VDI4_ndc_col").readBytes()
            depthBuff = File(basePath + "${dataset}${vdiType}VDI4_ndc_depth").readBytes()

            println("Color sum is ${buff.sum()}")
            println("Depth sum is ${depthBuff.sum()}")

            var colBuffer: ByteBuffer
            var depthBuffer: ByteBuffer?

            val windowWidth = 1280
            val windowHeight = 720

            colBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * 4 * 4)

            colBuffer.put(buff).flip()

            depthBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * 2 * 2 * 2)
            depthBuffer.put(depthBuff).flip()

            println("color buffer has remaining: ${colBuffer.remaining()}")

            val iterations = 100
            var cnt = 0

            var compressedColor =  memAlloc(ZSTD_COMPRESSBOUND(colBuffer.remaining().toLong()).toInt());
            var compressedDepth = memAlloc(ZSTD_COMPRESSBOUND(depthBuffer.capacity().toLong()).toInt())

            val totalTime = measureNanoTime {
                while (cnt < iterations) {
                    cnt++

                    val compTime = measureNanoTime {
//                        compressSnappy(colBuffer, depthBuffer, 6)
//                        compressSnappy(buff, depthBuff)
//                        compressSnappyRaw(colBuffer, depthBuffer)
                        compressZSTD(colBuffer, depthBuffer, compressedColor, compressedDepth)
//                        compressLZ4_LWJGL_HC(colBuffer, depthBuffer)
//                        compressLZ4(buff, depthBuff)
//                        compressLZ4Apache(buff, depthBuff)
//                        compressSnappyApacheFramed(buff, depthBuff)
//                        compressSnappyApache(buff, depthBuff)
//                        compressLZ4Apache(colBuffer, depthBuffer)
//                        compressLZMA(buff, depthBuff)
//                        compressGzip(buff, depthBuff)
                    }


                    if(cnt % 1 == 0) {
                        println("iteration: $cnt the compression took: ${compTime/1e9}")
                    }
                }
            }

            println("Overall average = ${totalTime/(1e9 * iterations)}")

        }
    }

}