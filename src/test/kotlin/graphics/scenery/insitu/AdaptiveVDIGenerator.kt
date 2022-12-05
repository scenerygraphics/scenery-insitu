package graphics.scenery.insitu

import bdv.tools.transformation.TransformedSource
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.controls.TrackedStereoGlasses
import graphics.scenery.attribute.material.Material
import graphics.scenery.backends.Shaders
import graphics.scenery.backends.vulkan.VulkanRenderer
import graphics.scenery.backends.vulkan.VulkanTexture
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.utils.SystemHelpers
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.volumes.*
import graphics.scenery.volumes.vdi.*
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import org.msgpack.jackson.dataformat.MessagePackFactory
import org.scijava.ui.behaviour.ClickBehaviour
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.io.*
import java.lang.Math
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.streams.toList
import kotlin.system.measureNanoTime

/**
 * Class to generate content adaptive VDIs
 *
 * @author Aryaman Gupta <argupta@mpi-cbg.de>
 */

class AdaptiveVDIGenerator: SceneryBase("Volume Rendering", System.getProperty("VolumeBenchmark.WindowWidth")?.toInt()?: 1280, System.getProperty("VolumeBenchmark.WindowHeight")?.toInt() ?: 720, wantREPL = false) {
    var hmd: TrackedStereoGlasses? = null

    val context: ZContext = ZContext(4)

    lateinit var volumeManager: VolumeManager
    val computeSupsegNums = RichNode()
    val computePrefix = RichNode()

    val generateVDIs = System.getProperty("VolumeBenchmark.GenerateVDI")?.toBoolean() ?: true
    val storeVDIs = System.getProperty("VolumeBenchmark.StoreVDIs")?.toBoolean()?: true
    val transmitVDIs = System.getProperty("VolumeBenchmark.TransmitVDIs")?.toBoolean()?: false
    val vo = System.getProperty("VolumeBenchmark.Vo")?.toFloat()?.toInt() ?: 0
    val separateDepth = true
    val colors32bit = true
    val world_abs = false
    val dataset = System.getProperty("VolumeBenchmark.Dataset")?.toString()?: "Kingsnake"

    val VDIsGenerated = AtomicInteger(0)

    val num_parts = when (dataset) {
        "Kingsnake" -> {
            1
        }
        "Rayleigh_Taylor" -> {
            2
        }
        "Beechnut" -> {
            3
        }
        "Simulation" -> {
            5
        }
        else -> {
            1
        }
    }

    val volumeDims = when (dataset) {
        "Kingsnake" -> {
            Vector3f(1024f, 1024f, 795f)
        }
        "Rayleigh_Taylor" -> {
            Vector3f(1024f, 1024f, 1024f)
        }
        "Beechnut" -> {
            Vector3f(1024f, 1024f, 1546f)
        }
        "Simulation" -> {
            Vector3f(2048f, 2048f, 1920f)
        }
        else -> {
            Vector3f(256f, 256f, 109f)
        }
    }

    val is16bit = if(dataset == "Kingsnake" || dataset == "Simulation") {
        false
    } else if (dataset == "Beechnut" || dataset == "Rayleigh_Taylor") {
        true
    } else {
        true
    }

    val volumeList = ArrayList<BufferedVolume>()
    val cam: Camera = DetachedHeadCamera(hmd)
    var camTarget = Vector3f(0f)
    val numOctreeLayers = 8
    val maxSupersegments = System.getProperty("VolumeBenchmark.NumSupersegments")?.toInt()?: 20
    var benchmarking = false
    val viewNumber = 1
    var ambientOcclusion = true

    val dynamicSubsampling = true

    val storeCamera = false
    val storeFrameTime = true
    val subsampleRay = true

    var subsamplingFactorImage = 1.0f

    var cameraMoving = false
    var cameraStopped = false

    val closeAfter = 25000000L

