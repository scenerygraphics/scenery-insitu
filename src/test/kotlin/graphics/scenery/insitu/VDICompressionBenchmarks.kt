package graphics.scenery.insitu

import com.esotericsoftware.kryo.io.ByteBufferOutputStream
import graphics.scenery.volumes.vdi.VDIDataIO
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor
import net.jpountz.lz4.LZ4SafeDecompressor
import org.lwjgl.system.MemoryUtil
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorOutputStream
import org.apache.commons.compress.compressors.snappy.SnappyCompressorOutputStream
import org.xerial.snappy.Snappy
import org.xerial.snappy.SnappyFramedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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
        }

        @JvmStatic
        fun compressLZ4Apache(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
            colorOut.write(color)
            val fColor = FramedLZ4CompressorOutputStream(colorOut)
//            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = FramedLZ4CompressorOutputStream(depthOut)
//            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressSnappyApacheFramed(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
            colorOut.write(color)
            val fColor = FramedSnappyCompressorOutputStream(colorOut)
//            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = FramedSnappyCompressorOutputStream(depthOut)
//            fDepth.write(depth)

            fDepth.flush()

            fColor.close()
            fDepth.close()
        }

        @JvmStatic
        fun compressSnappyApache(color: ByteArray, depth: ByteArray) {
            val colorOut = ByteArrayOutputStream(color.size)
            colorOut.write(color)
            val fColor = SnappyCompressorOutputStream(colorOut, color.size.toLong(), 32768 / 2)
//            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = SnappyCompressorOutputStream(depthOut, depth.size.toLong(), 32768 / 2)
//            fDepth.write(depth)

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
            colorOut.write(color)
            val fColor = LZMACompressorOutputStream(colorOut)
//            fColor.write(color, 0, color.size)

            fColor.flush()

            val depthOut = ByteArrayOutputStream(depth.size)
            depthOut.write(depth)
            val fDepth = LZMACompressorOutputStream(depthOut)
//            fDepth.write(depth)

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

            val totalTime = measureNanoTime {
                while (cnt < iterations) {
                    cnt++

                    val compTime = measureNanoTime {
//                        compressSnappy(colBuffer, depthBuffer, 6)
                        compressSnappy(buff, depthBuff)
//                        compressLZ4(buff, depthBuff)
//                        compressLZ4Apache(buff, depthBuff)
//                        compressSnappyApacheFramed(buff, depthBuff)
//                        compressSnappyApache(buff, depthBuff)
//                        compressLZ4Apache(colBuffer, depthBuffer)
//                        compressLZMA(buff, depthBuff)
//                        compressGzip(buff, depthBuff)
                    }


                    if(cnt % 10 == 0) {
                        println("iteration: $cnt the compression took: ${compTime/1e9}")
                    }
                }
            }

            println("Overall average = ${totalTime/(1e9 * iterations)}")

        }
    }

}