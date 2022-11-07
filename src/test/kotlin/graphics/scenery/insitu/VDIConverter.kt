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
import graphics.scenery.volumes.vdi.VDIBufferSizes
import graphics.scenery.volumes.vdi.VDIData
import graphics.scenery.volumes.vdi.VDIDataIO
import graphics.scenery.volumes.vdi.VDIMetadata
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.real.FloatType
import org.joml.*
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CustomNodeSimple : RichNode() {
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
}


class VDIConverter : SceneryBase("VDIConverter", 512, 512) {

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

    val separateDepth = true
    val colors32bit = true

    val commSize = 4
    val rank = 0

    override fun init() {

        val numLayers = if(separateDepth) {
            1
        } else {
            3
        }

        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val numSupersegments = 20

        val buff: ByteArray
        val depthBuff: ByteArray?

        var dataset = "Engine"

        val basePath = "/home/aryaman/Repositories/scenery-insitu/"

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            spatial().position = Vector3f( 5.699E+0f, -4.935E-1f,  5.500E+0f)
//            spatial().position = Vector3f( 6.284E+0f, -4.932E-1f, 4.787E+0f)
            spatial().rotation = Quaternionf( 1.211E-1, -3.842E-1 ,-5.090E-2,  9.139E-1)
//            spatial().rotation = Quaternionf( 1.162E-1, -4.624E-1, -6.126E-2,  8.769E-1)
            perspectiveCamera(33.7168f, windowWidth, windowHeight, farPlaneLocation = 20000f)

            scene.addChild(this)
        }
//        cam.farPlaneDistance = 200.0f

        //now cam should have the correct projection matrix (hopefully)

        if(separateDepth) {
            buff = File(basePath + "${dataset}VDI_${windowHeight}_4_ndc_col").readBytes()
            depthBuff = File(basePath + "${dataset}VDI_${windowHeight}_4_ndc_depth").readBytes()

        } else {
            buff = File("/home/aryaman/Repositories/scenery-insitu/VDI10_ndc").readBytes()
            depthBuff = null
        }

        var colBuffer: ByteBuffer
        var depthBuffer: ByteBuffer?

