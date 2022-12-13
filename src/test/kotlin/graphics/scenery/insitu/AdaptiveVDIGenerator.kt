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
import kotlin.math.max
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
    lateinit var volumeCommons: VolumeCommons
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

    @Volatile
    var runGeneration = false
    @Volatile
    var runThreshSearch = true

    val vdisGenerated = AtomicInteger(0)

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

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        if(generateVDIs) {
            benchmarking = false
            //set up volume manager

            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, maxSupersegments, scene, hub, true)

            hub.add(volumeManager)

        } else {
            volumeManager = VDIVolumeManager.create(windowWidth, windowHeight, scene, hub)

            hub.add(volumeManager)
        }

        volumeCommons = VolumeCommons(windowWidth, windowHeight, dataset, logger)

        volumeCommons.positionCamera(cam)
        scene.addChild(cam)

        val tf = volumeCommons.setupTransferFunction()

        val parent = volumeCommons.setupVolumes(volumeList, tf, hub)

        scene.addChild(parent)


        val pivot = volumeCommons.setupPivot(parent)

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
        var prefixBuffer: ByteBuffer? = null
        var numGeneratedBuff: ByteBuffer? = null

        while(renderer?.firstImageReady == false) {
//        while(renderer?.firstImageReady == false || volumeManager.shaderProperties.isEmpty()) {
            Thread.sleep(50)
        }

        val subVDIColor = volumeManager.material().textures["OutputSubVDIColor"]!!

        val subVDIDepth = volumeManager.material().textures["OutputSubVDIDepth"]!!

        val gridCells = volumeManager.material().textures["OctreeCells"]!!

        val numGeneratedTexture = volumeManager.material().textures["SupersegmentsGenerated"]!!

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

        var totalSupersegmentsGenerated = 0

        (renderer as VulkanRenderer).postRenderLambdas.add {
            if(runThreshSearch) {
                //fetch numgenerated, calculate and upload prefixsums

                val generated = fetchTexture(numGeneratedTexture)

                if(generated < 0) {
                    logger.error("Error fetching texture. generated: $generated")
                }

                numGeneratedBuff = numGeneratedTexture.contents
                val numGeneratedIntBuffer = numGeneratedBuff!!.asIntBuffer()

                val prefixTime = measureNanoTime {
                    prefixBuffer = MemoryUtil.memAlloc(windowWidth * windowHeight * 4)
                    val prefixIntBuff = prefixBuffer!!.asIntBuffer()

                    prefixIntBuff.put(0, 0)

                    for(i in 1 until windowWidth * windowHeight) {
                        prefixIntBuff.put(i, prefixIntBuff.get(i-1) + numGeneratedIntBuffer.get(i-1))
//                    if(i%100 == 0) {
//                        logger.info("i: $i. The numbers added were: ${prefixIntBuff.get(i-1)} and ${distributionIntBuff.get(i)}")
//                    }
                    }

                    totalSupersegmentsGenerated = prefixIntBuff.get(windowWidth*windowHeight-1) + numGeneratedIntBuffer.get(windowWidth*windowHeight-1)
                }
                logger.info("Prefix sum took ${prefixTime/1e9} to compute")


                volumeList.first().volumeManager.material().textures["PrefixSums"] = Texture(
                    Vector3i(windowWidth, windowHeight, 1), 1, contents = prefixBuffer, usageType = hashSetOf(
                        Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
                    , type = IntType(),
                    mipmap = false,
                    minFilter = Texture.FilteringMode.NearestNeighbour,
                    maxFilter = Texture.FilteringMode.NearestNeighbour
                )

                runThreshSearch = false
                runGeneration = true
            } else {
                // fetch the VDI
                val col = fetchTexture(subVDIColor)
                val depth = fetchTexture(subVDIDepth)
                val grid = fetchTexture(gridCells)

                if(col < 0 || depth < 0 || grid < 0) {
                    logger.error("Error fetching texture. col: $col, depth: $depth, grid: $grid")
                }

                vdisGenerated.incrementAndGet()

                runGeneration = false
                runThreshSearch = true
            }
        }

        (renderer as VulkanRenderer).postRenderLambdas.add {
            volumeList.first().volumeManager.shaderProperties["doGeneration"] = runGeneration
            volumeList.first().volumeManager.shaderProperties["doThreshSearch"] = runThreshSearch
        }

        var generatedSoFar = 0

        while (true) {
            tGeneration.start = System.nanoTime()

            while((vdisGenerated.get() <= generatedSoFar)) {
                Thread.sleep(5)
            }

            generatedSoFar = vdisGenerated.get()

            subVDIColorBuffer = subVDIColor.contents
            subVDIDepthBuffer = subVDIDepth.contents
            gridCellsBuff = gridCells.contents

            logger.info("total supersegments generated $totalSupersegmentsGenerated, which is ${totalSupersegmentsGenerated.toDouble() / (windowWidth * windowHeight * maxSupersegments).toDouble()} of maximum")

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
                    volumeDimensions = volumeCommons.volumeDims,
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
                        logger.info("Buffer sizes are: ${subVDIColorBuffer!!.capacity()}, ${subVDIDepthBuffer!!.capacity()}")
                        subVDIColorBuffer.limit(totalSupersegmentsGenerated * 4 * 4)
                        subVDIDepthBuffer.limit(2 * totalSupersegmentsGenerated * 4)
                        SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${fileName}_col_rle")
                        SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${fileName}_depth_rle")
                        SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree_rle")
                        SystemHelpers.dumpToFile(prefixBuffer!!, "${fileName}_prefix")
                        SystemHelpers.dumpToFile(numGeneratedBuff!!, "${fileName}_supersegments_generated")
                    } else {
                        SystemHelpers.dumpToFile(subVDIColorBuffer!!, fileName)
                        SystemHelpers.dumpToFile(gridCellsBuff!!, "${fileName}_octree")
                    }
                    logger.info("Wrote VDI $cnt")
                    vdisGenerated.incrementAndGet()
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
