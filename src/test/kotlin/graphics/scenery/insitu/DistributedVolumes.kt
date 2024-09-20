package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.backends.vulkan.VulkanTexture
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.VDIBufferSizes
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileOutputStream
import java.lang.Math
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.system.measureNanoTime

class CompositorNode : RichNode() {
    @ShaderProperty
    var ProjectionOriginal = Matrix4f()

    @ShaderProperty
    var invProjectionOriginal = Matrix4f()

    @ShaderProperty
    var ViewOriginal = Matrix4f()

    @ShaderProperty
    var invViewOriginal = Matrix4f()

    @ShaderProperty
    var nw = 0f

    @ShaderProperty
    var doComposite = false

    @ShaderProperty
    var numProcesses = 0

    @ShaderProperty
    var isDense = true

    @ShaderProperty
    var windowWidth = 0

    @ShaderProperty
    var windowHeight = 0

    @ShaderProperty
    var totalSupersegmentsFrom = IntArray(50); // the total supersegments received from a given PE
}

class DistributedVolumes: SceneryBase("DistributedVolumeRenderer", windowWidth = 1280, windowHeight = 720, wantREPL = false) {

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

    var volumes: HashMap<Int, BufferedVolume?> = java.util.HashMap()

    var hmd: TrackedStereoGlasses? = null

    lateinit var volumeManager: VolumeManager
    lateinit var volumeCommons: VolumeCommons
    val compositor = CompositorNode()
    val plane = FullscreenObject()

    val generateVDIs = true
    val denseVDIs = true
    val separateDepth = true
    val colors32bit = true
    val saveFinal = true
    val benchmarking = false
    var cnt_distr = 0
    var cnt_sub = 0
    var vdisGathered = 0
    val cam: Camera = DetachedHeadCamera(hmd)
    var camTarget = Vector3f(0f)

    var vo = 0f

    val maxSupersegments = 20
    var maxOutputSupersegments = 20

    data class Timer(var start: Long, var end: Long)

    val tRend = Timer(0,0)
    val tComposite = Timer(0,0)

    var totalComposite: Double = 0.0
    var totalRender: Double = 0.0

    var numIterations: Int = 0

    val warmUpIterations = 20

    var commSize = 1
    var rank = 0
    var nodeRank = 0
    var pixelToWorld = 0.001f
    var volumeDims = Vector3f(0f)
    var dataset = ""
    var isCluster = false
    var basePath = ""
    var rendererConfigured = false

    var mpiPointer = 0L
    var allToAllColorPointer = 0L
    var allToAllDepthPointer = 0L
    var allToAllPrefixPointer = 0L
    var gatherColorPointer = 0L
    var gatherDepthPointer = 0L
    var imagePointer = 0L

    var volumesCreated = false
    var volumeManagerInitialized = false

    var vdisGenerated = AtomicInteger(0)
    var vdisDistributed = AtomicInteger(0)
    var vdisComposited = AtomicInteger(0)

    @Volatile
    var runGeneration = true
    @Volatile
    var runThreshSearch = true
    @Volatile
    var runCompositing = false

    val singleGPUBenchmarks = false
    val colorMap = Colormap.get("hot")

    var dims = Vector3i(0)

