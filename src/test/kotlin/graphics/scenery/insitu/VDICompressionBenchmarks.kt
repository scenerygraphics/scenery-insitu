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
import org.lwjgl.util.lz4.LZ4.*
import org.lwjgl.util.lz4.LZ4Frame.LZ4F_getErrorName
import org.lwjgl.util.lz4.LZ4Frame.LZ4F_isError
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

    enum class CompressionTool {
        ZSTD,
        LZ4,
        Snappy
    }

    companion object {

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

//        @JvmStatic
//        fun compressZSTD_(color: ByteBuffer, depth: ByteBuffer, compressedColor: ByteBuffer, compressedDepth: ByteBuffer) {
//            val colorSize = ZSTD_compress(color, compressedColor, ZSTD_CLEVEL_DEFAULT) //TODO: min_CLevel produces faster compression
//            compressedColor.limit(colorSize.toInt())
//            compressedColor = compressedColor.slice()
//
//            val depthSize = ZSTD_compress(depth, compressedDepth, ZSTD_CLEVEL_DEFAULT)
//            compressedDepth.limit(depthSize.toInt())
//            compressedDepth = compressedDepth.slice()
//
//            println("ZSTD LWJGL! color size: ${colorSize.toFloat()/(1024f*1024f)} and depth size: ${depthSize.toFloat()/(1024f*1024f)}")
//            println("color buffer size: ${compressedColor.remaining().toFloat()/(1024f*1024f)} and depth buffer size: ${compressedDepth.remaining().toFloat()/(1024f*1024f)}")
//        }

        @JvmStatic
        fun compressSnappy(compressed: ByteBuffer, uncompressed: ByteBuffer): Long {
            return Snappy.compress(uncompressed, compressed).toLong()
        }

        @JvmStatic
        fun decompressSnappy(decompressed: ByteBuffer, compressed: ByteBuffer): Long {
            return Snappy.uncompress(compressed, decompressed).toLong()
        }

        @JvmStatic
        fun compressZSTD(compressed: ByteBuffer, uncompressed: ByteBuffer, level: Int): Long {
            return checkZSTD(ZSTD_compress(compressed, uncompressed, level))
        }

        @JvmStatic
        fun decompressZSTD(decompressed: ByteBuffer, compressed: ByteBuffer): Long {
            checkZSTD(ZSTD_findDecompressedSize(compressed))
            return ZSTD_decompress(decompressed, compressed)
        }

        @JvmStatic
        fun compressLZ4(compressed: ByteBuffer, uncompressed: ByteBuffer, level: Int): Long {
            return checkLZ4F(LZ4_compress_fast(uncompressed, compressed, level).toLong())
        }

        @JvmStatic
        fun decompressLZ4(decompressed: ByteBuffer, compressed: ByteBuffer): Long {
            return checkLZ4F(LZ4_decompress_safe(compressed, decompressed).toLong())
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

        private fun checkLZ4F(errorCode: Long): Long {
            check(!LZ4F_isError(errorCode)) { "LZ4 error: " + errorCode + " | " + LZ4F_getErrorName(errorCode) }
            return errorCode
        }

        private fun verifyDecompressed(uncompressed: ByteBuffer, decompressed: ByteBuffer) {
            val decompressedSize = decompressed.remaining().toLong()
            check(
                decompressedSize == uncompressed.remaining().toLong()
            ) {
                String.format(
                    "Decompressed size %d != uncompressed size %d",
                    decompressedSize,
                    uncompressed.remaining()
                )
            }
            println("uncompressed size: ${uncompressed.remaining()} and decompressed size: $decompressedSize")
            for (i in 0 until uncompressed.remaining()) {
                check(decompressed[i] == uncompressed[i]) { "Decompressed != uncompressed at: $i" }
//                if (i % 1000000 == 0) {
//                    println("Byte: $i Decompressed: ${decompressed[i]} uncompressed: ${uncompressed[i]}")
//                }
            }
        }

        @JvmStatic
        fun compress(compressed: ByteBuffer, uncompressed: ByteBuffer, level: Int, compressionTool: CompressionTool): Long {
            return if (compressionTool == CompressionTool.ZSTD) {
                compressZSTD(compressed, uncompressed, level)
            } else if (compressionTool == CompressionTool.LZ4) {
                compressLZ4(compressed, uncompressed, level)
            } else if (compressionTool == CompressionTool.Snappy) {
                compressSnappy(compressed, uncompressed)
            } else {
                -1
            }
        }

        @JvmStatic
        fun decompress(decompressed: ByteBuffer, compressed: ByteBuffer, compressionTool: CompressionTool): Long {
            return if(compressionTool == CompressionTool.ZSTD) {
                decompressZSTD(decompressed, compressed)
            } else if(compressionTool == CompressionTool.LZ4) {
                decompressLZ4(decompressed, compressed)
            } else if (compressionTool == CompressionTool.Snappy) {
                decompressSnappy(decompressed, compressed)
            } else {
                -1
            }
        }

        @JvmStatic
        fun runBenchmark(color: ByteBuffer, depth: ByteBuffer, level: Int, compressionTool: CompressionTool, iterations: Int = 100) {
            var compressedColor:  ByteBuffer? = null
            var compressedDepth: ByteBuffer? = null
            if(compressionTool ==  CompressionTool.ZSTD) {
                compressedColor =  memAlloc(ZSTD_COMPRESSBOUND(color.remaining().toLong()).toInt());
                compressedDepth = memAlloc(ZSTD_COMPRESSBOUND(depth.remaining().toLong()).toInt())
            } else if(compressionTool == CompressionTool.LZ4) {
                compressedColor = memAlloc(LZ4_compressBound(color.remaining()))
                compressedDepth = memAlloc(LZ4_compressBound(depth.remaining()))
            } else if (compressionTool == CompressionTool.Snappy) {
                compressedColor = memAlloc(Snappy.maxCompressedLength(color.remaining()))
                compressedDepth = memAlloc(Snappy.maxCompressedLength(depth.remaining()))
            }

            var decompressedColor = memAlloc(color.capacity() + 1024)
            var decompressedDepth = memAlloc(depth.capacity() + 1024)

            //first verify
            var colorCompressedSize = compress(compressedColor!!, color, level, compressionTool)
            compressedColor.limit(colorCompressedSize.toInt())
//            compressedColor = compressedColor.slice()

            var depthCompressedSize = compress(compressedDepth!!, depth, level, compressionTool)
            compressedDepth.limit(depthCompressedSize.toInt())
//            compressedDepth = compressedDepth.slice()

            var colorDecompressedSize =  decompress(decompressedColor, compressedColor.slice(), compressionTool)
            decompressedColor.limit(colorDecompressedSize.toInt())

            var depthDecompressedSize =  decompress(decompressedDepth, compressedDepth.slice(), compressionTool)
            decompressedDepth.limit(depthDecompressedSize.toInt())

            println("Compressed color buffer size: ${colorCompressedSize.toFloat() / (1024f * 1024f)} MB")
            println("Verifying color compression")
            verifyDecompressed(color, decompressedColor)
            compressedColor.limit(compressedColor.capacity())

            println("Compressed depth buffer size: ${depthCompressedSize.toFloat() / (1024f * 1024f)} MB")
            println("Verifying depth compression")
            verifyDecompressed(depth, decompressedDepth)
            compressedDepth.limit(compressedDepth.capacity())

            var totalCompressionTime = 0.0
            var totalDecompressionTime = 0.0

            //start the benchmarks
            for (i in 1..100) {
//                compressedColor!!.limit(compressedColor.capacity())
//                compressedColor = compressedColor.slice()
                compressedColor!!.position(0)
//                compressedDepth!!.limit(compressedDepth.capacity())
//                compressedDepth = compressedDepth.slice()
                compressedDepth!!.position(0)

//                println("Iteration: $i color buf has: ${compressedColor!!.remaining()} depth buf has: ${compressedDepth!!.remaining()}")
//                println("Color requirement: ${ZSTD_COMPRESSBOUND(color.remaining().toLong())}")

                val compressionTime = measureNanoTime {
                    colorCompressedSize = compress(compressedColor!!, color, level, compressionTool)
                    compressedColor.limit(colorCompressedSize.toInt())

                    depthCompressedSize = compress(compressedDepth!!, depth, level, compressionTool)
                    compressedDepth.limit(depthCompressedSize.toInt())
                }

                val decompressionTime = measureNanoTime {
                    colorDecompressedSize =  decompress(decompressedColor, compressedColor.slice(), compressionTool)

                    depthDecompressedSize =  decompress(decompressedDepth, compressedDepth.slice(), compressionTool)
                }

                compressedColor.limit(compressedColor.capacity())
                compressedDepth.limit(compressedDepth.capacity())

                if(i % 10 == 0) {
                    println("Compression time: ${compressionTime/1e9} and decompression time: ${decompressionTime/1e9}")
                    println("Compressed size: Color: ${colorCompressedSize.toFloat() / (1024f * 1024f)} MB and Depth: ${depthCompressedSize.toFloat() / (1024f * 1024f)} MB")
                }
                totalCompressionTime += compressionTime/1e9
                totalDecompressionTime += decompressionTime/1e9
            }

            println("Average compression time: ${totalCompressionTime / iterations.toDouble()}")
            println("Average decompression time: ${totalDecompressionTime / iterations.toDouble()}")
            println("Average overall: ${(totalDecompressionTime + totalCompressionTime) / iterations.toDouble()}")
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

            runBenchmark(colBuffer, depthBuffer, 0, CompressionTool.Snappy)

        }
    }

}