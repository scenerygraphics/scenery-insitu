package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.real.FloatType
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

class DistributeAndPrefixTest: SceneryBase("PrefixTest", windowWidth = 1280, windowHeight = 720) {

    val computeSupsegNums = RichNode()
    val computePrefix = RichNode()

    val dataset = System.getProperty("VolumeBenchmark.Dataset")?.toString()?: "Kingsnake"
    val vo = System.getProperty("VolumeBenchmark.Vo")?.toFloat()?.toInt() ?: 0
    val maxSupersegments = System.getProperty("VolumeBenchmark.NumSupersegments")?.toInt()?: 20

    override fun init() {
        renderer = hub.add(
            SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        computeSupsegNums.name = "distribute_supsegs_node"

        val basePath = "/home/aryaman/Repositories/scenery-insitu/"

        val qErrors = File(basePath + "${dataset}VDI_${windowWidth}_${windowHeight}_${maxSupersegments}_${vo}_4_ndc_quantization_errors").readBytes()

        val qErrorsBuffer: ByteBuffer

        qErrorsBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

        qErrorsBuffer.put(qErrors).flip()

        val outputBuffer = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)

        computeSupsegNums.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("DistributeSupersegments.comp"), this@DistributeAndPrefixTest::class.java))) {
            textures["SupersegmentNumbers"] = Texture.fromImage(
                Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = IntType(), channels = 1, mipmap = false, normalized = false,
                minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            textures["SupersegmentNumbers"]!!.mipmap = false
        }

        computeSupsegNums.metadata["DistributionMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth, windowHeight, 1),
            invocationType = InvocationType.Permanent
        )

        computeSupsegNums.material().textures["QuantizationErrors"] = Texture(
            Vector3i(windowWidth, windowHeight, 1), 1, contents = qErrorsBuffer, usageType = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
            , type = FloatType(),
            mipmap = false,
            minFilter = Texture.FilteringMode.NearestNeighbour,
            maxFilter = Texture.FilteringMode.NearestNeighbour
        )

        scene.addChild(computeSupsegNums)

        computePrefix.name = "prefix_node"

        val prefixBuffer = MemoryUtil.memCalloc(windowWidth * windowHeight * 4)

        computePrefix.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("prefix_naive.comp"), this@DistributeAndPrefixTest::class.java))) {
            textures["OutBuf"] = Texture.fromImage(
                Image(prefixBuffer, windowWidth, windowHeight), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = IntType(), channels = 1, mipmap = false, normalized = false,
                minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            textures["OutBuf"]!!.mipmap = false
        }

        computePrefix.metadata["PrefixMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth, windowHeight, 1),
            invocationType = InvocationType.Permanent
        )

        computePrefix.material().textures["InBuf"] = computeSupsegNums.material().textures["SupersegmentNumbers"]!!

        scene.addChild(computePrefix)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial {
                position = Vector3f(0.0f, 0.0f, 5.0f)
            }
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            manageTextures()
        }

        logger.info("Finished init")
    }

    fun manageTextures() {
        var distributionBuff: ByteBuffer?
        var prefixBuff: ByteBuffer?

        logger.info("in manage textures")

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        logger.info("first image ready")

        val distTex = computeSupsegNums.material().textures["SupersegmentNumbers"]!!
        val counter1 = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (distTex to counter1)

        val counter2 = AtomicInteger(0)

        var prefixTex: Texture = computePrefix.material().textures["OutBuf"]!!
        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (prefixTex to counter2)

        var prev1 = counter1.get()
        var prev2 = counter2.get()

        while (true) {
            while (counter1.get() == prev1 || counter2.get() == prev2) {
                Thread.sleep(50)
                logger.info("Waiting")
            }
            prev1 = counter1.get()
            prev2 = counter2.get()

            distributionBuff = distTex.contents
//            prefixBuff = prefixTex.contents

            val distributionIntBuff = distributionBuff!!.asIntBuffer()

            val prefixTime = measureNanoTime {
                prefixBuff = MemoryUtil.memAlloc(1280*720 * 4)
                val prefixIntBuff = prefixBuff!!.asIntBuffer()

                prefixIntBuff.put(0, distributionIntBuff.get(0))

                for(i in 1 until 1280*720) {
                    prefixIntBuff.put(i, prefixIntBuff.get(i-1) + distributionIntBuff.get(i))
//                    if(i%100 == 0) {
//                        logger.info("i: $i. The numbers added were: ${prefixIntBuff.get(i-1)} and ${distributionIntBuff.get(i)}")
//                    }
                }
            }

            logger.info("Prefix array was calculated in ${prefixTime/1e9}s")

            SystemHelpers.dumpToFile(distributionBuff!!, "distribution")
            SystemHelpers.dumpToFile(prefixBuff!!, "prefix")

            logger.info("Wrote the files")

        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DistributeAndPrefixTest().main()
        }
    }
}