    private external fun distributeVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, sizePerProcess: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, mpiPointer: Long)
    private external fun distributeDenseVDIs(subVDIColor: ByteBuffer, subVDIDepth: ByteBuffer, prefixSums: ByteBuffer, supersegmentCounts: IntArray, commSize: Int,
                                        colPointer: Long, depthPointer: Long, prefixPointer: Long, mpiPointer: Long)
    private external fun gatherCompositedVDIs(compositedVDIColor: ByteBuffer, compositedVDIDepth: ByteBuffer, compositedVDILen: Int, root: Int, myRank: Int, commSize: Int,
        colPointer: Long, depthPointer: Long, vo: Int, mpiPointer: Long)
    private external fun compositeImages(subImage: ByteBuffer, myRank: Int, commSize: Int, imagePointer: Long)
    private external fun reduceAcrossPEs(value: Double) : Double

    @Suppress("unused")
    fun setVolumeDims(dims: IntArray) {
        volumeDims = Vector3f(dims[0].toFloat(), dims[1].toFloat(), dims[2].toFloat())
    }

    @Suppress("unused")
    fun addVolume(volumeID: Int, dimensions: IntArray, pos: FloatArray, is16bit: Boolean) {
        logger.info("Trying to add the volume")
        logger.info("id: $volumeID, dims: ${dimensions[0]}, ${dimensions[1]}, ${dimensions[2]} pos: ${pos[0]}, ${pos[1]}, ${pos[2]}")

        while(!volumeManagerInitialized) {
            Thread.sleep(50)
        }

        val volume = if(is16bit) {
            Volume.fromBuffer(emptyList(), dimensions[0], dimensions[1], dimensions[2], UnsignedShortType(), hub)
        } else {
            Volume.fromBuffer(emptyList(), dimensions[0], dimensions[1], dimensions[2], UnsignedByteType(), hub)
        }
        volume.spatial().position = Vector3f(pos[0], pos[1], pos[2])

        volume.origin = Origin.FrontBottomLeft
        volume.spatial().needsUpdate = true
        volume.colormap = colorMap
        volume.pixelToWorldRatio = pixelToWorld

        val tf = TransferFunction()
        with(tf) {
            if(dataset == "Stagbeetle" || dataset == "Stagbeetle_divided") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.005f, 0.0f)
                addControlPoint(0.01f, 0.3f)
            } else if (dataset == "Kingsnake") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.43f, 0.0f)
                addControlPoint(0.5f, 0.5f)
            } else if (dataset == "Beechnut") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.43f, 0.0f)
                addControlPoint(0.457f, 0.321f)
                addControlPoint(0.494f, 0.0f)
                addControlPoint(1.0f, 0.0f)
            } else if (dataset == "Simulation") {
                addControlPoint(0.0f, 0f)
                addControlPoint(0.1f, 0.0f)
                addControlPoint(0.15f, 0.65f)
                addControlPoint(0.22f, 0.15f)
                addControlPoint(0.28f, 0.0f)
                addControlPoint(0.49f, 0.0f)
                addControlPoint(0.7f, 0.95f)
                addControlPoint(0.75f, 0.8f)
                addControlPoint(0.8f, 0.0f)
                addControlPoint(0.9f, 0.0f)
                addControlPoint(1.0f, 0.0f)
            } else if (dataset == "Rayleigh_Taylor") {
                addControlPoint(0.0f, 0.95f)
                addControlPoint(0.15f, 0.0f)
                addControlPoint(0.45f, 0.0f)
                addControlPoint(0.5f, 0.35f)
                addControlPoint(0.55f, 0.0f)
                addControlPoint(0.80f, 0.0f)
                addControlPoint(1.0f, 0.378f)
            } else if (dataset == "Microscopy") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.5f, 0.0f)
                addControlPoint(0.65f, 0.0f)
                addControlPoint(0.80f, 0.2f)
                addControlPoint(0.85f, 0.0f)
            } else if (dataset == "Rotstrat") {
                addControlPoint(0.0f, 0.0f)
                addControlPoint(0.4f, 0.0f)
                addControlPoint(0.6f, 0.01f)
                addControlPoint(1.0f, 0.05f)
            } else if (dataset == "Isotropic") {
                addControlPoint(0.0f, 0.01f)
                addControlPoint(0.1f, 0.01f)
                addControlPoint(0.5f, 0.0f)
                addControlPoint(0.6f, 0.0f)
                addControlPoint(0.65f, 0.0f)
                addControlPoint(0.7f, 0.0f)
                addControlPoint(0.8f, 0.5f)
            } else {
                logger.info("Using a standard transfer function")
                addControlPoint(0.0f, 0.0f)
                addControlPoint(1.0f, 1.0f)

            }
        }

        volume.name = "volume"
        volume.colormap = Colormap.get("hot")
        if(dataset == "Rotstrat") {
            volume.colormap = Colormap.get("viridis")
            volume.converterSetups[0].setDisplayRange(13000.0, 57000.0)
        } else if(dataset == "Beechnut") {
            volume.converterSetups[0].setDisplayRange(0.0, 33465.0)
        } else if(dataset == "Simulation") {
            volume.colormap = Colormap.get("rb")
            volume.converterSetups[0].setDisplayRange(50.0, 205.0)
        } else if(dataset == "Rayleigh_Taylor") {
            volume.colormap = Colormap.get("rbdarker")
        } else  if(dataset == "Isotropic") {
            volume.colormap = Colormap.get("rb")
            volume.converterSetups[0].setDisplayRange(32000.0, 60000.0)
        }

        volume.transferFunction = tf

        if(dataset.contains("BonePlug")) {
            volume.converterSetups[0].setDisplayRange(200.0, 12500.0)
            volume.colormap = Colormap.get("viridis")
        }

        scene.addChild(volume)

        volumes[volumeID] = volume


        volumesCreated = true
    }

    @Suppress("unused")
    fun updateVolume(volumeID: Int, buffer: ByteBuffer) {
        while(volumes[volumeID] == null) {
            Thread.sleep(50)
        }
        logger.info("Volume $volumeID has been updated")
        volumes[volumeID]?.addTimepoint("t", buffer)
        volumes[volumeID]?.goToLastTimepoint()
    }

    fun setupCompositor(compositeShader: String, dense: Boolean = false) {

        compositor.name = "compositor node"
        compositor.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf(compositeShader), this@DistributedVolumes::class.java)))
        if(generateVDIs) {
            val outputColours = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*4 / commSize)
            val outputDepths = MemoryUtil.memCalloc(maxOutputSupersegments*windowHeight*windowWidth*4*2 / commSize)
            val compositedVDIColor = Texture.fromImage(Image(outputColours, maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 4, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            val compositedVDIDepth = Texture.fromImage(Image(outputDepths, 2 * maxOutputSupersegments, windowHeight,  windowWidth/commSize), channels = 1, usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            compositor.material().textures["CompositedVDIColor"] = compositedVDIColor
            compositor.material().textures["CompositedVDIDepth"] = compositedVDIDepth
        } else {
            val outputColours = MemoryUtil.memCalloc(windowHeight*windowWidth*4 / commSize)
            val alphaComposited = Texture.fromImage(Image(outputColours, windowHeight,  windowWidth/commSize), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            compositor.material().textures["AlphaComposited"] = alphaComposited
        }
        compositor.metadata["ComputeMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth/commSize, windowHeight, 1)
        )
        compositor.windowWidth = windowWidth
        compositor.windowHeight = windowHeight
        compositor.isDense = dense

        if(!dense) {
            val prefixPlaceHolder = MemoryUtil.memCalloc(1 * 1 * 4)
            compositor.material().textures["VDIsPrefix"] = Texture(Vector3i(1, 1, 1), 1, contents = prefixPlaceHolder, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = IntType(), mipmap = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour
            )
        }

        if(!singleGPUBenchmarks) {
            compositor.visible = true
            scene.addChild(compositor)
        } else {
            compositor.visible = false
        }
    }

    override fun init() {

        logger.info("setting renderer device id to: $nodeRank")
        System.setProperty("scenery.Renderer.DeviceId", nodeRank.toString())

        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        if(generateVDIs) {
            //set up volume manager

            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, maxSupersegments, scene, hub, denseVDIs)

            hub.add(volumeManager)

            if(denseVDIs) {
                runThreshSearch = true
                runGeneration = false

            } else {
                runGeneration = true
            }

            setupCompositor("VDICompositor.comp", denseVDIs)

        } else {
            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, scene, hub, setupPlane = false)

            scene.addChild(plane)

            val emptyBB = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

            plane.material().textures["diffuse"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = emptyBB, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                type = UnsignedByteType(), mipmap = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour
            )

            hub.add(volumeManager)
        }


        volumeManagerInitialized = true

        volumeCommons = VolumeCommons(windowWidth, windowHeight, dataset, logger)

        with(cam) {
            spatial {
                if(dataset == "Kingsnake") {
                    position = Vector3f( 4.622E+0f, -9.060E-1f, -1.047E+0f) //V1 for kingsnake
                    rotation = Quaternionf( 5.288E-2, -9.096E-1, -1.222E-1,  3.936E-1)
                } else if (dataset == "Beechnut") {
                    position = Vector3f(-2.607E+0f, -5.973E-1f,  2.415E+0f) // V1 for Beechnut
                    rotation = Quaternionf(-9.418E-2, -7.363E-1, -1.048E-1, -6.618E-1)
                } else if (dataset == "Simulation") {
                    logger.info("Using simulation camera coordinates")
                    position = Vector3f(2.041E-1f, -5.253E+0f, -1.321E+0f) //V1 for Simulation
                    rotation = Quaternionf(9.134E-2, -9.009E-1,  3.558E-1, -2.313E-1)
                } else if (dataset == "Rayleigh_Taylor") {
                    position = Vector3f( -2.300E+0f, -6.402E+0f,  1.100E+0f) //V1 for Rayleigh_Taylor
                    rotation = Quaternionf(2.495E-1, -7.098E-1,  3.027E-1, -5.851E-1)
                } else if (dataset == "BonePlug") {
                    position = Vector3f( 1.897E+0f, -5.994E-1f, -1.899E+0f) //V1 for Boneplug
                    rotation = Quaternionf( 5.867E-5,  9.998E-1,  1.919E-2,  4.404E-3)
                } else if (dataset == "Rotstrat") {
                    position = Vector3f( 2.799E+0f, -6.156E+0f, -2.641E+0f) //V1 for Rotstrat
                    rotation = Quaternionf(-3.585E-2, -9.257E-1,  3.656E-1,  9.076E-2)
                } else if (dataset == "Isotropic") {
                    position = Vector3f( 2.799E+0f, -6.156E+0f, -2.641E+0f) //V1 for Isotropic
                    rotation = Quaternionf(-3.585E-2, -9.257E-1,  3.656E-1,  9.076E-2)

//                    position = Vector3f(6.361E+0f, -6.156E+0f,  2.679E+0f) //V2
//                    rotation = Quaternionf(-2.839E-1, -5.904E-1,  2.332E-1,  7.187E-1)
                }

//                position = Vector3f( 3.183E+0f, -5.973E-1f, -1.475E+0f) //V2 for Beechnut
//                rotation = Quaternionf( 1.974E-2, -9.803E-1, -1.395E-1,  1.386E-1)
//
//                position = Vector3f( 4.458E+0f, -9.057E-1f,  4.193E+0f) //V2 for Kingsnake
//                rotation = Quaternionf( 1.238E-1, -3.649E-1,-4.902E-2,  9.215E-1)

//                position = Vector3f( 6.284E+0f, -4.932E-1f,  4.787E+0f) //V2 for Simulation
//                rotation = Quaternionf( 1.162E-1, -4.624E-1, -6.126E-2,  8.769E-1)
            }
            perspectiveCamera(50.0f, windowWidth, windowHeight)
            cam.farPlaneDistance = 20.0f
        }
        scene.addChild(cam)

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        logger.info("Exiting init function!")

        basePath = if(isCluster) {
            "/beegfs/ws/1/argupta-vdi_generation/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        thread {
            if(generateVDIs) {
                if(singleGPUBenchmarks) {
                    doBenchmarks()
                } else {
                    if(denseVDIs) {
                        manageDenseVDIs()
                    } else {
                        manageVDIGeneration()
                    }
                }

            } else {
                manageDVR()
            }
        }
    }

    fun doBenchmarks() {
        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val pivot = Box(Vector3f(20.0f))
        pivot.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
        pivot.spatial().position = Vector3f(volumeDims.x/2.0f, volumeDims.y/2.0f, volumeDims.z/2.0f)
//        parent.children.first().addChild(pivot)
        volumes[0]?.addChild(pivot)
//        parent.spatial().updateWorld(true)
        volumes[0]?.spatial()?.updateWorld(true)

        cam.target = pivot.spatial().worldPosition(Vector3f(0.0f))
        camTarget = pivot.spatial().worldPosition(Vector3f(0.0f))

        pivot.visible = false

        logger.info("Setting target to: ${pivot.spatial().worldPosition(Vector3f(0.0f))}")

        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIBufferSizes(),
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?

        var numGenerated = 0

        val subVDIColor = volumeManager.material().textures["OutputSubVDIColor"]!!
        val colorCnt = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIColor to colorCnt)

        val depthCnt = AtomicInteger(0)
        var subVDIDepth: Texture? = null

        if(separateDepth) {
            subVDIDepth = volumeManager.material().textures["OutputSubVDIDepth"]!!
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIDepth to depthCnt)
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            vdiData.metadata.projection = cam.spatial().projection
            vdiData.metadata.view = cam.spatial().getTransformation()

            numGenerated++

            if(numGenerated == 10) {
                stats.clear("Renderer.fps")
            }

            if(numGenerated > 10) {
                rotateCamera(5f)
            }

            if(numGenerated > 155) {
//            if(numGenerated > 75) {
                //Stop
                val fps = stats.get("Renderer.fps")!!
                File("${dataset}_${windowWidth}_${windowHeight}_$maxSupersegments.csv").writeText("${fps.avg()};${fps.min()};${fps.max()};${fps.stddev()};${fps.data.size}")
                renderer?.shouldClose = true
            }

