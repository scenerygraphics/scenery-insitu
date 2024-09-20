package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.real.FloatType
import org.joml.Matrix4f
import org.joml.Vector2i
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import org.scijava.ui.behaviour.ClickBehaviour
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.system.measureNanoTime

class VDICompositingExample:SceneryBase("VDIComposite", 1280, 720) {

    private val vulkanProjectionFix =
        Matrix4f(
            1.0f,  0.0f, 0.0f, 0.0f,
            0.0f, -1.0f, 0.0f, 0.0f,
            0.0f,  0.0f, 0.5f, 0.0f,
            0.0f,  0.0f, 0.5f, 1.0f)

    fun Matrix4f.applyVulkanCoordinateSystem(): Matrix4f {
        val m = Matrix4f(vulkanProjectionFix)
        m.mul(this)

        return m
    }

    val closeAfter = 25000L

    val separateDepth = true
    val denseVDI = true

    val compute = CompositorNode()
    var dataset = "Kingsnake"
    val maxSupersegments = 20

    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val commSize = 1
        val rank = 0

        dataset += "_${commSize}_${rank}"

//        val basePath = "/home/aryaman/Repositories/DistributedVis/cmake-build-debug/"
//        val basePath = "/home/aryaman/Repositories/scenery-insitu/"
        val basePath = "/home/aryaman/TestingData/"
//        val basePath = "/home/aryaman/TestingData/FromCluster/"

        val file = FileInputStream(File(basePath + "${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_0_dump4"))

        val vdiData = VDIDataIO.read(file)