    /**
     * Reads raw volumetric data from a [file].
     *
     * Returns the new volume.
     *
     * Based on Volume.fromPathRaw
     */
    fun fromPathRaw(file: Path, is16bit: Boolean = true): BufferedVolume {

        val infoFile: Path
        val volumeFiles: List<Path>

        if(Files.isDirectory(file)) {
            volumeFiles = Files.list(file).filter { it.toString().endsWith(".raw") && Files.isRegularFile(it) && Files.isReadable(it) }.toList()
            infoFile = file.resolve("stacks.info")
        } else {
            volumeFiles = listOf(file)
            infoFile = file.resolveSibling("stacks.info")
        }

        val lines = Files.lines(infoFile).toList()

        logger.debug("reading stacks.info (${lines.joinToString()}) (${lines.size} lines)")
        val dimensions = Vector3i(lines.get(0).split(",").map { it.toInt() }.toIntArray())
        logger.debug("setting dim to ${dimensions.x}/${dimensions.y}/${dimensions.z}")
        logger.debug("Got ${volumeFiles.size} volumes")

        val volumes = CopyOnWriteArrayList<BufferedVolume.Timepoint>()
        volumeFiles.forEach { v ->
            val id = v.fileName.toString()
            val buffer: ByteBuffer by lazy {

                logger.debug("Loading $id from disk")
                val buffer = ByteArray(1024 * 1024)
                val stream = FileInputStream(v.toFile())
                val numBytes = if(is16bit) {
                    2
                } else {
                    1
                }
                val imageData: ByteBuffer = MemoryUtil.memAlloc((numBytes * dimensions.x * dimensions.y * dimensions.z))

                logger.debug("${v.fileName}: Allocated ${imageData.capacity()} bytes for image of $dimensions containing $numBytes per voxel")

                val start = System.nanoTime()
                var bytesRead = stream.read(buffer, 0, buffer.size)
                while (bytesRead > -1) {
                    imageData.put(buffer, 0, bytesRead)
                    bytesRead = stream.read(buffer, 0, buffer.size)
                }
                val duration = (System.nanoTime() - start) / 10e5
                logger.debug("Reading took $duration ms")

                imageData.flip()
                imageData
            }

            volumes.add(BufferedVolume.Timepoint(id, buffer))
        }

        return if(is16bit) {
            Volume.fromBuffer(volumes, dimensions.x, dimensions.y, dimensions.z, UnsignedShortType(), hub)
        } else {
            Volume.fromBuffer(volumes, dimensions.x, dimensions.y, dimensions.z, UnsignedByteType(), hub)
        }
    }

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val raycastShader: String
        val accumulateShader: String
        val compositeShader: String

        if(generateVDIs) {
            benchmarking = false
            //set up volume manager


            raycastShader = "AdaptiveVDIGenerator.comp"
//            raycastShader = "LochmannGenerator.comp"
            accumulateShader = "AccumulateVDI.comp"
//            accumulateShader = "AccumulateLochmann.comp"
            compositeShader = "VDICompositor.comp"
            val maxOutputSupersegments = 40
            val numLayers = if(separateDepth) {
                1
            } else {
                3         // VDI supersegments require both front and back depth values, along with color
            }

            volumeManager = VolumeManager(
                hub, useCompute = true, customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this.javaClass,
                        raycastShader,
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate",
                    ),
                    SegmentType.Accumulator to SegmentTemplate(
//                                this.javaClass,
                        accumulateShader,
                        "vis", "localNear", "localFar", "sampleVolume", "convert",
                    ),
                ),
            )

            val outputSubColorBuffer = if(colors32bit) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers * 4)
            } else {
                MemoryUtil.memCalloc(windowHeight*windowWidth*4*maxSupersegments*numLayers)
            }
            val outputSubDepthBuffer = if(separateDepth) {
                MemoryUtil.memCalloc(windowHeight*windowWidth*2*maxSupersegments*2 * 2)
//                MemoryUtil.memCalloc(windowHeight*windowWidth*2*maxSupersegments*2)
            } else {
                MemoryUtil.memCalloc(0)
            }

//            val numGridCells = 2.0.pow(numOctreeLayers)
            val numGridCells = Vector3f(windowWidth.toFloat() / 8f, windowHeight.toFloat() / 8f, maxSupersegments.toFloat())