        colBuffer = if(colors32bit) {
            MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * numLayers * 4 * 4)
        } else {
            MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * numLayers * 4)
        }
        colBuffer.put(buff).flip()
        logger.info("Length of color buffer is ${buff.size} and associated bytebuffer capacity is ${colBuffer.capacity()} it has remaining: ${colBuffer.remaining()}")
        logger.info("Col sum is ${buff.sum()}")

        if(separateDepth) {
            depthBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * 2 * 2 * 2)
            depthBuffer.put(depthBuff).flip()
            logger.info("Length of depth buffer is ${depthBuff!!.size} and associated bytebuffer capacity is ${depthBuffer.capacity()} it has remaining ${depthBuffer.remaining()}")
            logger.info("Depth sum is ${depthBuff.sum()}")
        } else {
            depthBuffer = null
        }

        val outputBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

        val compute = CustomNodeSimple()
        compute.name = "compute node"

        compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("ConvertToNDC.comp"), this@VDIConverter::class.java))) {
//        compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("SimpleVDIRendererIntDepths.comp"), this@VDIRendererSimple::class.java))) {
            textures["OutputViewport"] = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
            textures["OutputViewport"]!!.mipmap = false
        }

        compute.metadata["ComputeMetadata"] = ComputeMetadata(
            workSizes = Vector3i(windowWidth, windowHeight, 1),
            invocationType = InvocationType.Permanent
        )

        val bufType = if(colors32bit) {
            FloatType()
        } else {
            UnsignedByteType()
        }

        val outputColorBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*4*numSupersegments*numLayers * 4)
        val outputDepthBuffer = MemoryUtil.memCalloc(windowHeight*windowWidth*2*numSupersegments*2 * 2)


        val outputColor =
            Texture.fromImage(
                Image(outputColorBuffer, numLayers * numSupersegments, windowHeight, windowWidth), usage = hashSetOf(
                    Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 4, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)


        val outputDepth = Texture.fromImage(
            Image(outputDepthBuffer, 2 * numSupersegments, windowHeight, windowWidth),  usage = hashSetOf(
                Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), channels = 1, mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        compute.material().textures["OutputColor"] = outputColor
        compute.material().textures["OutputDepth"] = outputDepth

        compute.material().textures["InputVDI"] = Texture(Vector3i(numSupersegments*numLayers, windowHeight, windowWidth), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture)
            , type = bufType,
            mipmap = false,
//            normalized = false,
            minFilter = Texture.FilteringMode.NearestNeighbour,
            maxFilter = Texture.FilteringMode.NearestNeighbour
        )
        compute.material().textures["DepthVDI"] = Texture(Vector3i(2 * numSupersegments, windowHeight, windowWidth),  channels = 1, contents = depthBuffer, usageType = hashSetOf(
            Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture), type = FloatType(), mipmap = false, normalized = false, minFilter = Texture.FilteringMode.NearestNeighbour, maxFilter = Texture.FilteringMode.NearestNeighbour)

        val inv_viewOrig =

        Matrix4f(-0.941669f,	-0.025429f,	0.335579f,	0.000000f,
            0.000000f,	0.997141f,	0.075559f,	0.000000f,
                    -0.336541f,	0.071151f,	-0.938977f,	0.000000f,
//                    -313.645f,	74.846016f,	-822.299500f,	1.000000f
                    -0.3136f,	0.074846f,	-0.8223f,	1.000000f
        ) //Engine dataset

//        Matrix4f(-0.941669f,	-0.025429f,	0.335579f,	0.000000f,
//            0.000000f,	0.997141f,	0.075559f,	0.000000f,
//            -0.336541f,	0.071151f,	-0.938977f,	0.000000f,
//            -1.659809326f,	0.359451904f,	-4.578207520f,	1.000000f
//        ) //RayleighTaylor

//        Matrix4f(-0.941669f,	-0.025429f,	0.335579f,	0.000000f,
//            0.000000f,	0.997141f,	0.075559f,	0.000000f,
//            -0.336541f,	0.071151f,	-0.938977f,	0.000000f,
//            -1.154997803f,	0.252724701f,	-3.169741943f,	1.000000f
//        ) //Kingsnake

        val modelOrig = Matrix4f(2.000E+0f, 0.000E+0f,  0.000E+0f,  0.000E+0f,
            0.000E+0f, 2.000E+0f,  0.000E+0f,  0.000E+0f,
            0.000E+0f, 0.000E+0f,  2.000E+0f,  0.000E+0f,
            0.000E+0f, 0.000E+0f,  0.000E+0f,  1.000E+0f
        )

        compute.ProjectionOriginal = cam.spatial().projection
        compute.invProjectionOriginal = Matrix4f(cam.spatial().projection).invert()
        compute.ViewOriginal = Matrix4f(inv_viewOrig).invert()
        compute.invViewOriginal = inv_viewOrig

        scene.addChild(compute)

        val plane = FullscreenObject()
        plane.material().textures["diffuse"] = compute.material().textures["OutputViewport"]!!

        scene.addChild(plane)

        val light = PointLight(radius = 15.0f)
        light.position = Vector3f(0.0f, 0.0f, 2.0f)
        light.intensity = 5.0f
        light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
        scene.addChild(light)

        thread {
            var subVDIDepthBuffer: ByteBuffer? = null
            var subVDIColorBuffer: ByteBuffer?
            var gridCellsBuff: ByteBuffer?
            var thresholdBuff: ByteBuffer?

            while(renderer?.firstImageReady == false) {
                Thread.sleep(50)
            }

            val subVDIColor = compute.material().textures["OutputColor"]!!
            val colorCnt = AtomicInteger(0)

            (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIColor to colorCnt)

            val depthCnt = AtomicInteger(0)
            var subVDIDepth: Texture? = null

            if(separateDepth) {
                subVDIDepth = compute.material().textures["OutputDepth"]!!
                (renderer as? VulkanRenderer)?.persistentTextureRequests?.add (subVDIDepth to depthCnt)
            }

            var prevColor = colorCnt.get()
            var prevDepth = depthCnt.get()

            while(colorCnt.get() == prevColor || depthCnt.get() == prevDepth) {
                Thread.sleep(5)
            }

            subVDIColorBuffer = subVDIColor.contents
            subVDIDepthBuffer = subVDIDepth!!.contents

            val vdiData = VDIData(
                VDIBufferSizes(),
                VDIMetadata(
                    index = 0,
                    projection = cam.spatial().projection,
                    view = Matrix4f(inv_viewOrig).invert(),
                    volumeDimensions = Vector3f(256f),
                    model = modelOrig,
                    nw = 0f,
                    windowDimensions = Vector2i(cam.width, cam.height)
                )
            )

            val file = FileOutputStream(File("${dataset}Correctedvdidump4"))
            VDIDataIO.write(vdiData, file)
            logger.info("written the dump")
            file.close()

            SystemHelpers.dumpToFile(subVDIColorBuffer!!, "${dataset}CorrectedVDI4_ndc_col")
            SystemHelpers.dumpToFile(subVDIDepthBuffer!!, "${dataset}CorrectedVDI4_ndc_depth")

        }

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            VDIConverter().main()
        }
    }
}