        val buff = if(denseVDI) {
            File(basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_4_ndc_col_rle").readBytes()
        } else {
            File(basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_4_ndc_col").readBytes()
        }

        val depthBuff = if(denseVDI) {
            File(basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_4_ndc_depth_rle").readBytes()
        } else {
            File(basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_4_ndc_depth").readBytes()
        }

        var colBuffer: ByteBuffer
        var depthBuffer: ByteBuffer
//        colBuffer = ByteBuffer.wrap(buff)
//        depthBuffer = ByteBuffer.wrap(depthBuff)
        colBuffer = MemoryUtil.memCalloc(buff.size)
        depthBuffer = MemoryUtil.memCalloc(depthBuff.size)

        logger.info("Length of color buffer is ${buff.size} and associated bytebuffer capacity is ${colBuffer.capacity()} it has remaining: ${colBuffer.remaining()}")
        logger.info("Length of depth buffer is ${depthBuff.size} and associated bytebuffer capacity is ${depthBuffer.capacity()} it has remianing ${depthBuffer.remaining()}")
        colBuffer.put(buff).flip()
        depthBuffer.put(depthBuff).flip()


        val maxOutputSupersegments = 20

        compute.name = "compositor node"

        compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("VDICompositor.comp"), this@VDICompositingExample::class.java)))
        val outputColours = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*4 / commSize)
        val compositedVDIColor = Texture.fromImage(Image(outputColours, maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 4, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        compute.material().textures["CompositedVDIColor"] = compositedVDIColor

        val outputDepths = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*2 / commSize)
        val compositedVDIDepth = Texture.fromImage(Image(outputDepths, 2 * maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 1, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
            , type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        compute.material().textures["CompositedVDIDepth"] = compositedVDIDepth

        compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(windowWidth/commSize, windowHeight, 1)
        )

        compute.nw = vdiData.metadata.nw
        compute.ViewOriginal = vdiData.metadata.view
        compute.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compute.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compute.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compute.doComposite = true
        compute.numProcesses = commSize
        compute.windowHeight = windowHeight
        compute.windowWidth = windowWidth
        compute.isDense = denseVDI

        if(denseVDI) {

            val totalMaxSupersegments = buff.size / (4*4).toFloat()

            logger.info("total max supsegs (including padding): $totalMaxSupersegments")

            compute.material().textures["VDIsColor"] = Texture(Vector3i(512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            compute.material().textures["VDIsDepth"] = Texture(Vector3i(2*512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()),  channels = 1, contents = depthBuffer, usageType = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            for (i in 0 until commSize) {
                compute.totalSupersegmentsFrom[i] = 2433894
            }

        } else {

            compute.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight, windowWidth), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            compute.material().textures["VDIsDepth"] = Texture(Vector3i(2*maxSupersegments, windowHeight, windowWidth),  channels = 1, contents = depthBuffer, usageType = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
        }

        if(denseVDI) {
            val prefixArray: ByteArray = File(basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_4_ndc_prefix").readBytes()

            val prefixBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

            prefixBuffer.put(prefixArray).flip()

            compute.material().textures["VDIsPrefix"] = Texture(Vector3i(windowHeight, windowWidth, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = IntType(), mipmap = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
        }


        logger.info("Updated the ip textured")

        compute.visible = true
        scene.addChild(compute)

        val light = PointLight(radius = 15.0f)
        light.spatial().position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial().position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            storeCompositedVDIs()
        }

        thread {
            Thread.sleep(closeAfter)
            renderer?.shouldClose = true
        }

    }

    fun storeCompositedVDIs() {
        var compositedVDIDepthBuffer: ByteBuffer? = null
        var compositedVDIColorBuffer: ByteBuffer?

        while(renderer?.firstImageReady == false) {
//        while(renderer?.firstImageReady == false || volumeManager.shaderProperties.isEmpty()) {
            Thread.sleep(50)
        }

        val compositedVDIColor = compute.material().textures["CompositedVDIColor"]!!
        val colorCounter = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (compositedVDIColor to colorCounter)

        val depthCounter = AtomicInteger(0)
        var compositedVDIDepth: Texture? = null

        if(separateDepth) {
            compositedVDIDepth = compute.material().textures["CompositedVDIDepth"]!!
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (compositedVDIDepth to depthCounter)
        }

        var prevAtomic = colorCounter.get()

        var cnt = 0

        val tGeneration = Timer(0, 0)

        while (true) {
            tGeneration.start = System.nanoTime()
            while(colorCounter.get() == prevAtomic) {
                Thread.sleep(5)
            }
            prevAtomic = colorCounter.get()
            compositedVDIColorBuffer = compositedVDIColor.contents
            if(separateDepth) {
                compositedVDIDepthBuffer = compositedVDIDepth!!.contents
            }

            tGeneration.end = System.nanoTime()

            val timeTaken = tGeneration.end - tGeneration.start

            logger.info("Time taken for generation (only correct if VDIs were not being written to disk): ${timeTaken}")

            if(cnt == 4) {

                var fileName = ""
                fileName = "${dataset}CompositedVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt}_ndc"
                if(separateDepth) {
                    SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, "/home/aryaman/TestingData/${fileName}_col")
                    SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, "/home/aryaman/TestingData/${fileName}_depth")
                } else {
                    SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, fileName)
                }
                logger.info("Wrote VDI $cnt")
            }
            cnt++
        }
    }

    override fun inputSetup() {
        super.inputSetup()
        //TODO: modify the below for new scenery syntax
//
//        inputHandler?.addBehaviour("save_texture", ClickBehaviour { _, _ ->
//            logger.info("Finding node")
//            val node = scene.find("compositor node") ?: return@ClickBehaviour
//            val texture = node.material.textures["CompositedVDIColor"]!!
//            val textureDepth = node.material.textures["CompositedVDIDepth"]!!
//            val r = renderer ?: return@ClickBehaviour
//            logger.info("Node found, saving texture")
//
//            val result = r.requestTexture(texture) { tex ->
//                logger.info("Received texture")
//
//                tex.contents?.let { buffer ->
//                    logger.info("Dumping to file")
//                    SystemHelpers.dumpToFile(buffer, "/home/aryaman/Desktop/vdi_files/texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                    logger.info("File dumped")
//                }
//
//            }
//
//            val resultDepth = r.requestTexture(textureDepth) { tex ->
//                logger.info("Received depth texture")
//
//                tex.contents?.let { buffer ->
//                    logger.info("Dumping to file")
//                    SystemHelpers.dumpToFile(buffer, "/home/aryaman/Desktop/vdi_files/texture-${SystemHelpers.formatDateTime(delimiter = "_")}.raw")
//                    logger.info("File dumped")
//                }
//
//            }
//
//        })

        inputHandler?.addBehaviour("toggle_compositing", ClickBehaviour { _, _ ->
            toggleCompositing()
        })
        inputHandler?.addKeyBinding("toggle_compositing", "R")
    }

    fun toggleCompositing () {
        if(compute.doComposite) {
            logger.info("Setting doComposite to FALSE")
            compute.doComposite = false
        } else {
            logger.info("Setting to TRUE")
            compute.doComposite = true
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VDICompositingExample().main()
        }
    }
}