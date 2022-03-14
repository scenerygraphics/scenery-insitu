package graphics.scenery.insitu

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.Shaders
import graphics.scenery.compute.ComputeMetadata
import graphics.scenery.compute.InvocationType
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.Test
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.nio.ByteBuffer

class VDIRendererSimple_old : SceneryBase("SimpleVDIRenderer", 600, 600) {
    override fun init() {

        renderer = hub.add(SceneryElement.Renderer,
                Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val numSupersegments = 40

        val buff = File("/home/aryaman/Repositories/openfpm_pdata/example/Grid/3_gray_scott_3d/size:1Final_VDICol100.raw").readBytes()
//        depthBuff = File("/home/aryaman/Repositories/openfpm_pdata/example/Grid/3_gray_scott_3d/Final_VDIDepth1594425655.raw").readBytes()

        var colBuffer: ByteBuffer
//        var depthBuffer: ByteBuffer
//        colBuffer = ByteBuffer.wrap(buff)
//        depthBuffer = ByteBuffer.wrap(depthBuff)
        colBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * 3 * 4)
//        depthBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * numSupersegments * 4 * 2)

        colBuffer.put(buff).flip()
//        depthBuffer.put(depthBuff).flip()

        logger.info("Length of color buffer is ${buff.size} and associated bytebuffer capacity is ${colBuffer.capacity()} it has remaining: ${colBuffer.remaining()}")
//        logger.info("Length of depth buffer is ${depthBuff.size} and associated bytebuffer capacity is ${depthBuffer.capacity()} it has remianing ${depthBuffer.remaining()}")

        logger.info("Col sum is ${buff.sum()}")
//        logger.info("Depth sum is ${depthBuff.sum()}")


        val outputBuffer = MemoryUtil.memCalloc(windowHeight * windowWidth * 4)

        val compute = RichNode()
        compute.name = "compute node"
        compute.setMaterial(ShaderMaterial(Shaders.ShadersFromFiles(arrayOf("SimpleVDIRenderer.comp"), this::class.java)))
        compute.material().textures["OutputViewport"] = Texture.fromImage(Image(outputBuffer, windowWidth, windowHeight), usage = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
        compute.material().textures["OutputViewport"]!!.mipmap = false
        compute.metadata["ComputeMetadata"] = ComputeMetadata(
                workSizes = Vector3i(512, 512, 1),
                invocationType = InvocationType.Once
        )
        compute.material().textures["InputVDI"] = Texture(Vector3i(numSupersegments*3, windowHeight, windowWidth), 4, contents = colBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))
//        compute.material.textures["DepthVDI"] = Texture(Vector3i(2*numSupersegments, windowHeight, windowWidth), 4, contents = depthBuffer, usageType = hashSetOf(Texture.UsageType.LoadStoreImage, Texture.UsageType.Texture))

        scene.addChild(compute)

        val plane = FullscreenObject()
        plane.material().textures["diffuse"] = compute.material().textures["OutputViewport"]!!

        scene.addChild(plane)

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

//        thread {
//            while (running) {
//                box.rotation.rotateY(0.01f)
//                box.needsUpdate = true
////                box.material.textures["diffuse"] = compute.material.textures["OutputViewport"]!!
//
//                Thread.sleep(20)
//            }
//        }
    }

    @Test
    override fun main() {
        super.main()
    }
}