//            val numGridCells = Vector3f(256f, 256f, 256f)
            val lowestLevel = MemoryUtil.memCalloc(numGridCells.x.toInt() * numGridCells.y.toInt() * numGridCells.z.toInt() * 4)

            val basePath = "/home/aryaman/Repositories/scenery-insitu/"

            val prefixSums = File(basePath + "prefix").readBytes()

            val prefixBuffer: ByteBuffer

            prefixBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

            prefixBuffer.put(prefixSums).flip()

            val outputSubVDIColor: Texture
            val outputSubVDIDepth: Texture
            val gridCells: Texture

            val totalMaxSupersegments = maxSupersegments * windowWidth * windowHeight

            outputSubVDIColor = if(colors32bit) {
                Texture.fromImage(
                    Image(outputSubColorBuffer, numLayers * 512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
            } else {
                Texture.fromImage(
                    Image(outputSubColorBuffer, numLayers * 512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()), usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            }

            volumeManager.customTextures.add("OutputSubVDIColor")
            volumeManager.material().textures["OutputSubVDIColor"] = outputSubVDIColor

            if(separateDepth) {
                outputSubVDIDepth = Texture.fromImage(
                    Image(outputSubDepthBuffer, 2 * 512, 512, ceil((totalMaxSupersegments / (512*512)).toDouble()).toInt()),  usage = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
//                    Image(outputSubDepthBuffer, 2*maxSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
//                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)
                volumeManager.customTextures.add("OutputSubVDIDepth")
                volumeManager.material().textures["OutputSubVDIDepth"] = outputSubVDIDepth
            }

            gridCells = Texture.fromImage(Image(lowestLevel, numGridCells.x.toInt(), numGridCells.y.toInt(), numGridCells.z.toInt()), channels = 1, type = UnsignedIntType(),
                usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.customTextures.add("OctreeCells")
            volumeManager.material().textures["OctreeCells"] = gridCells

            volumeManager.customTextures.add("PrefixSums")
            volumeManager.material().textures["PrefixSums"] = Texture(
                Vector3i(windowWidth, windowHeight, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                , type = IntType(),
                mipmap = false,
                minFilter = Texture.FilteringMode.NearestNeighbour,
                maxFilter = Texture.FilteringMode.NearestNeighbour
            )


            volumeManager.customUniforms.add("doGeneration")
            volumeManager.shaderProperties["doGeneration"] = true

            volumeManager.customUniforms.add("windowWidth")
            volumeManager.shaderProperties["windowWidth"] = windowWidth

            volumeManager.customUniforms.add("windowHeight")
            volumeManager.shaderProperties["windowHeight"] = windowHeight

            volumeManager.customUniforms.add("maxSupersegments")
            volumeManager.shaderProperties["maxSupersegments"] = maxSupersegments

            hub.add(volumeManager)

            val compute = RichNode()
            compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("GridCellsToZero.comp"), this@AdaptiveVDIGenerator::class.java)))

            compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(numGridCells.x.toInt(), numGridCells.y.toInt(), 1),
                invocationType = InvocationType.Permanent
            )

            compute.material().textures["GridCells"] = gridCells

            scene.addChild(compute)

        } else {
            volumeManager = VolumeManager(hub,
                useCompute = true,
                customSegments = hashMapOf(
                    SegmentType.FragmentShader to SegmentTemplate(
                        this.javaClass,
                        "ComputeRaycast.comp",
                        "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate"),
                ))
            volumeManager.customTextures.add("OutputRender")

            val outputBuffer = MemoryUtil.memCalloc(windowWidth*windowHeight*4)
            val outputTexture = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            volumeManager.material().textures["OutputRender"] = outputTexture

            volumeManager.customUniforms.add("ambientOcclusion")
            volumeManager.shaderProperties["ambientOcclusion"] = false

            hub.add(volumeManager)

            val plane = FullscreenObject()
            scene.addChild(plane)
            plane.material().textures["diffuse"] = volumeManager.material().textures["OutputRender"]!!
        }


        with(cam) {
            spatial {
                if(dataset == "Kingsnake") {
                    position = Vector3f( 4.622E+0f, -9.060E-1f, -1.047E+0f) //V1 for kingsnake
                    rotation = Quaternionf( 5.288E-2, -9.096E-1, -1.222E-1,  3.936E-1)
                } else if (dataset == "Beechnut") {
                    position = Vector3f(-2.607E+0f, -5.973E-1f,  2.415E+0f) // V1 for Beechnut
                    rotation = Quaternionf(-9.418E-2, -7.363E-1, -1.048E-1, -6.618E-1)
                } else if (dataset == "Simulation") {
                    position = Vector3f(2.041E-1f, -5.253E+0f, -1.321E+0f) //V1 for Simulation
                    rotation = Quaternionf(9.134E-2, -9.009E-1,  3.558E-1, -2.313E-1)
                } else if (dataset == "Rayleigh_Taylor") {
                    position = Vector3f( -2.300E+0f, -6.402E+0f,  1.100E+0f) //V1 for Rayleigh_Taylor
                    rotation = Quaternionf(2.495E-1, -7.098E-1,  3.027E-1, -5.851E-1)
                } else if (dataset == "BonePlug") {
                    position = Vector3f( 1.897E+0f, -5.994E-1f, -1.899E+0f) //V1 for Boneplug
                    rotation = Quaternionf( 5.867E-5,  9.998E-1,  1.919E-2,  4.404E-3)
                } else if (dataset == "Rotstrat") {
                    position = Vector3f(4.908E+0f, -4.931E-1f, -2.563E+0f) //V1 for Simulation
                    rotation = Quaternionf( 3.887E-2, -9.470E-1, -1.255E-1,  2.931E-1)
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

            scene.addChild(this)
        }

        val shell = Box(Vector3f(10.0f, 10.0f, 10.0f), insideNormals = true)
        shell.material {
            cullingMode = Material.CullingMode.None
            diffuse = Vector3f(0.2f, 0.2f, 0.2f)
            specular = Vector3f(0.0f)
            ambient = Vector3f(0.0f)
        }
        scene.addChild(shell)
        shell.visible = false

        val datasetPath = Paths.get("/home/aryaman/Datasets/Volume/${dataset}")
//        val datasetPath = Paths.get("/scratch/ws/1/argupta-distributed_vdis/Datasets/${dataset}")

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
                TransferFunction.ramp(0.0025f, 0.005f, 0.7f)
            } else {
                logger.info("Using a standard transfer function")
                addControlPoint(0.0f, 0.0f)
                addControlPoint(1.0f, 1.0f)

            }
        }

        val pixelToWorld = (0.0075f * 512f) / volumeDims.x

        val parent = RichNode()

        var prev_slices = 0f
        var current_slices = 0f

        var prevIndex = 0f

        for(i in 1..num_parts) {
            val volume = fromPathRaw(Paths.get("$datasetPath/Part$i"), is16bit)
            volume.name = "volume"
            volume.colormap = Colormap.get("hot")
            if(dataset == "Rotstrat") {
                volume.colormap = Colormap.get("jet")
                volume.converterSetups[0].setDisplayRange(25000.0, 50000.0)
            } else if(dataset == "Beechnut") {
                volume.converterSetups[0].setDisplayRange(0.0, 33465.0)
            } else if(dataset == "Simulation") {
                volume.colormap = Colormap.get("rb")
                volume.converterSetups[0].setDisplayRange(50.0, 205.0)
            } else if(dataset == "Rayleigh_Taylor") {
                volume.colormap = Colormap.get("rbdarker")
            }

            volume.origin = Origin.FrontBottomLeft
            val source = (volume.ds.sources[0].spimSource as TransformedSource).wrappedSource as? BufferSource<*>
            //volume.converterSetups.first().setDisplayRange(10.0, 220.0)
            current_slices = source!!.depth.toFloat()
            logger.info("current slices: $current_slices")
            volume.pixelToWorldRatio = pixelToWorld
            volume.transferFunction = tf

//            if(i == 2){
//                val tfUI = TransferFunctionUI(650, 500, volume)
//            }

//            volume.spatial().position = Vector3f(2.0f, 6.0f, 4.0f - ((i - 1) * ((volumeDims.z / num_parts) * pixelToWorld)))
//            if(i > 1) {
//            val temp = Vector3f(volumeList.lastOrNull()?.spatial()?.position?: Vector3f(0f)) - Vector3f(0f, 0f, (prev_slices/2f + current_slices/2f) * pixelToWorld)
            val temp = Vector3f(0f, 0f, 1.0f * (prevIndex) * pixelToWorld)
            volume.spatial().position = temp
//            if(num_parts > 1) {
//                volume.spatial().scale = Vector3f(1.0f, 1.0f, 2.0f)
//            }
//            }
            prevIndex += current_slices
            logger.info("volume slice $i position set to $temp")
            prev_slices = current_slices
//            volume.spatial().updateWorld(true)
            parent.addChild(volume)
//            println("Volume model matrix is: ${Matrix4f(volume.spatial().model).invert()}")
            volumeList.add(volume)
        }

//        parent.spatial().position = Vector3f(2.0f, 6.0f, 4.0f) - Vector3f(0.0f, 0.0f, volumeList.map { it.spatial().position.z }.sum()/2.0f)
        parent.spatial().position = Vector3f(0f)
//        cam.spatial().position = Vector3f(0f)

        scene.addChild(parent)

        val middle_index = if(num_parts % 2 == 0) {
            (num_parts / 2) - 1
        } else {
            (num_parts + 1) / 2 - 1
        }

        val pivot = Box(Vector3f(20.0f))
        pivot.material().diffuse = Vector3f(0.0f, 1.0f, 0.0f)
        pivot.spatial().position = Vector3f(volumeDims.x/2.0f, volumeDims.y/2.0f, volumeDims.z/2.0f)
        parent.children.first().addChild(pivot)
        parent.spatial().updateWorld(true)
        cam.target = pivot.spatial().worldPosition(Vector3f(0.0f))
        camTarget = pivot.spatial().worldPosition(Vector3f(0.0f))

        pivot.visible = false

        logger.info("Setting target to: ${pivot.spatial().worldPosition(Vector3f(0.0f))}")

        val lights = (0 until 3).map {
            PointLight(radius = 15.0f)
        }

        lights.mapIndexed { i, light ->
            light.spatial().position = Vector3f(2.0f * i - 4.0f,  i - 1.0f, 0.0f)
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 0.5f
            scene.addChild(light)
        }

//        thread {
//            while (!sceneInitialized()) {
//                Thread.sleep(200)
//            }
//            while (true) {
//                val dummyVolume = scene.find("DummyVolume") as? DummyVolume
//                val clientCam = scene.find("ClientCamera") as? DetachedHeadCamera
//                if (dummyVolume != null && clientCam != null) {
//                    volumeList.first().networkCallback += {  //TODO: needs to be repeated for all volume slices
//                        if (volumeList.first().transferFunction != dummyVolume.transferFunction) {
//                            volumeList.first().transferFunction = dummyVolume.transferFunction
//                        }
//                        /*
//                    if(volume.colormap != dummyVolume.colormap) {
//                        volume.colormap = dummyVolume.colormap
//                    }
//                    if(volume.slicingMode != dummyVolume.slicingMode) {
//                        volume.slicingMode = dummyVolume.slicingMode
//                    }*/
//                    }
//                    cam.update += {
//                        cam.spatial().position = clientCam.spatial().position
//                        cam.spatial().rotation = clientCam.spatial().rotation
//                    }
//                    break;
//                }
//            }
//
//            settings.set("VideoEncoder.StreamVideo", true)
//            settings.set("VideoEncoder.StreamingAddress", "udp://${InetAddress.getLocalHost().hostAddress}:3337")
////            settings.set("VideoEncoder.StreamingAddress", "udp://10.1.224.71:3337")
//            renderer?.recordMovie()
//        }

        thread {
            if (generateVDIs) {
                manageVDIGeneration()
            }
        }

        thread {
            if(benchmarking) {
                doBenchmarks()
            }
        }

//        thread {
//            dynamicSubsampling()
//        }

//        thread {
//            while (true) {
//                Thread.sleep(2000)
//                logger.info("Assigning transfer fn")
//                volumeList[0].transferFunction = volumeList[1].transferFunction
//            }
//        }

//        thread {
//            while(true)
//            {
//                Thread.sleep(2000)
//                println("${cam.spatial().position}")
//                println("${cam.spatial().rotation}")
//
//                logger.info("near plane: ${cam.nearPlaneDistance} and far: ${cam.farPlaneDistance}")
//            }
//        }
        thread {
            Thread.sleep(closeAfter)
            renderer?.shouldClose = true
        }

//        thread {
//            camFlyThrough()
//        }

    }
