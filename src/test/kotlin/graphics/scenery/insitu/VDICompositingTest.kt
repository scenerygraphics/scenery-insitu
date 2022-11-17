package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.backends.vulkan.VulkanTexture
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.volumes.vdi.VDIDataIO
import net.imglib2.type.numeric.real.FloatType
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class VDICompositingTest: SceneryBase("VDICompositingTest", windowWidth = 1280, windowHeight = 720, wantREPL = false) {

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

    val compositor = CompositorNode()
    val maxCompositedSupersegments = 20
    val maxSupersegments = 20
    var commSize = 1
    var isCluster = false
    var nodeRank = 0
    var rank = 0

    var mpiPointer = 0L
    var allToAllColorPointer: Long = 0
    var allToAllDepthPointer: Long = 0
    var gatherColorPointer: Long = 0
    var gatherDepthPointer: Long = 0

    var vdisComposited = AtomicInteger(0)
    var vdisDistributed = AtomicInteger(0)

    var dataset = ""
    var basePath = ""
    val benchmarking = false

    @Volatile
    var rendererConfigured = false

    @Volatile
    var runCompositing = false

    private external fun distributeVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, sizePerProcess: Int, commSize: Int,
                                        colPointer: Long, depthPointer: Long, mpiPointer: Long)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, compositedVDIDepth: ByteBuffer, compositedVDILen: Int, root: Int, myRank: Int, commSize: Int,
                                              colPointer: Long, depthPointer: Long, mpiPointer: Long)

    override fun init() {

        logger.info("setting renderer device id to: $nodeRank")
        System.setProperty("scenery.Renderer.DeviceId", nodeRank.toString())

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        logger.info("renderer has been set up!")

        val cam: Camera = DetachedHeadCamera()

        with(cam) {
            spatial {
                position = Vector3f( 4.622E+0f, -9.060E-1f, -1.047E+0f)
                rotation = Quaternionf( 5.288E-2, -9.096E-1, -1.222E-1,  3.936E-1)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            cam.farPlaneDistance = 20.0f

            scene.addChild(this)
        }

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        compositor.name = "compositor node"
        compositor.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("VDICompositor.comp"), this@VDICompositingTest::class.java)))

        val outputColours = MemoryUtil.memCalloc(maxCompositedSupersegments*windowHeight*windowWidth*4*4 / commSize)
        val outputDepths = MemoryUtil.memCalloc(maxCompositedSupersegments*windowHeight*windowWidth*4*2 / commSize)
        val compositedVDIColor = Texture.fromImage(
            Image(outputColours, maxCompositedSupersegments, windowHeight,  windowWidth/commSize), channels = 4, usage = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
        val compositedVDIDepth = Texture.fromImage(
            Image(outputDepths, 2 * maxCompositedSupersegments, windowHeight,  windowWidth/commSize), channels = 1, usage = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
            , type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
        compositor.material().textures["CompositedVDIColor"] = compositedVDIColor
        compositor.material().textures["CompositedVDIDepth"] = compositedVDIDepth

        compositor.metadata["ComputeMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth/commSize, windowHeight, 1)
        )

        basePath = if(isCluster) {
            "/scratch/ws/1/argupta-vdi_generation/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        logger.info("Got dataset value: $dataset")

        val file = FileInputStream(File(basePath + "${dataset}_${commSize}_${rank}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_0_dump4"))
        val vdiData = VDIDataIO.read(file)

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compositor.numProcesses = commSize

        compositor.visible = true
        scene.addChild(compositor)

        rendererConfigured = true
    }

    @Suppress("unused")
    fun rendererReady() {
        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }
    }

    fun fetchTexture(texture: Texture) : Int {
        val ref = VulkanTexture.getReference(texture)
        val buffer = texture.contents ?: return -1

        if(ref != null) {
            val start = System.nanoTime()
//            texture.contents = ref.copyTo(buffer, true)
            ref.copyTo(buffer, false)
            val end = System.nanoTime()
            logger.info("The request textures of size ${texture.contents?.remaining()?.toFloat()?.div((1024f*1024f))} took: ${(end.toDouble()-start.toDouble())/1000000.0}")
        } else {
            logger.error("In fetchTexture: Texture not accessible")
        }

        return 0
    }

    @Suppress("unused")
    fun compositeVDIs(subVDIColorBuffer: ByteBuffer, subVDIDepthBuffer: ByteBuffer, rank: Int, iterations: Int) {

        val compositedColorTex = compositor.material().textures["CompositedVDIColor"]!!
        val compositedDepthTex = compositor.material().textures["CompositedVDIDepth"]!!

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runCompositing) {


                val col = fetchTexture(compositedColorTex)
                val depth = fetchTexture(compositedDepthTex)

                if(col < 0) {
                    logger.error("Error fetching the color compositedVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth compositedVDI!!")
                }

                vdisComposited.incrementAndGet()
                runCompositing = false
            }

            if(vdisDistributed.get() > vdisComposited.get()) {
                runCompositing = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runCompositing) {
                logger.info("SETTING DO_COMPOSITE TO TRUE!")
            }
            compositor.doComposite = runCompositing
        }

        var compositedSoFar = 0//vdisComposited.get()
        for (i in 1..iterations) {

            distributeVDIs(subVDIColorBuffer, subVDIDepthBuffer, windowHeight * windowWidth * maxSupersegments * 4 / commSize, commSize, allToAllColorPointer,
                allToAllDepthPointer, mpiPointer)

            while (vdisComposited.get() <= compositedSoFar) {
                Thread.sleep(5)
            }

            compositedSoFar = vdisComposited.get()

            val compositedVDIColorBuffer = compositedColorTex.contents
            val compositedVDIDepthBuffer = compositedDepthTex.contents

            if(!benchmarking) {
                logger.info("Dumping to file before gather")
                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, basePath + "CompositedVDI${vdisDistributed.get()}_ndc_col")
                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, basePath + "CompositedVDI${vdisDistributed.get()}_ndc_depth")
            }

            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxCompositedSupersegments * 4 / commSize, 0,
                rank, commSize, gatherColorPointer, gatherDepthPointer, mpiPointer)
        }
    }

    @Suppress("unused")
    fun uploadForCompositing(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer) {

        if(!benchmarking) {
            logger.info("Dumping to file in the uploadFromCompositing function")
            SystemHelpers.dumpToFile(vdiSetColour, basePath + "CompositingTestSetOfVDI${vdisDistributed.get()}_ndc_col")
            SystemHelpers.dumpToFile(vdiSetDepth, basePath + "CompositingTestSetOfVDI${vdisDistributed.get()}_ndc_depth")
            logger.info("File dumped")
        }

        compositor.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight, windowWidth), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)


        compositor.material().textures["VDIsDepth"] = Texture(Vector3i(2 * maxSupersegments, windowHeight, windowWidth), 1, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)


        vdisDistributed.incrementAndGet()
    }

    @Suppress("unused")
    fun stopRendering() {
        renderer?.shouldClose = true
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VDICompositingTest().main()
        }
    }

}