//            if(numGenerated % 20 == 0) {
//
//                subVDIColorBuffer = subVDIColor.contents
//                if (subVDIDepth != null) {
//                    subVDIDepthBuffer = subVDIDepth.contents
//                }
//
//                val fileName = "${dataset}VDI${numGenerated}_ndc"
//                SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col")
//                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth")
//
//                val file = FileOutputStream(File("${dataset}vdidump$numGenerated"))
//                VDIDataIO.write(vdiData, file)
//                logger.info("written the dump")
//                file.close()
//            }
        }

    }

    fun rotateCamera(degrees: Float, pitch: Boolean = false) {
        cam.targeted = true
        val frameYaw: Float
        val framePitch: Float

        if(pitch) {
            framePitch = degrees / 180.0f * Math.PI.toFloat()
            frameYaw = 0f
        } else {
            frameYaw = degrees / 180.0f * Math.PI.toFloat()
            framePitch = 0f
        }

        // first calculate the total rotation quaternion to be applied to the camera
        val yawQ = Quaternionf().rotateXYZ(0.0f, frameYaw, 0.0f).normalize()
        val pitchQ = Quaternionf().rotateXYZ(framePitch, 0.0f, 0.0f).normalize()

        val distance = (camTarget - cam.spatial().position).length()
        cam.spatial().rotation = pitchQ.mul(cam.spatial().rotation).mul(yawQ).normalize()
        cam.spatial().position = camTarget + cam.forward * distance * (-1.0f)
    }

    private fun dvrBenchmarks() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        Thread.sleep(5000)

        setupMetadata()

        val viewpoints = listOf(0, 5, 10, 20, 30, 90, 95, 100, 110, 120)

        var vo = 0

        for(view in viewpoints) {
            rotateCamera(view.toFloat()-vo.toFloat())
            vo = view

            Thread.sleep(1000) // allow the change to take place
            stats.clear("Renderer.fps")

            Thread.sleep(2500) //collect some data
            val fps = stats.get("Renderer.fps")!!

            val global_min = reduceAcrossPEs(fps.avg().toDouble())
            if(fps.avg()<0.00001f) {
                logger.error("Error! Process: $rank has fps: ${fps.avg()} at view: $view. The max: ${fps.max()} and min: ${fps.min()}")
            }

            logger.debug("At view: $vo, rank: $rank had: ${fps.avg()}")

            if(rank == 0) {
                logger.error("At view: $vo, global min: $global_min")
            }

            r.screenshot(basePath + "${dataset}_${rank}_${view.toInt()}.png")
        }
    }

    fun manageDVR() {

        setupMetadata()

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val colorTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputRender")!!

        rotateCamera(90f)

        (renderer as VulkanRenderer).postRenderLambdas.add {
            rotateCamera(1f)
            val textureFetched = fetchTexture(colorTexture)

            if(textureFetched < 0) {
                logger.error("Error fetching DVR texture. return value: $textureFetched")
            }

            val imageBuffer = colorTexture.contents!!

            if (imageBuffer.remaining() == windowWidth * windowHeight * 4) {
                compositeImages(imageBuffer, rank, commSize, imagePointer) //this function will call a java fn that will place the image on the screen
            } else {
                logger.error("Not compositing because image size: ${imageBuffer.remaining()} expected: ${windowHeight * windowWidth * 4}")
            }

        }
    }

    @Suppress("unused")
    fun displayComposited(compositedImage: ByteBuffer) {

        val bufferLE = compositedImage.order(ByteOrder.LITTLE_ENDIAN)
        plane.material().textures["diffuse"] = Texture(Vector3i(windowWidth, windowHeight, 1), 4, contents = bufferLE, mipmap = true)
    }

    private fun saveScreenshots() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        val numScreenshots = 5

        dataset += "_${commSize}_${rank}"

        for(i in 1..numScreenshots) {
            val path = basePath + "${dataset}Screenshot$i"

            r.screenshot("$path.png")
            Thread.sleep(5000L)
        }
    }


    @Suppress("unused")
    fun stopRendering() {
        renderer?.shouldClose = true
    }

    fun fetchTexture(texture: Texture) : Int {
        val ref = VulkanTexture.getReference(texture)
        val buffer = texture.contents ?: return -1

        if(ref != null) {
            val start = System.nanoTime()
//            texture.contents = ref.copyTo(buffer, true)
            ref.copyTo(buffer, false)
            val end = System.nanoTime()
//            logger.info("The request textures of size ${texture.contents?.remaining()?.toFloat()?.div((1024f*1024f))} took: ${(end.toDouble()-start.toDouble())/1000000.0}")
        } else {
            logger.error("In fetchTexture: Texture not accessible")
        }

        return 0
    }

    private fun setupMetadata(): VDIData {
        basePath = if(isCluster) {
            "/beegfs/ws/1/argupta-vdi_generation/vdi_dumps/"
        } else {
            "/home/aryaman/TestingData/"
        }

        camTarget = when (dataset) {
            "Kingsnake" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.491E+0f)
            }
            "Beechnut" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  2.899E+0f)
            }
            "Simulation" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.800E+0f)
            }
            "Rayleigh_Taylor" -> {
                Vector3f(1.920E+0f, -1.920E+0f,  1.920E+0f)
            }
            "BonePlug" -> {
                Vector3f(1.920E+0f, -6.986E-1f,  6.855E-1f)
            }
            "Rotstrat" -> {
                Vector3f( 1.920E+0f, -1.920E+0f,  1.800E+0f)
            }
            "Isotropic" -> {
                Vector3f( 1.920E+0f, -1.920E+0f,  1.800E+0f)
            }
            else -> {
                Vector3f(0f)
            }
        }

        val model = volumes[0]?.spatial()?.world

        val vdiData = VDIData(
            VDIBufferSizes(),
            VDIMetadata(
                projection = cam.spatial().projection,
                view = cam.spatial().getTransformation(),
                volumeDimensions = volumeDims,
                model = model!!,
                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
                windowDimensions = Vector2i(cam.width, cam.height)
            )
        )

        return vdiData
    }

    private fun manageDenseVDIs() {
        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }
        val vdiData = setupMetadata()
        rotateCamera(vo)
        Thread.sleep(5000)
        vdiData.metadata.view = cam.spatial().getTransformation()

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compositor.numProcesses = commSize

        var colorTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIColor")!!
        var depthTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIDepth")!!
        val numGeneratedTexture = volumeManager.material().textures["SupersegmentsGenerated"]!!

        var compositedColor = compositor.material().textures["CompositedVDIColor"]!!
        var compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

        var prefixBuffer: ByteBuffer? = null
        var prefixIntBuff: IntBuffer? = null
        var totalSupersegmentsGenerated = 0

        var viewUsedForGeneration = cam.spatial().getTransformation()

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runThreshSearch) {
                //fetch numgenerated, calculate and upload prefixsums

                val generated = fetchTexture(numGeneratedTexture)

                if(generated < 0) {
                    logger.error("Error fetching texture. generated: $generated")
                }

                val numGeneratedBuff = numGeneratedTexture.contents
                val numGeneratedIntBuffer = numGeneratedBuff!!.asIntBuffer()

                //todo: check for errors in numGeneratedIntBuffer buffer

                val prefixTime = measureNanoTime {
                    prefixBuffer = MemoryUtil.memAlloc(windowWidth * windowHeight * 4)
                    prefixIntBuff = prefixBuffer!!.asIntBuffer()

                    prefixIntBuff!!.put(0, 0)

                    for(i in 1 until windowWidth * windowHeight) {
                        prefixIntBuff!!.put(i, prefixIntBuff!!.get(i-1) + numGeneratedIntBuffer.get(i-1))
                    }

                    totalSupersegmentsGenerated = prefixIntBuff!!.get(windowWidth*windowHeight-1) + numGeneratedIntBuffer.get(windowWidth*windowHeight-1)
                }
                logger.debug("Prefix sum took ${prefixTime/1e9} to compute")


                volumes[0]!!.volumeManager.material().textures["PrefixSums"] = Texture(
                    Vector3i(windowHeight, windowWidth, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                    , type = IntType(),
                    mipmap = false,
                    minFilter = Texture.FilteringMode.NearestNeighbour,
                    maxFilter = Texture.FilteringMode.NearestNeighbour
                )

                runThreshSearch = false
                if(cam.spatial().getTransformation() != viewUsedForGeneration) {
                    logger.error("This is an error!! A different view has been used!!")
                }
                runGeneration = true
            } else if(runGeneration) {

                colorTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIColor")!!
                depthTexture = volumes[0]?.volumeManager?.material()?.textures?.get("OutputSubVDIDepth")!!

                val col = fetchTexture(colorTexture)
                val depth = fetchTexture(depthTexture)

                if(col < 0) {
                    logger.error("Error fetching the color subVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth subVDI!!")
                }

                vdisGenerated.incrementAndGet()
                runGeneration = false
            }

            if(runCompositing) {
                compositedColor = compositor.material().textures["CompositedVDIColor"]!!
                compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

                val col = fetchTexture(compositedColor)
                val depth = fetchTexture(compositedDepth)

                if(col < 0) {
                    logger.error("Error fetching the color compositedVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth compositedVDI!!")
                }

                vdisComposited.incrementAndGet()
                runCompositing = false

//                if(vdisGathered % 5 == 0) {
//                    rotateCamera(10f)
//                    vdiData.metadata.view = cam.spatial().getTransformation()
//                }

                runThreshSearch = true
                viewUsedForGeneration = cam.spatial().getTransformation()
            }

//            logger.info("distributed: ${vdisDistributed.get()} and composited: ${vdisComposited.get()}")
            if(vdisDistributed.get() > vdisComposited.get()) {
                runCompositing = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            compositor.doComposite = runCompositing

            if(runCompositing) {
                logger.debug("doComposite has been set to true!")
            }

            volumes[0]?.volumeManager?.shaderProperties?.set("doGeneration", runGeneration)
            volumes[0]?.volumeManager?.shaderProperties?.set("doThreshSearch", runThreshSearch)
        }

        var generatedSoFar = 0
        var compositedSoFar = 0

        dataset += "_${commSize}_${rank}"

        var start = 0L
        var end = 0L

        var start_complete = 0L
        var end_complete = 0L

        while(true) {

            start_complete = System.nanoTime()

            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var bufferToSend: ByteBuffer? = null

            var compositedVDIDepthBuffer: ByteBuffer?
            var compositedVDIColorBuffer: ByteBuffer?

            start = System.nanoTime()
            while((vdisGenerated.get() <= generatedSoFar)) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

            logger.debug("Waiting for VDI generation took: ${end/1e9}")

            generatedSoFar = vdisGenerated.get()

            subVDIColorBuffer = colorTexture.contents
            subVDIDepthBuffer = depthTexture.contents

            subVDIColorBuffer!!.limit(totalSupersegmentsGenerated * 4 * 4)
            subVDIDepthBuffer!!.limit(2 * totalSupersegmentsGenerated * 4)

            compositedVDIColorBuffer = compositedColor.contents
            compositedVDIDepthBuffer = compositedDepth.contents


            if(!benchmarking) {
                logger.info("Dumping sub VDI files")
                SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_col_rle")
                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_depth_rle")
                SystemHelpers.dumpToFile(prefixBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_prefix")
                logger.info("File dumped")
                cnt_sub++
            }
            val supersegmentCounts = IntArray(commSize)

            val totalLists = windowHeight * windowWidth

            var supersegmentsSoFar = 0

            for(i in 0 until (commSize-1)) {
                supersegmentCounts[i] = prefixIntBuff!!.get((totalLists / commSize) * (i + 1)) - supersegmentsSoFar
                logger.debug("Rank: $rank will send ${supersegmentCounts[i]} supersegments to process $i")
                supersegmentsSoFar += supersegmentCounts[i];
            }

            supersegmentCounts[commSize-1] = totalSupersegmentsGenerated - supersegmentsSoFar
            logger.debug("Rank: $rank will send ${supersegmentCounts[commSize-1]} supersegments to process ${commSize-1}")

            logger.debug("Total supersegments generated by rank $rank: $totalSupersegmentsGenerated")

            start = System.nanoTime()
            distributeDenseVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, prefixBuffer!!, supersegmentCounts, commSize, allToAllColorPointer,
                allToAllDepthPointer, allToAllPrefixPointer, mpiPointer)
            end = System.nanoTime() - start

            logger.info("Distributing VDIs took: ${end/1e9}")
            logger.debug("Back in the management function")

            start = System.nanoTime()
            while(vdisComposited.get() <= compositedSoFar) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

            compositedSoFar = vdisComposited.get()

            //fetch the composited VDI

            if(!benchmarking) {
                logger.info("Dumping composited VDI files")
                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, basePath + "${dataset}CompositedVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_col")
                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, basePath + "${dataset}CompositedVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_depth")
                logger.info("File dumped")

                logger.debug("Cam position: ${cam.spatial().position}")
                logger.debug("Cam rotation: ${cam.spatial().rotation}")
            }

            start = System.nanoTime()
            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxOutputSupersegments * 4 / commSize, 0,
                rank, commSize, gatherColorPointer, gatherDepthPointer, vo.toInt(), mpiPointer) //3 * commSize because the supersegments here contain only 1 element
            end = System.nanoTime() - start

            logger.debug("Gather took: ${end/1e9}")


            vdiData.metadata.view = viewUsedForGeneration
            if(saveFinal && (rank == 0)) {
                val file = FileOutputStream(File(basePath + "${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_${vo.toInt()}_dump$vdisGathered"))
                VDIDataIO.write(vdiData, file)
                logger.debug("written the dump $vdisGathered")
                file.close()
            }

            vdisGathered++
            end_complete = System.nanoTime() - start_complete
            logger.debug("Whole iteration took: ${end_complete/1e9}")
        }

    }

    private fun manageVDIGeneration() {

        while(renderer?.firstImageReady == false) {
            Thread.sleep(50)
        }

        while(!rendererConfigured) {
            Thread.sleep(50)
        }

        val vdiData = setupMetadata()

        compositor.nw = vdiData.metadata.nw
        compositor.ViewOriginal = vdiData.metadata.view
        compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
        compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
        compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()
        compositor.numProcesses = commSize

        var colorTexture = volumeManager.material().textures["OutputSubVDIColor"]!!
        var depthTexture = volumeManager.material().textures["OutputSubVDIDepth"]!!

        var compositedColor =
            if(generateVDIs) {
                compositor.material().textures["CompositedVDIColor"]!!
            } else {
                compositor.material().textures["AlphaComposited"]!!
            }
        var compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runGeneration) {

                colorTexture = volumeManager.material().textures["OutputSubVDIColor"]!!
                depthTexture = volumeManager.material().textures["OutputSubVDIDepth"]!!

                val col = fetchTexture(colorTexture)
                val depth = if(separateDepth) {
                    fetchTexture(depthTexture)
                } else {
                    0
                }

                if(col < 0) {
                    logger.error("Error fetching the color subVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth subVDI!!")
                }

                vdisGenerated.incrementAndGet()
                runGeneration = false
            }
            if(runCompositing) {
                compositedColor = compositor.material().textures["CompositedVDIColor"]!!
                compositedDepth = compositor.material().textures["CompositedVDIDepth"]!!

                val col = fetchTexture(compositedColor)
                val depth = if(separateDepth) {
                    fetchTexture(compositedDepth)
                } else {
                    0
                }

                if(col < 0) {
                    logger.error("Error fetching the color compositedVDI!!")
                }
                if(depth < 0) {
                    logger.error("Error fetching the depth compositedVDI!!")
                }

                vdisComposited.incrementAndGet()
                runCompositing = false

                logger.info("The camera is not rotating!")
//                rotateCamera(10f)
//                vdiData.metadata.projection = cam.spatial().projection
//                vdiData.metadata.view = cam.spatial().getTransformation()
                runGeneration = true
            }

            if(vdisDistributed.get() > vdisComposited.get()) {
                runCompositing = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runCompositing) {
//                logger.info("SETTING DO_COMPOSITE TO TRUE!")
            }
            compositor.doComposite = runCompositing
            volumes[0]?.volumeManager?.shaderProperties?.set("doGeneration", runGeneration)
        }

        var generatedSoFar = 0
        var compositedSoFar = 0

        dataset += "_${commSize}_${rank}"

        var start = 0L
        var end = 0L

        var start_complete = 0L
        var end_complete = 0L

        while(true) {

            start_complete = System.nanoTime()

            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var bufferToSend: ByteBuffer? = null

            var compositedVDIDepthBuffer: ByteBuffer?
            var compositedVDIColorBuffer: ByteBuffer?

            start = System.nanoTime()
            while((vdisGenerated.get() <= generatedSoFar)) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

            logger.debug("Waiting for VDI generation took: ${end/1e9}")


//            logger.warn("C1: vdis generated so far: $generatedSoFar and the new value of vdisgenerated: ${vdisGenerated.get()}")

            generatedSoFar = vdisGenerated.get()

            subVDIColorBuffer = colorTexture.contents
            if(separateDepth) {
                subVDIDepthBuffer = depthTexture!!.contents
            }
            compositedVDIColorBuffer = compositedColor.contents
            compositedVDIDepthBuffer = compositedDepth.contents

//
//            compositor.ViewOriginal = vdiData.metadata.view
//            compositor.invViewOriginal = Matrix4f(vdiData.metadata.view).invert()
//            compositor.ProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem()
//            compositor.invProjectionOriginal = Matrix4f(vdiData.metadata.projection).applyVulkanCoordinateSystem().invert()

            if(!benchmarking) {
                logger.info("Dumping sub VDI files")
                SystemHelpers.dumpToFile(subVDIColorBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_col")
                SystemHelpers.dumpToFile(subVDIDepthBuffer!!, basePath + "${dataset}SubVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_sub}_ndc_depth")
                logger.info("File dumped")
            }

            if(subVDIColorBuffer == null || subVDIDepthBuffer == null) {
                logger.info("CALLING DISTRIBUTE EVEN THOUGH THE BUFFERS ARE NULL!!")
            }

//            logger.info("For distributed the color buffer isdirect: ${subVDIColorBuffer!!.isDirect()} and depthbuffer: ${subVDIDepthBuffer!!.isDirect()}")

            start = System.nanoTime()
            distributeVDIs(subVDIColorBuffer!!, subVDIDepthBuffer!!, windowHeight * windowWidth * maxSupersegments * 4 / commSize, commSize, allToAllColorPointer,
            allToAllDepthPointer, mpiPointer)
            end = System.nanoTime() - start

            logger.info("Distributing VDIs took: ${end/1e9}")


//            logger.info("Back in the management function")

            if(!benchmarking) {
//                Thread.sleep(1000)
//
//                subVDIColorBuffer.clear()
//                subVDIDepthBuffer.clear()
            }

            start = System.nanoTime()
            while(vdisComposited.get() <= compositedSoFar) {
                Thread.sleep(5)
            }
            end = System.nanoTime() - start

//            logger.info("Waiting for composited generation took: ${end/1e9}")

            compositedSoFar = vdisComposited.get()

            //fetch the composited VDI

            if(compositedVDIColorBuffer == null || compositedVDIDepthBuffer == null) {
                logger.info("CALLING GATHER EVEN THOUGH THE BUFFER(S) ARE NULL!!")
            }

            if(!benchmarking) {
                logger.info("Dumping sub VDI files")
                SystemHelpers.dumpToFile(compositedVDIColorBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_col")
                SystemHelpers.dumpToFile(compositedVDIDepthBuffer!!, basePath + "${dataset}CompositedVDI${cnt_sub}_ndc_depth")
                logger.info("File dumped")

                logger.info("Cam position: ${cam.spatial().position}")
                logger.info("Cam rotation: ${cam.spatial().rotation}")

                cnt_sub++
            }

//            logger.info("For gather the color buffer isdirect: ${compositedVDIColorBuffer!!.isDirect()} and depthbuffer: ${compositedVDIDepthBuffer!!.isDirect()}")

            start = System.nanoTime()
            gatherCompositedVDIs(compositedVDIColorBuffer!!, compositedVDIDepthBuffer!!, windowHeight * windowWidth * maxOutputSupersegments * 4 / commSize, 0,
                rank, commSize, gatherColorPointer, gatherDepthPointer, vo.toInt(), mpiPointer) //3 * commSize because the supersegments here contain only 1 element
            end = System.nanoTime() - start

            logger.debug("Gather took: ${end/1e9}")


            if(saveFinal && (rank == 0)) {
                val file = FileOutputStream(File(basePath + "${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_0_dump$vdisGathered"))
                VDIDataIO.write(vdiData, file)
                logger.info("written the dump $vdisGathered")
                file.close()
            }

            vdisGathered++

            if(!benchmarking) {
//                Thread.sleep(1000)
//
//                compositedVDIColorBuffer.clear()
//                compositedVDIDepthBuffer.clear()
//
                logger.info("Back in the management function after gathering and streaming")
//                Thread.sleep(1000)
            }

            end_complete = System.nanoTime() - start_complete

            logger.debug("Whole iteration took: ${end_complete/1e9}")
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

//        inputHandler?.addBehaviour("rotate_camera", ClickBehaviour { _, _ ->
//            addVolume(0, intArrayOf(200, 200, 200), floatArrayOf(0f, 0f, -3.5f))
//        })
//        inputHandler?.addKeyBinding("rotate_camera", "R")
    }

    @Suppress("unused")
    fun uploadForCompositingDense(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer, prefixSet: ByteBuffer, colorCounts: IntArray, depthCounts: IntArray) {

//        val prefixIntBuff = prefixSet.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
//
//        var error = false
//
//        for(i in 0 until windowWidth * windowHeight) {
//            if(prefixIntBuff.get(i) < 0) {
//                logger.info("This is an error after distribute! value is ${prefixIntBuff.get(i)} at loc: $i")
//                error = true
//            }
//        }
//
//        if(error) {
//            logger.info("encountered error in prefix sum at iteration: $cnt_distr")
//        }
//
//        var prefixAdded = 0
//        for(i in 1 until commSize) {
//            val numSupsegs = colorCounts[i-1] / (4*4)
//            prefixAdded += numSupsegs
//            for (j in (i * ((windowWidth * windowHeight)/commSize)) until ((i+1) * (windowWidth*windowHeight)/commSize)) {
////                if(j % 100 == 0) {
////                    logger.info("prefix was: ${prefixIntBuffer.get(j)}")
////                }
//                prefixIntBuff.put(j, prefixIntBuff.get(j) + prefixAdded)
////                if(j % 100 == 0) {
////                    logger.info("updated to: ${prefixIntBuffer.get(j)}")
////                }
//            }
//        }
        val supersegmentsRecvd = (vdiSetColour.remaining() / (4*4)).toFloat() //including potential 0 supersegments that were padded

        logger.debug("Rank: $rank: total supsegs recvd (including 0s): $supersegmentsRecvd")

        for (i in 0 until commSize) {
            compositor.totalSupersegmentsFrom[i] = colorCounts[i] / (4 * 4)
            logger.debug("Rank $rank: totalSupersegmentsFrom $i: ${colorCounts[i] / (4 * 4)}")
        }

        if(!benchmarking) {
            logger.info("Dumping to file in the composite function")
            SystemHelpers.dumpToFile(vdiSetColour, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_col_rle")
            SystemHelpers.dumpToFile(vdiSetDepth, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_depth_rle")
            SystemHelpers.dumpToFile(prefixSet, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_prefix")
            logger.info("File dumped")
            cnt_distr++
        }

        compositor.material().textures["VDIsColor"] = Texture(Vector3i(512, 512, ceil((supersegmentsRecvd / (512*512)).toDouble()).toInt()), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        compositor.material().textures["VDIsDepth"] = Texture(Vector3i(2 * 512, 512, ceil((supersegmentsRecvd / (512*512)).toDouble()).toInt()), 1, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        compositor.material().textures["VDIsPrefix"] = Texture(Vector3i(windowHeight, windowWidth, 1), 1, contents = prefixSet, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
            type = IntType(), mipmap = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour
        )

        val view = cam.spatial().getTransformation()
        compositor.ViewOriginal = view
        compositor.invViewOriginal = Matrix4f(view).invert()

        vdisDistributed.incrementAndGet()

    }


    @Suppress("unused")
    fun uploadForCompositing(vdiSetColour: ByteBuffer, vdiSetDepth: ByteBuffer) {
        //Receive the VDIs and composite them

//        logger.info("In the composite function")

//        if(saveFiles) {
        val model = volumes[0]?.spatial()?.world

//        val vdiData = VDIData(
//            VDIMetadata(
//                projection = cam.spatial().projection,
//                view = cam.spatial().getTransformation(),
//                volumeDimensions = volumeDims,
//                model = model!!,
//                nw = volumes[0]?.volumeManager?.shaderProperties?.get("nw") as Float,
//                windowDimensions = Vector2i(cam.width, cam.height)
//            )
//        )
//
//        val duration = measureNanoTime {
//            val file = FileOutputStream(File(basePath + "${dataset}vdidump$cnt_distr"))
////                    val comp = GZIPOutputStream(file, 65536)
//            VDIDataIO.write(vdiData, file)
//            logger.info("written the dump")
//            file.close()
//        }

        if(!benchmarking) {
            logger.info("Dumping to file in the composite function")
            SystemHelpers.dumpToFile(vdiSetColour, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_col")
            SystemHelpers.dumpToFile(vdiSetDepth, basePath + "${dataset}SetOfVDI_${windowWidth}_${windowHeight}_${maxSupersegments}_0_${cnt_distr}_ndc_depth")
            logger.info("File dumped")
            cnt_distr++
        }
//        }

        if(generateVDIs) {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(maxSupersegments, windowHeight, windowWidth), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

            if(separateDepth) {
                compositor.material().textures["VDIsDepth"] = Texture(Vector3i(2 * maxSupersegments, windowHeight, windowWidth), 1, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture),
                    type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            }
        } else {
            compositor.material().textures["VDIsColor"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetColour, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            compositor.material().textures["VDIsDepth"] = Texture(Vector3i(windowHeight, windowWidth, 1), 4, contents = vdiSetDepth, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        }
//        logger.info("Updated the textures to be composited")

        val view = cam.spatial().getTransformation()
        compositor.ViewOriginal = view
        compositor.invViewOriginal = Matrix4f(view).invert()

        vdisDistributed.incrementAndGet()

//        compute.visible = true
    }


    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DistributedVolumes().main()
        }
    }
}