//    override fun inputSetup() {
//        super.inputSetup()
//
//
//    }

    fun camFlyThrough() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        renderer!!.recordMovie("/datapot/aryaman/owncloud/VDI_Benchmarks/${dataset}_volume.mp4")

        Thread.sleep(2000)

        val maxPitch = 10f
        val maxYaw = 10f

        val minYaw = -10f
        val minPitch = -10f

//        rotateCamera(40f, true)
        var pitchRot = 0.12f
        var yawRot = 0.075f

        var totalYaw = 0f
        var totalPitch = 0f

//        rotateCamera(20f, true)

        moveCamera(yawRot, pitchRot, maxYaw, maxPitch, minPitch, minYaw, totalYaw, totalPitch, 8000f)
        logger.info("Moving to phase 2")
        moveCamera(yawRot, pitchRot * 2, maxYaw, maxPitch * 2, minPitch * 2, minYaw, totalYaw, totalPitch, 2000f)

        zoomCamera(0.99f, 1000f)
        logger.info("Moving to phase 3")
        moveCamera(yawRot * 3, pitchRot * 3, maxYaw, maxPitch, minPitch, minYaw, totalYaw, totalPitch, 8000f)

        cameraMoving = true

        moveCamera(yawRot *2, pitchRot * 5, 40f, 40f, -60f, -50f, totalYaw, totalPitch, 6000f)

        Thread.sleep(1000)

        renderer!!.recordMovie()
    }

    private fun moveCamera(yawRot: Float, pitchRot: Float, maxYaw: Float, maxPitch: Float, minPitch: Float, minYaw: Float, totalY: Float, totalP: Float, duration: Float) {

        var totalYaw = totalY
        var totalPitch = totalP

        var yaw = yawRot
        var pitch = pitchRot

        val startTime = System.nanoTime()

        var cnt = 0

        val list: MutableList<Any> = ArrayList()
        val listDi: MutableList<Any> = ArrayList()

        while (true) {

            cnt += 1

            if(totalYaw < maxYaw && totalYaw > minYaw) {
                rotateCamera(yaw)
                totalYaw += yaw
            } else {
                yaw *= -1f
                rotateCamera(yaw)
                totalYaw += yaw
            }

            if (totalPitch < maxPitch && totalPitch > minPitch) {
                rotateCamera(pitch, true)
                totalPitch += pitch
            } else {
                pitch *= -1f
                rotateCamera(pitch, true)
                totalPitch += pitch
            }
            Thread.sleep(50)

            val currentTime = System.nanoTime()

            if ((currentTime - startTime)/1e6 > duration) {
                break
            }

            if(cnt % 5 == 0 && storeCamera) {
                //save the camera and Di

                val rotArray = floatArrayOf(cam.spatial().rotation.x, cam.spatial().rotation.y, cam.spatial().rotation.z, cam.spatial().rotation.w)
                val posArray = floatArrayOf(cam.spatial().position.x(), cam.spatial().position.y(), cam.spatial().position.z())

                list.add(rotArray)
                list.add(posArray)

                listDi.add(subsamplingFactorImage)

                logger.info("Added to the list $cnt")

            }
        }

        val objectMapper = ObjectMapper(MessagePackFactory())

        val bytes = objectMapper.writeValueAsBytes(list)

        Files.write(Paths.get("${dataset}_${subsampleRay}_camera.txt"), bytes)

        val bytesDi = objectMapper.writeValueAsBytes(listDi)

        Files.write(Paths.get("${dataset}_${subsampleRay}_di.txt"), bytesDi)

        logger.warn("The file has been written")
    }

    fun zoomCamera(factor: Float, duration: Float) {
        cam.targeted = true

        val startTime = System.nanoTime()
        while (true) {
            val distance = (camTarget - cam.spatial().position).length()

            cam.spatial().position = camTarget + cam.forward * distance * (-1.0f * factor)

            Thread.sleep(50)

            if((System.nanoTime() - startTime)/1e6 > duration) {
                break
            }
        }
    }


    private fun doBenchmarks() {
        val r = (hub.get(SceneryElement.Renderer) as Renderer)

        while(!r.firstImageReady) {
            Thread.sleep(200)
        }

        Thread.sleep(5000)

        val rotationInterval = 5f
        var totalRotation = 0f

        for(i in 1..9) {
            val path = "benchmarking/${dataset}/View${viewNumber}/volume_rendering/reference_comp${windowWidth}_${windowHeight}_${totalRotation.toInt()}"
            // take screenshot and wait for async writing
            r.screenshot("$path.png")
            Thread.sleep(2000L)
            stats.clear("Renderer.fps")

            // collect data for a few secs
            Thread.sleep(5000)

            // write out CSV with fps data
            val fps = stats.get("Renderer.fps")!!
            File("$path.csv").writeText("${fps.avg()};${fps.min()};${fps.max()};${fps.stddev()};${fps.data.size}")

            rotateCamera(rotationInterval)
            Thread.sleep(1000) // wait for rotation to take place
            totalRotation = i * rotationInterval
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

        logger.info("cam target: ${camTarget}")

        val distance = (camTarget - cam.spatial().position).length()
        cam.spatial().rotation = pitchQ.mul(cam.spatial().rotation).mul(yawQ).normalize()
        cam.spatial().position = camTarget + cam.forward * distance * (-1.0f)
        logger.info("new camera pos: ${cam.spatial().position}")
        logger.info("new camera rotation: ${cam.spatial().rotation}")
        logger.info("camera forward: ${cam.forward}")
    }

    fun fetchTexture(texture: Texture) : Int {
        val ref = VulkanTexture.getReference(texture)
        val buffer = texture.contents ?: return -1

        if(ref != null) {
            val start = System.nanoTime()
            texture.contents = ref.copyTo(buffer, true)
            val end = System.nanoTime()
            logger.info("The request textures of size ${texture.contents?.remaining()?.toFloat()?.div((1024f*1024f))} took: ${(end.toDouble()-start.toDouble())/1000000.0}")
        } else {
            logger.error("In fetchTexture: Texture not accessible")
        }

        return 0
    }

    private fun createPublisher() : ZMQ.Socket {
        var publisher: ZMQ.Socket = context.createSocket(SocketType.PUB)
        publisher.isConflate = true

        val address: String = "tcp://0.0.0.0:6655"
        val port = try {
            publisher.bind(address)
            address.substringAfterLast(":").toInt()
        } catch (e: ZMQException) {
            logger.warn("Binding failed, trying random port: $e")
            publisher.bindToRandomPort(address.substringBeforeLast(":"))
        }

        return publisher
    }

    private fun stringToQuaternion(inputString: String): Quaternionf {
        val elements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return Quaternionf(elements[0], elements[1], elements[2], elements[3])
    }

    private fun stringToVector3f(inputString: String): Vector3f {
        val mElements = inputString.removeSurrounding("[", "]").split(",").map { it.toFloat() }
        return Vector3f(mElements[0], mElements[1], mElements[2])
    }

    private fun manageVDIGeneration() {
        var subVDIDepthBuffer: ByteBuffer? = null
        var subVDIColorBuffer: ByteBuffer?
        var gridCellsBuff: ByteBuffer?

        while(renderer?.firstImageReady == false) {
//        while(renderer?.firstImageReady == false || volumeManager.shaderProperties.isEmpty()) {
            Thread.sleep(50)
        }

        val subVDIColor = volumeManager.material().textures["OutputSubVDIColor"]!!
        val colorCnt = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIColor to colorCnt)

        val depthCnt = AtomicInteger(0)
        var subVDIDepth: Texture? = null

        if(separateDepth) {
            subVDIDepth = volumeManager.material().textures["OutputSubVDIDepth"]!!
            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIDepth to depthCnt)
        }

        val gridCells = volumeManager.material().textures["OctreeCells"]!!
        val gridTexturesCnt = AtomicInteger(0)

        (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (gridCells to gridTexturesCnt)

        var prevColor = colorCnt.get()
        var prevDepth = depthCnt.get()

        var cnt = 0

        val tGeneration = Timer(0, 0)

        val publisher = createPublisher()

        val subscriber: ZMQ.Socket = context.createSocket(SocketType.SUB)
        subscriber.isConflate = true
//        val address = "tcp://localhost:6655"
        val address = "tcp://10.1.31.61:6655"
        try {
            subscriber.connect(address)
        } catch (e: ZMQException) {
            logger.warn("ZMQ Binding failed.")
        }
        subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)

        val objectMapper = ObjectMapper(MessagePackFactory())

        var compressedColor:  ByteBuffer? = null
        var compressedDepth: ByteBuffer? = null

        val compressor = VDICompressor()
        val compressionTool = VDICompressor.CompressionTool.LZ4

        while (true) {
            tGeneration.start = System.nanoTime()
            while(colorCnt.get() == prevColor || depthCnt.get() == prevDepth) {
                Thread.sleep(5)
            }
            prevColor = colorCnt.get()
            prevDepth = depthCnt.get()
            subVDIColorBuffer = subVDIColor.contents
            if(separateDepth) {
                subVDIDepthBuffer = subVDIDepth!!.contents
            }
            gridCellsBuff = gridCells.contents

            tGeneration.end = System.nanoTime()

            val timeTaken = (tGeneration.end - tGeneration.start)/1e9

            logger.info("Time taken for generation (only correct if VDIs were not being written to disk): ${timeTaken}")

            val camera = cam

            val model = volumeList.first().spatial().world

            val vdiData = VDIData(
                VDIBufferSizes(),
                VDIMetadata(
                    index = cnt,
                    projection = camera.spatial().projection,
                    view = camera.spatial().getTransformation(),
                    volumeDimensions = volumeDims,
                    model = model,
                    nw = volumeList.first().volumeManager.shaderProperties["nw"] as Float,
                    windowDimensions = Vector2i(camera.width, camera.height)
                )
            )

            if(transmitVDIs) {

                val colorSize = windowHeight * windowWidth * maxSupersegments * 4 * 4
                val depthSize = windowWidth * windowHeight * maxSupersegments * 4 * 2

                if(subVDIColorBuffer!!.remaining() != colorSize || subVDIDepthBuffer!!.remaining() != depthSize) {
                    logger.warn("Skipping transmission this frame due to inconsistency in buffer size")
                }

                val compressionTime = measureNanoTime {
                    if(compressedColor == null) {
                        compressedColor = MemoryUtil.memAlloc(compressor.returnCompressBound(colorSize.toLong(), compressionTool))
                    }
                    val compressedColorLength = compressor.compress(compressedColor!!, subVDIColorBuffer!!, 3, compressionTool)
                    compressedColor!!.limit(compressedColorLength.toInt())

                    vdiData.bufferSizes.colorSize = compressedColorLength

                    if(separateDepth) {
                        if(compressedDepth == null) {
                            compressedDepth = MemoryUtil.memAlloc(compressor.returnCompressBound(depthSize.toLong(), compressionTool))
                        }
                        val compressedDepthLength = compressor.compress(compressedDepth!!, subVDIDepthBuffer!!, 3, compressionTool)
                        compressedDepth!!.limit(compressedDepthLength.toInt())

                        vdiData.bufferSizes.depthSize = compressedDepthLength
                    }
                }

                logger.info("Time taken in compressing VDI: ${compressionTime/1e9}")

                val publishTime = measureNanoTime {
                    val metadataOut = ByteArrayOutputStream()
                    VDIDataIO.write(vdiData, metadataOut)

                    val metadataBytes = metadataOut.toByteArray()

                    logger.info("Size of VDI data is: ${metadataBytes.size}")

                    val vdiDataSize = metadataBytes.size.toString().toByteArray(Charsets.US_ASCII)

                    var messageLength = vdiDataSize.size + metadataBytes.size + compressedColor!!.remaining()

                    if(separateDepth) {
                        messageLength += compressedDepth!!.remaining()
                    }

                    val message = ByteArray(messageLength)

                    vdiDataSize.copyInto(message)

                    metadataBytes.copyInto(message, vdiDataSize.size)

                    compressedColor!!.slice().get(message, vdiDataSize.size + metadataBytes.size, compressedColor!!.remaining())

                    if(separateDepth) {
                        compressedDepth!!.slice().get(message, vdiDataSize.size + metadataBytes.size + compressedColor!!.remaining(), compressedDepth!!.remaining())

                        compressedDepth!!.limit(compressedDepth!!.capacity())

                    }

                    compressedColor!!.limit(compressedColor!!.capacity())

                    val sent = publisher.send(message)

                    if(!sent) {
                        logger.warn("There was a ZeroMQ error in queuing the message to send")
                    }

                }

                logger.info("Whole publishing process took: ${publishTime/1e9}")

                val payload = subscriber.recv(0)

                if(payload != null) {
                    val deserialized: List<Any> = objectMapper.readValue(payload, object : TypeReference<List<Any>>() {})

                    cam.spatial().rotation = stringToQuaternion(deserialized[0].toString())
                    cam.spatial().position = stringToVector3f(deserialized[1].toString())
                }

            }

            if(storeVDIs) {
                if(cnt == 4) { //store the 4th VDI
                    val file = FileOutputStream(File("${dataset}vdi_${windowWidth}_${windowHeight}_${maxSupersegments}_${vo}_dump$cnt"))
                    //                    val comp = GZIPOutputStream(file, 65536)
                    VDIDataIO.write(vdiData, file)
                    logger.info("written the dump")
                    file.close()

                    var fileName = ""
                    if(world_abs) {
                        fileName = "${dataset}VDI_${windowWidth}_${windowHeight}_${maxSupersegments}_${vo}_${cnt}_world_new"
                    } else {
                        fileName = "${dataset}VDI_${windowWidth}_${windowHeight}_${maxSupersegments}_${vo}_${cnt}_ndc"
                    }
                    if(separateDepth) {
                        SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col")
                        SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth")
                        SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree")
                    } else {
                        SystemHelpers.dumpToFile(subVDIColorBuffer!!, fileName)
                        SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree")
                    }
                    logger.info("Wrote VDI $cnt")
                    VDIsGenerated.incrementAndGet()
                }
            }
            cnt++
        }
    }

    override fun inputSetup() {
        setupCameraModeSwitching()

        inputHandler?.addBehaviour("rotate_camera", ClickBehaviour { _, _ ->
            rotateCamera(10f)
        })
        inputHandler?.addKeyBinding("rotate_camera", "R")

        inputHandler?.addBehaviour("toggleAO", ClickBehaviour { _, _ ->
            logger.info("toggling AO to ${!ambientOcclusion}")
            volumeList[0].volumeManager.shaderProperties.set("ambientOcclusion", !ambientOcclusion)
            ambientOcclusion = !ambientOcclusion
        })
        inputHandler?.addKeyBinding("toggleAO", "O")

        inputHandler?.addBehaviour("downsample", ClickBehaviour { _, _ ->
            logger.info("Current display width: ${settings.get<Int>("Renderer.displayWidth")}")
            logger.info("Current display height: ${settings.get<Int>("Renderer.displayHeight")}")

            logger.info("Doing down sampling")

            settings.set("Renderer.displayWidth", 64)
            settings.set("Renderer.displayHeight", 54)
//            settings.set("Renderer.SupersamplingFactor", 0.1)
        })
        inputHandler?.addKeyBinding("downsample", "L")

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            AdaptiveVDIGenerator().main()
        }
    }
}
